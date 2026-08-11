package cn.ykccchen.script;

import cn.ykccchen.script.exception.ResourceNotFoundException;
import cn.ykccchen.script.exception.ScriptExecutionException;
import cn.ykccchen.script.exception.ScriptAsyncRejectedException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Bounded asynchronous host API for executing compiled or named scripts.
 *
 * <p>External executors and schedulers remain owned by the caller. Executors
 * created by the builder are shut down by this instance.</p>
 */
public final class ScriptAsyncExecutor implements AutoCloseable {

	private final ExecutorService workerExecutor;
	private final ScheduledExecutorService scheduler;
	private final boolean ownsWorkerExecutor;
	private final boolean ownsScheduler;
	private final long defaultTimeoutMillis;
	private final long closeTimeoutMillis;
	private final Semaphore outstandingPermits;
	private final List<ScriptTaskListener> listeners;
	private final ConcurrentMap<Long, DefaultScriptTask> tasks = new ConcurrentHashMap<>();
	private final AtomicBoolean shutdown = new AtomicBoolean();
	private final Object terminationMonitor = new Object();

	private ScriptAsyncExecutor(Builder builder) {
		this.ownsWorkerExecutor = builder.executorService == null;
		this.workerExecutor = ownsWorkerExecutor ? builder.createWorkerExecutor() : builder.executorService;
		this.ownsScheduler = builder.scheduler == null;
		this.scheduler = ownsScheduler
				? Executors.newSingleThreadScheduledExecutor(builder.createThreadFactory("timeout"))
				: builder.scheduler;
		this.defaultTimeoutMillis = builder.defaultTimeoutMillis;
		this.closeTimeoutMillis = builder.closeTimeoutMillis;
		int maximumOutstanding = builder.maxOutstandingTasks > 0
				? builder.maxOutstandingTasks
				: ownsWorkerExecutor
						? builder.maximumPoolSize + builder.queueCapacity
						: 500;
		this.outstandingPermits = new Semaphore(maximumOutstanding);
		this.listeners = Collections.unmodifiableList(new ArrayList<>(builder.listeners));
	}

	public static Builder builder() {
		return new Builder();
	}

	public ScriptTask submit(Script script, Supplier<ScriptContext> contextFactory) {
		return submit(script, contextFactory, defaultTimeoutMillis, TimeUnit.MILLISECONDS);
	}

	public ScriptTask submit(Script script, Supplier<ScriptContext> contextFactory,
			long timeout, TimeUnit unit) {
		Objects.requireNonNull(script, "script");
		Objects.requireNonNull(contextFactory, "contextFactory");
		return submitTask(new DefaultScriptTask(this, script, null, null, null,
				contextFactory, toTimeoutMillis(timeout, unit)));
	}

	public ScriptTask submit(ScriptRepository repository, String scriptName,
			Supplier<ScriptContext> contextFactory) {
		return submit(repository, scriptName, ScriptVersionPolicy.PINNED,
				contextFactory, defaultTimeoutMillis, TimeUnit.MILLISECONDS);
	}

	public ScriptTask submit(ScriptRepository repository, String scriptName,
			ScriptVersionPolicy versionPolicy, Supplier<ScriptContext> contextFactory) {
		return submit(repository, scriptName, versionPolicy,
				contextFactory, defaultTimeoutMillis, TimeUnit.MILLISECONDS);
	}

	public ScriptTask submit(ScriptRepository repository, String scriptName,
			ScriptVersionPolicy versionPolicy, Supplier<ScriptContext> contextFactory,
			long timeout, TimeUnit unit) {
		Objects.requireNonNull(repository, "repository");
		String requiredName = requireScriptName(scriptName);
		ScriptVersionPolicy requiredPolicy = Objects.requireNonNull(versionPolicy, "versionPolicy");
		Objects.requireNonNull(contextFactory, "contextFactory");
		long timeoutMillis = toTimeoutMillis(timeout, unit);
		if (shutdown.get()) {
			return rejectedTask(requiredName, contextFactory, timeoutMillis,
					new ScriptAsyncRejectedException("异步执行器已关闭"));
		}
		ScriptRevision pinnedRevision = null;
		if (requiredPolicy == ScriptVersionPolicy.PINNED) {
			try {
				pinnedRevision = repository.get(requiredName);
			} catch (ResourceNotFoundException exception) {
				DefaultScriptTask task = new DefaultScriptTask(this, null, repository,
						requiredName, null, contextFactory, timeoutMillis);
				task.failBeforeAdmission(exception);
				return task;
			}
		}
		return submitTask(new DefaultScriptTask(this, null, repository, requiredName,
				pinnedRevision, contextFactory, timeoutMillis));
	}

