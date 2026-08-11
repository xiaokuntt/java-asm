package cn.ykccchen.script;

import cn.ykccchen.script.exception.ScriptExecutionException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Future returned by the language-level {@code async} expression.
 */
public final class ScriptAsyncResult implements Future<Object> {

	private final long taskId = ScriptTaskIds.next();
	private final ScriptAsyncTrace trace;
	private final ScriptContext context;
	private final AtomicReference<ScriptTaskState> state =
			new AtomicReference<>(ScriptTaskState.QUEUED);
	private final AtomicBoolean unregistered = new AtomicBoolean();
	private final AtomicBoolean failureObserved = new AtomicBoolean();
	private final AtomicBoolean workerExited = new AtomicBoolean();
	private final CompletableFuture<Object> completion = new CompletableFuture<>();
	private final long submittedAtMillis = System.currentTimeMillis();
	private final long submittedNanos = System.nanoTime();
	private volatile long startedAtMillis;
	private volatile long completedAtMillis;
	private volatile long startedNanos;
	private volatile long completedNanos;
	private volatile Throwable failure;
	private volatile Future<?> workerFuture;

	ScriptAsyncResult(ScriptContext context, long parentTaskId, int depth) {
		this.context = context;
		this.trace = new ScriptAsyncTrace(taskId, parentTaskId, depth, context);
	}

	ScriptAsyncResult(ScriptContext context) {
		this(context, 0, 1);
	}

	public long getTaskId() {
		return taskId;
	}

	public ScriptAsyncTrace getTrace() {
		return trace;
	}

	public ScriptTaskState getState() {
		return state.get();
	}

	public CompletionStage<Object> completion() {
		return completion;
	}

	@Override
	public boolean isCancelled() {
		return completion.isCancelled();
	}

	@Override
	public boolean isDone() {
		return completion.isDone();
	}

	@Override
	public Object get() throws InterruptedException, ExecutionException {
		failureObserved.set(true);
		return completion.get();
	}

	@Override
	public Object get(long timeout, TimeUnit unit)
			throws InterruptedException, ExecutionException, TimeoutException {
		failureObserved.set(true);
		return completion.get(timeout, unit);
	}

	public Throwable getFailure() {
		return failure;
	}

	boolean isFailureObserved() {
		return failureObserved.get();
	}

	void markFailureObserved() {
		failureObserved.set(true);
	}

	public boolean cancel() {
		return cancel(true);
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		while (true) {
			ScriptTaskState current = state.get();
			if (current == ScriptTaskState.QUEUED) {
				if (!state.compareAndSet(current, ScriptTaskState.CANCELLED)) {
					continue;
				}
				markCompleted();
				Future<?> future = workerFuture;
				if (future != null) {
					future.cancel(false);
				}
				completion.cancel(false);
				emit(null);
				unregister();
				return true;
			}
			if (current == ScriptTaskState.RUNNING) {
				if (!state.compareAndSet(current, ScriptTaskState.CANCELLING)) {
					continue;
				}
				emit(null);
				Future<?> future = workerFuture;
				if (future != null) {
					future.cancel(mayInterruptIfRunning);
				}
				return true;
			}
			return false;
		}
	}

	void timeout() {
		while (true) {
			ScriptTaskState current = state.get();
			if (current == ScriptTaskState.QUEUED) {
				if (!state.compareAndSet(current, ScriptTaskState.TIMED_OUT)) {
					continue;
				}
				ScriptExecutionException exception = timeoutException();
				failure = exception;
				markCompleted();
				Future<?> future = workerFuture;
				if (future != null) {
					future.cancel(false);
				}
				completion.completeExceptionally(exception);
				emit(exception);
				unregister();
				return;
			}
			if (current == ScriptTaskState.RUNNING) {
				if (!state.compareAndSet(current, ScriptTaskState.TIMING_OUT)) {
					continue;
				}
				emit(null);
				Future<?> future = workerFuture;
				if (future != null) {
					future.cancel(true);
				}
			}
			return;
		}
	}

	boolean start() {
		if (!state.compareAndSet(ScriptTaskState.QUEUED, ScriptTaskState.RUNNING)) {
			return false;
		}
		startedAtMillis = System.currentTimeMillis();
		startedNanos = System.nanoTime();
		emit(null);
		return true;
	}

	void succeed(Object value) {
		finish(value, null);
	}

	void fail(Throwable throwable) {
		finish(null, throwable);
	}

	void reject(RejectedExecutionException exception) {
		if (state.compareAndSet(ScriptTaskState.QUEUED, ScriptTaskState.REJECTED)) {
			failure = exception;
			markCompleted();
			completion.completeExceptionally(exception);
			emit(exception);
			unregister();
		}
	}

