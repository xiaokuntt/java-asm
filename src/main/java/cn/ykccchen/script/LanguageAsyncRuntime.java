package cn.ykccchen.script;

import cn.ykccchen.script.exception.ScriptRuntimeException;
import cn.ykccchen.script.exception.ScriptExecutionException;
import cn.ykccchen.script.exception.ScriptAsyncRejectedException;
import cn.ykccchen.script.exception.ScriptErrorCode;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * Runtime bridge used by compiled language-level {@code async} expressions.
 */
public final class LanguageAsyncRuntime {

	private static final ThreadLocal<Integer> ASYNC_DEPTH = new ThreadLocal<>();
	private static final ThreadLocal<Long> CURRENT_TASK = new ThreadLocal<>();
	private static final int BRIDGE_THREADS = Math.min(32,
			Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
	private static final LongAdder BRIDGE_REJECTIONS = new LongAdder();
	private static final LongAdder ABANDONED_TASKS = new LongAdder();
	private static final ThreadPoolExecutor DEFAULT_BLOCKING_EXECUTOR = new ThreadPoolExecutor(
			BRIDGE_THREADS, BRIDGE_THREADS, 30, TimeUnit.SECONDS,
			new ArrayBlockingQueue<Runnable>(1024), new ThreadFactory() {
				private final java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong();
				@Override
				public Thread newThread(Runnable runnable) {
					Thread thread = new Thread(runnable, "script-future-bridge-" + sequence.incrementAndGet());
					thread.setDaemon(true);
					return thread;
				}
			}, new ThreadPoolExecutor.AbortPolicy());

	static {
		DEFAULT_BLOCKING_EXECUTOR.allowCoreThreadTimeOut(true);
	}

	private LanguageAsyncRuntime() {
	}

	@SuppressWarnings("unchecked")
	public static ScriptAsyncResult submit(ScriptContext context, Function function, Object argument) {
		ScriptContext requiredContext = Objects.requireNonNull(context, "context");
		if (requiredContext instanceof ScriptDebugContext) {
			throw new ScriptRuntimeException(ScriptErrorCode.SCRIPT_ASYNC_ERROR,
					"语言级 async 暂不支持跨线程调试");
		}
		ExecutorService executor = requiredContext.getLanguageAsyncExecutor();
		if (executor == null) {
			throw new ScriptRuntimeException(ScriptErrorCode.SCRIPT_ASYNC_ERROR,
					"语言级 async 未配置 ExecutorService");
		}
		Integer currentDepth = ASYNC_DEPTH.get();
		Long currentTask = CURRENT_TASK.get();
		ScriptAsyncResult result = new ScriptAsyncResult(requiredContext,
				currentTask == null ? requiredContext.getAsyncTaskId() : currentTask,
				currentDepth == null ? 1 : currentDepth + 1);
		result.emitQueued();
		if (!requiredContext.registerLanguageAsyncTask(result)) {
			result.reject(new ScriptAsyncRejectedException("语言级异步子任务数量已达到上限"));
			return result;
		}
		Runnable invocation = () -> invoke(result, requiredContext, function, argument);
		if (isAsyncWorker() && shouldInline(requiredContext, executor)) {
			invocation.run();
			return result;
		}
		try {
			Future<?> workerFuture = executor.submit(invocation);
			result.setWorkerFuture(workerFuture);
		} catch (RejectedExecutionException exception) {
			result.reject(exception instanceof ScriptAsyncRejectedException
					? exception : new ScriptAsyncRejectedException("语言级异步执行器拒绝任务", exception));
		}
		return result;
	}

	public static Object await(ScriptContext context, Object value) {
		Objects.requireNonNull(context, "context");
		if (value instanceof ScriptAsyncResult) {
			((ScriptAsyncResult) value).markFailureObserved();
		}
		try {
			if (value instanceof CompletionStage) {
				return ((CompletionStage<?>) value).toCompletableFuture().get();
			}
			if (value instanceof Future) {
				return ((Future<?>) value).get();
			}
			throw new ScriptRuntimeException(ScriptErrorCode.SCRIPT_ASYNC_ERROR,
					"await 只支持 Future 或 CompletionStage，实际类型："
					+ (value == null ? "null" : value.getClass().getName()));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new ScriptExecutionException(
					ScriptExecutionException.Reason.CANCELLED, "await 等待被取消");
		} catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof RuntimeException) {
				throw (RuntimeException) cause;
			}
			if (cause instanceof Error) {
				throw (Error) cause;
			}
			throw new ScriptRuntimeException("await 异步任务失败", cause);
		}
	}

	/** Returns a stage that succeeds with an ordered list after every input succeeds. */
	public static CompletionStage<Object> all(ScriptContext context, Object values) {
		List<Object> inputs = inputs(values);
		List<CompletableFuture<Object>> futures = new ArrayList<>(inputs.size());
		for (Object input : inputs) {
			futures.add(asFuture(context, input));
		}
		CancellableFuture result = new CancellableFuture(inputs);
		CompletableFuture<?>[] array = futures.toArray(new CompletableFuture<?>[0]);
		CompletableFuture.allOf(array).whenComplete((ignored, failure) -> {
			if (failure != null) {
				result.completeExceptionally(unwrapCompletionFailure(failure));
				return;
			}
			List<Object> resolved = new ArrayList<>(futures.size());
			for (CompletableFuture<Object> future : futures) {
				resolved.add(future.join());
			}
			result.complete(Collections.unmodifiableList(resolved));
		});
		return result;
	}

	/** Returns a stage completed by the first input, whether it succeeds or fails. */
	public static CompletionStage<Object> race(ScriptContext context, Object values) {
		List<Object> inputs = inputs(values);
		if (inputs.isEmpty()) {
			throw new ScriptRuntimeException("Async.race 至少需要一个异步任务");
		}
		CancellableFuture result = new CancellableFuture(inputs);
		for (Object input : inputs) {
			asFuture(context, input).whenComplete((value, failure) -> {
				boolean completed;
				if (failure == null) {
					completed = result.complete(value);
				} else {
					completed = result.completeExceptionally(unwrapCompletionFailure(failure));
				}
				if (completed && context.getLanguageAsyncPolicy().isCancelRaceLosers()) {
					result.cancelSources(input);
				}
			});
		}
		return result;
	}

	/** Applies a deadline to a Future or CompletionStage without owning a scheduler. */
	public static CompletionStage<Object> timeout(ScriptContext context, Object value,
			long timeout, TimeUnit unit) {
		Objects.requireNonNull(context, "context");
		if (timeout <= 0) {
			throw new IllegalArgumentException("timeout must be greater than zero");
		}
		ScheduledExecutorService scheduler = context.getLanguageAsyncScheduler();
		if (scheduler == null) {
			throw new ScriptRuntimeException("Async.timeout 未配置 ScheduledExecutorService");
		}
		CompletableFuture<Object> source = asFuture(context, value);
		CancellableFuture result = new CancellableFuture(Collections.singletonList(value));
		ScheduledFuture<?> deadline;
		try {
			deadline = scheduler.schedule(() -> {
				TimeoutException failure = new TimeoutException("语言级异步组合任务执行超时");
				if (result.completeExceptionally(failure) && value instanceof Future) {
					((Future<?>) value).cancel(true);
				}
			}, timeout, Objects.requireNonNull(unit, "unit"));
		} catch (RejectedExecutionException exception) {
			result.completeExceptionally(exception);
			return result;
		}
		source.whenComplete((resolved, failure) -> {
			deadline.cancel(false);
			if (failure == null) {
				result.complete(resolved);
			} else {
				result.completeExceptionally(unwrapCompletionFailure(failure));
			}
		});
		return result;
	}

	private static List<Object> inputs(Object values) {
		if (values == null) {
			return Collections.emptyList();
		}
		if (values instanceof Iterable) {
			List<Object> result = new ArrayList<>();
			for (Object value : (Iterable<?>) values) {
				result.add(value);
			}
			return result;
		}
		if (values.getClass().isArray()) {
			int length = Array.getLength(values);
			List<Object> result = new ArrayList<>(length);
			for (int i = 0; i < length; i++) {
				result.add(Array.get(values, i));
			}
			return result;
		}
		return Collections.singletonList(values);
	}

	@SuppressWarnings("unchecked")
	private static CompletableFuture<Object> asFuture(ScriptContext context, Object value) {
		if (value instanceof ScriptAsyncResult) {
			return ((ScriptAsyncResult) value).completion().toCompletableFuture();
		}
		if (value instanceof CompletionStage) {
			return ((CompletionStage<Object>) value).toCompletableFuture();
		}
		if (value instanceof Future) {
			ExecutorService executor = context.getLanguageBlockingExecutor();
			if (executor == null) {
				executor = DEFAULT_BLOCKING_EXECUTOR;
			}
			CancellableFuture result = new CancellableFuture(Collections.singletonList(value));
			try {
				executor.submit(() -> {
					try {
						result.complete(((Future<?>) value).get());
					} catch (ExecutionException exception) {
						result.completeExceptionally(exception.getCause());
					} catch (CancellationException exception) {
						result.cancel(false);
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						result.completeExceptionally(exception);
					}
				});
			} catch (RejectedExecutionException exception) {
				if (executor == DEFAULT_BLOCKING_EXECUTOR) {
					BRIDGE_REJECTIONS.increment();
				}
				result.completeExceptionally(exception);
			}
			return result;
		}
		throw new ScriptRuntimeException("异步组合只支持 Future 或 CompletionStage，实际类型："
				+ (value == null ? "null" : value.getClass().getName()));
	}

	private static Throwable unwrapCompletionFailure(Throwable failure) {
		Throwable current = failure;
		while ((current instanceof CompletionException || current instanceof ExecutionException)
				&& current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	@SuppressWarnings("unchecked")
	private static void invoke(ScriptAsyncResult result, ScriptContext context,
			Function function, Object argument) {
		if (!result.start()) {
			return;
		}
		Integer previousDepth = ASYNC_DEPTH.get();
		Long previousTask = CURRENT_TASK.get();
		ASYNC_DEPTH.set(result.getTrace().getDepth());
		CURRENT_TASK.set(result.getTaskId());
		try {
			context.checkpoint();
			Object value = function.apply(argument);
			context.checkpoint();
			result.succeed(value);
		} catch (Throwable throwable) {
			result.fail(throwable);
			if (throwable instanceof VirtualMachineError) {
				throw (VirtualMachineError) throwable;
			}
			if (throwable instanceof ThreadDeath) {
				throw (ThreadDeath) throwable;
			}
		} finally {
			if (previousDepth == null) {
				ASYNC_DEPTH.remove();
			} else {
				ASYNC_DEPTH.set(previousDepth);
			}
			if (previousTask == null) {
				CURRENT_TASK.remove();
			} else {
				CURRENT_TASK.set(previousTask);
			}
			result.workerExited();
		}
	}

	private static boolean isAsyncWorker() {
		Integer depth = ASYNC_DEPTH.get();
		return depth != null && depth > 0;
	}

	private static boolean shouldInline(ScriptContext context, ExecutorService executor) {
		ScriptNestedAsyncPolicy policy = context.getLanguageAsyncPolicy().getNestedAsyncPolicy();
		if (policy == ScriptNestedAsyncPolicy.INLINE) return true;
		if (policy == ScriptNestedAsyncPolicy.EXECUTOR) return false;
		if (executor instanceof ThreadPoolExecutor) {
			ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
			boolean idleWorker = pool.getActiveCount() < pool.getPoolSize();
			boolean createsCoreWorker = pool.getPoolSize() < pool.getCorePoolSize();
			boolean growsPastCore = pool.getQueue().remainingCapacity() == 0
					&& pool.getPoolSize() < pool.getMaximumPoolSize();
			return !(idleWorker || createsCoreWorker || growsPastCore);
		}
		return true;
	}

	static void recordAbandonedTask() {
		ABANDONED_TASKS.increment();
	}

	public static ScriptAsyncRuntimeStats stats() {
		return new ScriptAsyncRuntimeStats(DEFAULT_BLOCKING_EXECUTOR.getPoolSize(),
				DEFAULT_BLOCKING_EXECUTOR.getActiveCount(), DEFAULT_BLOCKING_EXECUTOR.getQueue().size(),
				DEFAULT_BLOCKING_EXECUTOR.getCompletedTaskCount(), BRIDGE_REJECTIONS.sum(),
				ABANDONED_TASKS.sum());
	}

	private static final class CancellableFuture extends CompletableFuture<Object> {
		private final List<Object> sources = new CopyOnWriteArrayList<>();

		private CancellableFuture(List<Object> sources) {
			this.sources.addAll(sources);
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			boolean cancelled = super.cancel(mayInterruptIfRunning);
			if (cancelled) {
				cancelSources(null, mayInterruptIfRunning);
			}
			return cancelled;
		}

		private void cancelSources(Object winner) {
			cancelSources(winner, true);
		}

		private void cancelSources(Object winner, boolean mayInterruptIfRunning) {
			for (Object source : sources) {
				if (source != winner && source instanceof Future) {
					((Future<?>) source).cancel(mayInterruptIfRunning);
				}
			}
		}
	}
}