	public int getOutstandingTaskCount() {
		return tasks.size();
	}

	public boolean isShutdown() {
		return shutdown.get();
	}

	public boolean isTerminated() {
		if (!shutdown.get() || !tasks.isEmpty()) {
			return false;
		}
		return !ownsWorkerExecutor || workerExecutor.isTerminated();
	}

	public void shutdown() {
		if (shutdown.compareAndSet(false, true)) {
			if (ownsWorkerExecutor) {
				workerExecutor.shutdown();
			}
			shutdownOwnedSchedulerWhenIdle();
			signalTerminationChange();
		}
	}

	public List<ScriptTask> shutdownNow() {
		shutdown.set(true);
		List<ScriptTask> active = new ArrayList<>(tasks.values());
		for (ScriptTask task : active) {
			task.cancel();
		}
		if (ownsWorkerExecutor) {
			workerExecutor.shutdownNow();
		}
		if (ownsScheduler) {
			scheduler.shutdownNow();
		}
		signalTerminationChange();
		return Collections.unmodifiableList(active);
	}

	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		TimeUnit requiredUnit = Objects.requireNonNull(unit, "unit");
		long timeoutNanos = requiredUnit.toNanos(timeout);
		if (timeoutNanos < 0) {
			throw new IllegalArgumentException("timeout must not be negative");
		}
		if (ownsWorkerExecutor) {
			return workerExecutor.awaitTermination(timeout, requiredUnit) && tasks.isEmpty();
		}
		long deadline = System.nanoTime() + timeoutNanos;
		synchronized (terminationMonitor) {
			while (!isTerminated()) {
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0) {
					return false;
				}
				TimeUnit.NANOSECONDS.timedWait(terminationMonitor, remaining);
			}
			return true;
		}
	}

	@Override
	public void close() {
		shutdown();
		try {
			if (!awaitTermination(closeTimeoutMillis, TimeUnit.MILLISECONDS)) {
				shutdownNow();
				awaitTermination(closeTimeoutMillis, TimeUnit.MILLISECONDS);
			}
		} catch (InterruptedException exception) {
			shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	private ScriptTask submitTask(DefaultScriptTask task) {
		task.emitCurrentState(null);
		if (shutdown.get()) {
			task.reject(new ScriptAsyncRejectedException("异步执行器已关闭"));
			return task;
		}
		if (!outstandingPermits.tryAcquire()) {
			task.reject(new ScriptAsyncRejectedException("异步任务数量已达到上限"));
			return task;
		}
		task.markAdmitted();
		tasks.put(task.getTaskId(), task);
		try {
			Future<?> future = workerExecutor.submit(task);
			task.setWorkerFuture(future);
		} catch (RejectedExecutionException exception) {
			task.reject(exception instanceof ScriptAsyncRejectedException
					? exception : new ScriptAsyncRejectedException("宿主异步执行器拒绝任务", exception));
		}
		return task;
	}

	private ScriptTask rejectedTask(String scriptName, Supplier<ScriptContext> contextFactory, long timeoutMillis,
			RejectedExecutionException exception) {
		DefaultScriptTask task = new DefaultScriptTask(this, null, null, scriptName,
				null, contextFactory, timeoutMillis);
		task.emitCurrentState(null);
		task.reject(exception);
		return task;
	}

	private void taskTerminated(DefaultScriptTask task) {
		if (tasks.remove(task.getTaskId(), task)) {
			outstandingPermits.release();
		}
		shutdownOwnedSchedulerWhenIdle();
		signalTerminationChange();
	}

	private void shutdownOwnedSchedulerWhenIdle() {
		if (shutdown.get() && tasks.isEmpty() && ownsScheduler) {
			scheduler.shutdown();
		}
	}

	private void signalTerminationChange() {
		synchronized (terminationMonitor) {
			terminationMonitor.notifyAll();
		}
	}

	private void notifyListeners(ScriptTaskEvent event) {
		for (ScriptTaskListener listener : listeners) {
			try {
				listener.onStateChanged(event);
			} catch (RuntimeException ignored) {
				// Monitoring must not affect task execution.
			}
		}
	}

	private static String requireScriptName(String name) {
		String value = Objects.requireNonNull(name, "scriptName").trim();
		if (value.isEmpty()) {
			throw new IllegalArgumentException("scriptName must not be blank");
		}
		return value;
	}

	private static long toTimeoutMillis(long timeout, TimeUnit unit) {
		if (timeout < 0) {
			throw new IllegalArgumentException("timeout must not be negative");
		}
		if (timeout == 0) {
			return 0;
		}
		long millis = Objects.requireNonNull(unit, "unit").toMillis(timeout);
		if (millis <= 0) {
			throw new IllegalArgumentException("timeout must be at least one millisecond");
		}
		return millis;
	}

	private static final class DefaultScriptTask implements ScriptTask, Runnable {

		private final ScriptAsyncExecutor owner;
		private final Script directScript;
		private final ScriptRepository repository;
		private final ScriptRevision pinnedRevision;
		private final Supplier<ScriptContext> contextFactory;
		private final long timeoutMillis;
		private final long taskId = ScriptTaskIds.next();
		private final long submittedAtMillis = System.currentTimeMillis();
		private final long submittedNanos = System.nanoTime();
		private final AtomicReference<ScriptTaskState> state =
				new AtomicReference<>(ScriptTaskState.QUEUED);
		private final CompletableFuture<Object> completion = new CompletableFuture<>();
		private final AtomicBoolean terminated = new AtomicBoolean();
		private volatile String scriptName;
		private volatile long scriptVersion;
		private volatile String correlationId;
		private volatile long startedAtMillis;
		private volatile long completedAtMillis;
		private volatile long startedNanos;
		private volatile long completedNanos;
		private volatile Throwable failure;
		private volatile Future<?> workerFuture;
		private volatile ScheduledFuture<?> timeoutFuture;
		private volatile Thread runnerThread;
		private volatile ScriptContext context;
		private volatile boolean admitted;

		private DefaultScriptTask(ScriptAsyncExecutor owner, Script directScript,
				ScriptRepository repository, String scriptName, ScriptRevision pinnedRevision,
				Supplier<ScriptContext> contextFactory, long timeoutMillis) {
			this.owner = owner;
			this.directScript = directScript;
			this.repository = repository;
			this.scriptName = scriptName;
			this.pinnedRevision = pinnedRevision;
			this.contextFactory = contextFactory;
			this.timeoutMillis = timeoutMillis;
			if (pinnedRevision != null) {
				this.scriptVersion = pinnedRevision.getVersion();
			}
		}

		@Override
		public void run() {
			if (!transition(ScriptTaskState.QUEUED, ScriptTaskState.RUNNING, null)) {
				return;
			}
			runnerThread = Thread.currentThread();
			Object result = null;
			Throwable thrown = null;
			boolean claimed = false;
			try {
				ScriptContext created = Objects.requireNonNull(contextFactory.get(),
						"contextFactory returned null");
				context = created;
				correlationId = created.getCorrelationId();
				if (created.isRunning() || !created.tryClaimAsyncExecution(taskId)) {
					throw new IllegalStateException("ScriptContext 已被其他任务使用");
				}
				claimed = true;
				ScriptRevision revision = pinnedRevision;
				if (repository != null && revision == null) {
					revision = repository.get(scriptName);
					scriptVersion = revision.getVersion();
				}
				if (directScript != null && scriptName == null) {
					scriptName = created.getScriptName();
				}
				scheduleTimeout();
				result = revision == null ? directScript.execute(created) : revision.execute(created);
			} catch (Throwable throwable) {
				thrown = throwable;
			} finally {
				cancelTimeoutFuture();
				if (claimed) {
					context.releaseAsyncExecution(taskId);
				}
				runnerThread = null;
				completeFromWorker(result, thrown);
			}
			if (thrown instanceof VirtualMachineError) {
				throw (VirtualMachineError) thrown;
			}
			if (thrown instanceof ThreadDeath) {
				throw (ThreadDeath) thrown;
			}
		}

		@Override
		public boolean cancel() {
			while (true) {
				ScriptTaskState current = state.get();
				if (current == ScriptTaskState.QUEUED) {
					CancellationException exception = new CancellationException("异步脚本任务已取消");
					if (state.compareAndSet(current, ScriptTaskState.CANCELLED)) {
						failure = exception;
						markCompleted();
						Future<?> future = workerFuture;
						if (future != null) {
							future.cancel(false);
						}
						completion.completeExceptionally(exception);
						emitCurrentState(exception);
						terminate();
						return true;
					}
					continue;
				}
				if (current == ScriptTaskState.RUNNING) {
					if (state.compareAndSet(current, ScriptTaskState.CANCELLING)) {
						emitCurrentState(null);
						signalCancellation(false);
						return true;
					}
					continue;
				}
				return false;
			}
		}

		private void scheduleTimeout() {
			if (timeoutMillis > 0 && state.get() == ScriptTaskState.RUNNING) {
				timeoutFuture = owner.scheduler.schedule(this::timeout,
						timeoutMillis, TimeUnit.MILLISECONDS);
			}
		}

		private void timeout() {
			if (state.compareAndSet(ScriptTaskState.RUNNING, ScriptTaskState.TIMING_OUT)) {
				emitCurrentState(null);
				signalCancellation(true);
			}
		}

		private void signalCancellation(boolean timedOut) {
			ScriptContext currentContext = context;
			if (currentContext != null) {
				if (timedOut) {
					currentContext.timeout();
				} else {
					currentContext.cancel();
				}
			}
			Thread runner = runnerThread;
			if (runner != null) {
				runner.interrupt();
			}
			Future<?> future = workerFuture;
			if (future != null) {
				future.cancel(true);
			}
		}

		private void completeFromWorker(Object result, Throwable thrown) {
			while (true) {
				ScriptTaskState current = state.get();
				ScriptTaskState terminal;
				Throwable completionFailure = thrown;
				if (current == ScriptTaskState.CANCELLING) {
					terminal = ScriptTaskState.CANCELLED;
					completionFailure = new CancellationException("异步脚本任务已取消");
				} else if (current == ScriptTaskState.TIMING_OUT) {
					terminal = ScriptTaskState.TIMED_OUT;
					completionFailure = timeoutException(thrown);
				} else if (current == ScriptTaskState.RUNNING) {
					terminal = classifyWorkerResult(thrown);
					if (terminal == ScriptTaskState.CANCELLED) {
						completionFailure = new CancellationException("异步脚本任务已取消");
					} else if (terminal == ScriptTaskState.TIMED_OUT) {
						completionFailure = timeoutException(thrown);
					}
				} else {
					return;
				}
				if (!state.compareAndSet(current, terminal)) {
					continue;
				}
				failure = completionFailure;
				markCompleted();
				if (terminal == ScriptTaskState.SUCCEEDED) {
					completion.complete(result);
				} else {
					completion.completeExceptionally(completionFailure);
				}
				emitCurrentState(completionFailure);
				terminate();
				return;
			}
		}

		private ScriptTaskState classifyWorkerResult(Throwable thrown) {
			if (thrown == null) {
				return ScriptTaskState.SUCCEEDED;
			}
			if (thrown instanceof ScriptExecutionException) {
				ScriptExecutionException.Reason reason = ((ScriptExecutionException) thrown).getReason();
				if (reason == ScriptExecutionException.Reason.CANCELLED) {
					return ScriptTaskState.CANCELLED;
				}
				if (reason == ScriptExecutionException.Reason.TIMED_OUT) {
					return ScriptTaskState.TIMED_OUT;
				}
			}
			return ScriptTaskState.FAILED;
		}

		private ScriptExecutionException timeoutException(Throwable thrown) {
			if (thrown instanceof ScriptExecutionException
					&& ((ScriptExecutionException) thrown).getReason()
					== ScriptExecutionException.Reason.TIMED_OUT) {
				return (ScriptExecutionException) thrown;
			}
			return new ScriptExecutionException(ScriptExecutionException.Reason.TIMED_OUT,
					"异步脚本任务执行超时");
		}

		private boolean transition(ScriptTaskState expected, ScriptTaskState updated, Throwable cause) {
			if (!state.compareAndSet(expected, updated)) {
				return false;
			}
			if (updated == ScriptTaskState.RUNNING) {
				startedAtMillis = System.currentTimeMillis();
				startedNanos = System.nanoTime();
			}
			emitCurrentState(cause);
			return true;
		}

		private void reject(RejectedExecutionException exception) {
			if (state.compareAndSet(ScriptTaskState.QUEUED, ScriptTaskState.REJECTED)) {
				failure = exception;
				markCompleted();
				completion.completeExceptionally(exception);
				emitCurrentState(exception);
				terminate();
			}
		}

		private void failBeforeAdmission(Throwable throwable) {
			emitCurrentState(null);
			if (state.compareAndSet(ScriptTaskState.QUEUED, ScriptTaskState.FAILED)) {
				failure = throwable;
				markCompleted();
				completion.completeExceptionally(throwable);
				emitCurrentState(throwable);
				terminate();
			}
		}

		private void markAdmitted() {
			admitted = true;
		}

		private void setWorkerFuture(Future<?> workerFuture) {
			this.workerFuture = workerFuture;
			ScriptTaskState current = state.get();
			if (current == ScriptTaskState.CANCELLED) {
				workerFuture.cancel(false);
			} else if (current == ScriptTaskState.CANCELLING
					|| current == ScriptTaskState.TIMING_OUT) {
				workerFuture.cancel(true);
			}
		}

		private void cancelTimeoutFuture() {
			ScheduledFuture<?> future = timeoutFuture;
			if (future != null) {
				future.cancel(false);
			}
		}

		private void markCompleted() {
			completedAtMillis = System.currentTimeMillis();
			completedNanos = System.nanoTime();
		}

		private void terminate() {
			if (terminated.compareAndSet(false, true) && admitted) {
				owner.taskTerminated(this);
			}
		}

		private void emitCurrentState(Throwable cause) {
			long queueDuration = startedNanos == 0
					? Math.max(0, System.nanoTime() - submittedNanos)
					: Math.max(0, startedNanos - submittedNanos);
			long executionDuration = startedNanos == 0
					? 0
					: Math.max(0, (completedNanos == 0 ? System.nanoTime() : completedNanos) - startedNanos);
			owner.notifyListeners(new ScriptTaskEvent(taskId, state.get(), scriptName, scriptVersion,
					correlationId, submittedAtMillis, startedAtMillis, completedAtMillis,
					queueDuration, executionDuration, cause));
		}

		@Override
		public long getTaskId() {
			return taskId;
		}

		@Override
		public ScriptTaskState getState() {
			return state.get();
		}

		@Override
		public String getScriptName() {
			return scriptName;
		}

		@Override
		public long getScriptVersion() {
			return scriptVersion;
		}

		@Override
		public String getCorrelationId() {
			return correlationId;
		}

		@Override
		public long getSubmittedAtMillis() {
			return submittedAtMillis;
		}

		@Override
		public long getStartedAtMillis() {
			return startedAtMillis;
		}

		@Override
		public long getCompletedAtMillis() {
			return completedAtMillis;
		}

		@Override
		public CompletionStage<Object> completion() {
			return completion;
		}
	}

	public static final class Builder {

		private ExecutorService executorService;
		private ScheduledExecutorService scheduler;
		private int corePoolSize = Math.max(1, Runtime.getRuntime().availableProcessors());
		private int maximumPoolSize = corePoolSize;
		private int queueCapacity = 256;
		private int maxOutstandingTasks;
		private long keepAliveMillis = 60_000;
		private long defaultTimeoutMillis;
		private long closeTimeoutMillis = 5_000;
		private String threadNamePrefix = "script-async";
		private boolean daemon;
		private boolean internalPoolConfigured;
		private final List<ScriptTaskListener> listeners = new ArrayList<>();

		private Builder() {
		}

		public Builder executorService(ExecutorService executorService) {
			this.executorService = Objects.requireNonNull(executorService, "executorService");
			return this;
		}

		public Builder scheduler(ScheduledExecutorService scheduler) {
			this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
			return this;
		}

		public Builder corePoolSize(int corePoolSize) {
			if (corePoolSize <= 0) {
				throw new IllegalArgumentException("corePoolSize must be greater than zero");
			}
			this.corePoolSize = corePoolSize;
			this.internalPoolConfigured = true;
			return this;
		}

		public Builder maximumPoolSize(int maximumPoolSize) {
			if (maximumPoolSize <= 0) {
				throw new IllegalArgumentException("maximumPoolSize must be greater than zero");
			}
			this.maximumPoolSize = maximumPoolSize;
			this.internalPoolConfigured = true;
			return this;
		}

		public Builder queueCapacity(int queueCapacity) {
			if (queueCapacity <= 0) {
				throw new IllegalArgumentException("queueCapacity must be greater than zero");
			}
			this.queueCapacity = queueCapacity;
			this.internalPoolConfigured = true;
			return this;
		}

		public Builder maxOutstandingTasks(int maxOutstandingTasks) {
			if (maxOutstandingTasks <= 0) {
				throw new IllegalArgumentException("maxOutstandingTasks must be greater than zero");
			}
			this.maxOutstandingTasks = maxOutstandingTasks;
			return this;
		}

		public Builder keepAlive(long keepAlive, TimeUnit unit) {
			if (keepAlive < 0) {
				throw new IllegalArgumentException("keepAlive must not be negative");
			}
			this.keepAliveMillis = Objects.requireNonNull(unit, "unit").toMillis(keepAlive);
			this.internalPoolConfigured = true;
			return this;
		}

		public Builder defaultTimeout(long timeout, TimeUnit unit) {
			this.defaultTimeoutMillis = toTimeoutMillis(timeout, unit);
			return this;
		}

		public Builder closeTimeout(long timeout, TimeUnit unit) {
			this.closeTimeoutMillis = toTimeoutMillis(timeout, unit);
			return this;
		}

		public Builder threadNamePrefix(String threadNamePrefix) {
			String value = Objects.requireNonNull(threadNamePrefix, "threadNamePrefix").trim();
			if (value.isEmpty()) {
				throw new IllegalArgumentException("threadNamePrefix must not be blank");
			}
			this.threadNamePrefix = value;
			return this;
		}

		public Builder daemonThreads(boolean daemon) {
			this.daemon = daemon;
			return this;
		}

		public Builder addTaskListener(ScriptTaskListener listener) {
			listeners.add(Objects.requireNonNull(listener, "listener"));
			return this;
		}

		public ScriptAsyncExecutor build() {
			if (executorService != null && internalPoolConfigured) {
				throw new IllegalStateException("外部 executorService 不能与内部线程池参数同时配置");
			}
			if (maximumPoolSize < corePoolSize) {
				throw new IllegalStateException("maximumPoolSize must be >= corePoolSize");
			}
			return new ScriptAsyncExecutor(this);
		}

		private ExecutorService createWorkerExecutor() {
			return new ThreadPoolExecutor(corePoolSize, maximumPoolSize,
					keepAliveMillis, TimeUnit.MILLISECONDS,
					new ArrayBlockingQueue<>(queueCapacity),
					createThreadFactory("worker"), new ThreadPoolExecutor.AbortPolicy());
		}

		private ThreadFactory createThreadFactory(String role) {
			return new NamedThreadFactory(threadNamePrefix + "-" + role, daemon);
		}
	}

	private static final class NamedThreadFactory implements ThreadFactory {

		private final String prefix;
		private final boolean daemon;
		private final AtomicInteger sequence = new AtomicInteger();

		private NamedThreadFactory(String prefix, boolean daemon) {
			this.prefix = prefix;
			this.daemon = daemon;
		}

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
			thread.setDaemon(daemon);
			return thread;
		}
	}
}