	void workerExited() {
		workerExited.set(true);
		context.languageAsyncWorkerExited(this);
	}

	boolean hasWorkerExited() { return workerExited.get(); }

	void setWorkerFuture(Future<?> workerFuture) {
		this.workerFuture = workerFuture;
		ScriptTaskState current = state.get();
		if (current == ScriptTaskState.CANCELLED || current == ScriptTaskState.TIMED_OUT
				|| current == ScriptTaskState.ABANDONED) {
			workerFuture.cancel(false);
		} else if (current == ScriptTaskState.CANCELLING || current == ScriptTaskState.TIMING_OUT) {
			workerFuture.cancel(true);
		}
	}

	boolean abandon() {
		while (true) {
			ScriptTaskState current = state.get();
			if (current.isTerminal()) {
				return false;
			}
			if (!state.compareAndSet(current, ScriptTaskState.ABANDONED)) {
				continue;
			}
			ScriptExecutionException exception = new ScriptExecutionException(
					ScriptExecutionException.Reason.CANCELLED,
					"语言级异步任务取消后未在限定时间内退出，已脱离父作用域");
			failure = exception;
			markCompleted();
			context.markLanguageAsyncTaskAbandoned(this);
			completion.completeExceptionally(exception);
			emit(exception);
			unregister();
			LanguageAsyncRuntime.recordAbandonedTask();
			return true;
		}
	}

	void emitQueued() {
		emit(null);
	}

	private void finish(Object value, Throwable throwable) {
		while (true) {
			ScriptTaskState current = state.get();
			ScriptTaskState terminal;
			Throwable terminalFailure = throwable;
			if (current == ScriptTaskState.CANCELLING) {
				terminal = ScriptTaskState.CANCELLED;
			} else if (current == ScriptTaskState.TIMING_OUT) {
				terminal = ScriptTaskState.TIMED_OUT;
				terminalFailure = timeoutException();
			} else if (current == ScriptTaskState.RUNNING) {
				terminal = classify(throwable);
				if (terminal == ScriptTaskState.TIMED_OUT) {
					terminalFailure = throwable instanceof ScriptExecutionException
							? throwable : timeoutException();
				}
			} else {
				return;
			}
			if (!state.compareAndSet(current, terminal)) {
				continue;
			}
			failure = terminalFailure;
			markCompleted();
			if (terminal == ScriptTaskState.SUCCEEDED) {
				completion.complete(value);
			} else if (terminal == ScriptTaskState.CANCELLED) {
				completion.cancel(false);
			} else {
				completion.completeExceptionally(terminalFailure);
			}
			emit(terminalFailure);
			unregister();
			return;
		}
	}

	private ScriptTaskState classify(Throwable throwable) {
		if (throwable == null) {
			return ScriptTaskState.SUCCEEDED;
		}
		if (throwable instanceof ScriptExecutionException) {
			ScriptExecutionException.Reason reason = ((ScriptExecutionException) throwable).getReason();
			if (reason == ScriptExecutionException.Reason.CANCELLED) {
				return ScriptTaskState.CANCELLED;
			}
			if (reason == ScriptExecutionException.Reason.TIMED_OUT) {
				return ScriptTaskState.TIMED_OUT;
			}
		}
		return ScriptTaskState.FAILED;
	}

	private ScriptExecutionException timeoutException() {
		return new ScriptExecutionException(
				ScriptExecutionException.Reason.TIMED_OUT, "语言级异步任务执行超时");
	}

	private void markCompleted() {
		completedAtMillis = System.currentTimeMillis();
		completedNanos = System.nanoTime();
	}

	private void unregister() {
		if (unregistered.compareAndSet(false, true)) {
			context.unregisterLanguageAsyncTask(this);
		}
	}

	private void emit(Throwable cause) {
		long queueDuration = startedNanos == 0
				? Math.max(0, System.nanoTime() - submittedNanos)
				: Math.max(0, startedNanos - submittedNanos);
		long executionDuration = startedNanos == 0
				? 0
				: Math.max(0, (completedNanos == 0 ? System.nanoTime() : completedNanos) - startedNanos);
		ScriptTaskEvent event = new ScriptTaskEvent(taskId, trace.getParentTaskId(), trace.getDepth(),
				state.get(), context.getScriptName(), 0,
				context.getCorrelationId(), submittedAtMillis, startedAtMillis, completedAtMillis,
				queueDuration, executionDuration, cause == null ? failure : cause);
		for (ScriptTaskListener listener : context.getLanguageAsyncListeners()) {
			try {
				listener.onStateChanged(event);
			} catch (RuntimeException ignored) {
				// Monitoring must not affect language execution.
			}
		}
	}
}
