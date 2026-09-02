package cn.ykccchen.script;

import cn.ykccchen.script.runtime.RuntimeContext;
import cn.ykccchen.script.runtime.ScriptRuntime;
import cn.ykccchen.script.runtime.Variables;
import cn.ykccchen.script.exception.ScriptExecutionException;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;


/**
 * 脚本环境上下文
 * 编译后的类每个方法的第一个参数都是本类。
 * 此类主要用于辅助读写变量以及设置/读取/
 */
public class ScriptContext {

	/**
	 * 保存手动设置的环境变量
	 */
	private final Map<String, Object> rootVariables = new LinkedHashMap<>();

	/**
	 * 代码执行时，存放 import "xx.xx.xx.*" 的包
	 */
	private final List<String> importPackages = new ArrayList<>();

	private ScriptRuntime runtime;

	private Variables variables;

	private String scriptName;
	private String correlationId;
	private volatile long asyncTaskId;
	private ScriptEngineConfig engineConfig;
	private ScriptExecutionLimits executionLimits = ScriptExecutionLimits.unlimited();
	private volatile ScriptExecutionState executionState;
	private final AtomicReference<ScriptExecutionException.Reason> terminationRequest = new AtomicReference<>();
	private volatile boolean running;
	private final List<ScriptExecutionListener> executionListeners = new CopyOnWriteArrayList<>();
	private ExecutorService languageAsyncExecutor;
	private ExecutorService languageBlockingExecutor;
	private ExecutorService engineLanguageBlockingExecutor;
	private ScheduledExecutorService languageAsyncScheduler;
	private ScriptAsyncPolicy languageAsyncPolicy;
	private final List<ScriptTaskListener> languageAsyncListeners = new CopyOnWriteArrayList<>();
	private final Set<ScriptAsyncResult> languageAsyncTasks =
			Collections.newSetFromMap(new ConcurrentHashMap<ScriptAsyncResult, Boolean>());
	private final Object languageAsyncMonitor = new Object();
	private final Queue<ScriptAsyncResult> completedLanguageAsyncTasks = new ConcurrentLinkedQueue<>();
	private final AtomicReference<ScriptAsyncResult> failFastLanguageTask = new AtomicReference<>();
	private final AtomicBoolean asyncExecutionClaimed = new AtomicBoolean();
	private final Set<ScriptAsyncResult> abandonedLanguageAsyncTasks =
			Collections.newSetFromMap(new ConcurrentHashMap<ScriptAsyncResult, Boolean>());
	private final AtomicLong hostAccessCount = new AtomicLong();
	private final AtomicLong hostCallCount = new AtomicLong();

	public ScriptContext() {
	}

	public ScriptContext(Map<String, Object> variables) {
		putMapIntoContext(variables);
	}



	public String getScriptName() {
		return scriptName;
	}

	public void setScriptName(String scriptName) {
		this.scriptName = scriptName;
	}

	public String getCorrelationId() {
		return correlationId;
	}

	public ScriptContext setCorrelationId(String correlationId) {
		this.correlationId = correlationId;
		return this;
	}

	public long getAsyncTaskId() {
		return asyncTaskId;
	}

	public ScriptContext setExecutionLimits(ScriptExecutionLimits executionLimits) {
		this.executionLimits = Objects.requireNonNull(executionLimits, "executionLimits");
		return this;
	}

	public ScriptContext enableCancellation() {
		this.executionLimits = ScriptExecutionLimits.cancellable();
		return this;
	}

	public ScriptExecutionLimits getExecutionLimits() {
		return executionLimits;
	}

	public Registration addExecutionListener(ScriptExecutionListener listener) {
		ScriptExecutionListener requiredListener = Objects.requireNonNull(listener, "listener");
		executionListeners.add(requiredListener);
		return Registration.of(() -> executionListeners.remove(requiredListener));
	}

	public List<ScriptExecutionListener> getExecutionListeners() {
		return Collections.unmodifiableList(new ArrayList<>(executionListeners));
	}

	public ScriptContext setLanguageAsyncExecutor(ExecutorService executor) {
		this.languageAsyncExecutor = Objects.requireNonNull(executor, "executor");
		return this;
	}

	public ExecutorService getLanguageAsyncExecutor() {
		if (languageAsyncExecutor != null) {
			return languageAsyncExecutor;
		}
		return engineConfig == null ? null : engineConfig.getLanguageAsyncExecutor();
	}

	public ScriptContext setLanguageBlockingExecutor(ExecutorService executor) {
		this.languageBlockingExecutor = Objects.requireNonNull(executor, "executor");
		return this;
	}

	public ExecutorService getLanguageBlockingExecutor() {
		if (languageBlockingExecutor != null) {
			return languageBlockingExecutor;
		}
		if (engineLanguageBlockingExecutor != null) {
			return engineLanguageBlockingExecutor;
		}
		return engineConfig == null ? null : engineConfig.getLanguageBlockingExecutor();
	}

	ExecutorService getEngineLanguageBlockingExecutor() {
		return engineLanguageBlockingExecutor;
	}

	void setEngineLanguageBlockingExecutor(ExecutorService executor) {
		this.engineLanguageBlockingExecutor = executor;
	}

	public ScriptContext setLanguageAsyncScheduler(ScheduledExecutorService scheduler) {
		this.languageAsyncScheduler = Objects.requireNonNull(scheduler, "scheduler");
		return this;
	}

	public ScheduledExecutorService getLanguageAsyncScheduler() {
		if (languageAsyncScheduler != null) {
			return languageAsyncScheduler;
		}
		return engineConfig == null ? null : engineConfig.getLanguageAsyncScheduler();
	}

	public ScriptContext setLanguageAsyncPolicy(ScriptAsyncPolicy policy) {
		this.languageAsyncPolicy = Objects.requireNonNull(policy, "policy");
		return this;
	}

	public ScriptAsyncPolicy getLanguageAsyncPolicy() {
		if (languageAsyncPolicy != null) {
			return languageAsyncPolicy;
		}
		return engineConfig == null ? ScriptAsyncPolicy.defaults() : engineConfig.getLanguageAsyncPolicy();
	}

	public Registration addLanguageAsyncListener(ScriptTaskListener listener) {
		ScriptTaskListener requiredListener = Objects.requireNonNull(listener, "listener");
		languageAsyncListeners.add(requiredListener);
		return Registration.of(() -> languageAsyncListeners.remove(requiredListener));
	}

	List<ScriptTaskListener> getLanguageAsyncListeners() {
		LinkedHashSet<ScriptTaskListener> listeners = new LinkedHashSet<>();
		if (engineConfig != null) {
			listeners.addAll(engineConfig.getLanguageAsyncListeners());
		}
		listeners.addAll(languageAsyncListeners);
		return new ArrayList<>(listeners);
	}

	public void cancel() {
		terminationRequest.compareAndSet(null, ScriptExecutionException.Reason.CANCELLED);
		ScriptExecutionState state = executionState;
		if (state != null) {
			applyTerminationRequest(state);
		}
		for (ScriptAsyncResult task : languageAsyncTasks) {
			task.cancel(true);
		}
	}

	void timeout() {
		terminationRequest.compareAndSet(null, ScriptExecutionException.Reason.TIMED_OUT);
		ScriptExecutionState state = executionState;
		if (state != null) {
			applyTerminationRequest(state);
		}
		for (ScriptAsyncResult task : languageAsyncTasks) {
			task.timeout();
		}
	}

	public boolean isRunning() {
		return running;
	}

	public boolean isCancellationRequested() {
		ScriptExecutionState state = executionState;
		return terminationRequest.get() != null
				|| state != null && state.isCancellationRequested();
	}

	public void checkpoint() {
		ScriptAsyncResult failedTask = failFastLanguageTask.get();
		if (failedTask != null) {
			throw propagateAsyncFailure(failedTask.getFailure());
		}
		ScriptExecutionState state = executionState;
		if (state != null) {
			state.checkpoint();
		} else if (Thread.currentThread().isInterrupted()) {
			throw new ScriptExecutionException(
					ScriptExecutionException.Reason.CANCELLED, "脚本执行已取消");
		}
	}

	void recordHostAccess() {
		hostAccessCount.incrementAndGet();
	}

	void beforeHostCall(Class<?> owner, String memberName) {
		hostCallCount.incrementAndGet();
		ScriptExecutionState state = executionState;
		if (state != null) {
			state.hostCall(owner, memberName);
		}
	}

	public long getHostAccessCount() { return hostAccessCount.get(); }
	public long getHostCallCount() { return hostCallCount.get(); }

	/**
	 * 获取当前作用域内的String变量值
	 *
	 * @param name 变量名称
	 * @return 变量值
	 */
	public String getString(String name) {
		return Objects.toString(get(name), null);
	}

	/**
	 * 添加 .* 的导包
	 *
	 * @param packageName 包名 如 java.text.
	 */
	public void addImport(String packageName) {
		importPackages.add(packageName);
	}

	public Class<?> getImportClass(String simpleClassName) {
		for (int i = importPackages.size() - 1; i >= 0; i--) {
			try {
				return ResourceLoader.forName(this, importPackages.get(i) + simpleClassName);
			} catch (ClassNotFoundException ignored) {
			}
		}
		return null;
	}

	/**
	 * 获取当前作用域内的变量值
	 *
	 * @param name 变量名称
	 * @return 变量值
	 */
	public Object get(String name) {
		return rootVariables.get(name);
	}

	public boolean contains(String name) {
		return rootVariables.containsKey(name);
	}

	/**
	 * 设置环境变量
	 *
	 * @param name  变量名
	 * @param value 变量值
	 */
	public ScriptContext set(String name, Object value) {
		rootVariables.put(name, value);
		return this;
	}

	public Object remove(String name) {
		return rootVariables.remove(name);
	}

	/**
	 * 创建变量
	 *
	 * @param runtime 脚本实例
	 * @param size    数组大小（变量个数）
	 */
	public Variables createVariables(ScriptRuntime runtime, int size) {
		this.runtime = runtime;
		return this.variables = new Variables(size);
	}

	public Variables getVariables() {
		return variables;
	}

	/**
	 * 从当前上下文中动态执行脚本
	 *
	 * @param runtimeContext
	 * @param script 脚本内容
	 */
	public Object eval(RuntimeContext runtimeContext, String script) {
		Map<String, Object> varMap = new LinkedHashMap<>(runtimeContext.getScriptContext().getRootVariables());
		varMap.putAll(runtimeContext.getVariables().getVariables(runtimeContext.getScriptContext()));
		return eval(script, varMap);
	}

	/**
	 * 从当前上下文中动态执行脚本
	 *
	 * @param script 脚本内容
	 * @param varMap 变量信息
	 */
	public Object eval(String script, Map<String,Object> varMap) {
		Script compiledScript = Script.create(true, script, null);
		ScriptContext context = new ScriptContext(varMap);
		context.setScriptName(this.getScriptName());
		context.engineConfig = this.engineConfig;
		context.executionLimits = this.executionLimits;
		context.executionState = this.executionState;
		context.executionListeners.addAll(this.executionListeners);
		context.languageAsyncExecutor = this.getLanguageAsyncExecutor();
		context.languageBlockingExecutor = this.getLanguageBlockingExecutor();
		context.languageAsyncScheduler = this.getLanguageAsyncScheduler();
		context.languageAsyncPolicy = this.getLanguageAsyncPolicy();
		context.languageAsyncListeners.addAll(this.languageAsyncListeners);
		context.correlationId = this.correlationId;
		context.asyncTaskId = this.asyncTaskId;
		return compiledScript.execute(context);
	}

	public String[] getVarNames(){
		return runtime.getVarNames();
	}

	/**
	 * 获取调用时传入的变量信息
	 */
	public Map<String, Object> getRootVariables() {
		return rootVariables;
	}

	/**
	 * 批量设置环境变量
	 */
	public void putMapIntoContext(Map<String, Object> map) {
		if (map != null && !map.isEmpty()) {
			rootVariables.putAll(map);
		}
	}

	/**
	 * 从环境中获取值，此方法给编译后的类专用。
	 *
	 * @param name 变量名
	 */
	public Object getEnvironmentValue(String name) {
		if (contains(name)) {
			return get(name);
		}
		Object value = getImportClass(name);
		return value == null ? ResourceLoader.findClass(this, name) : value;
	}

	ScriptEngineConfig getEngineConfig() {
		return engineConfig;
	}

	public cn.ykccchen.script.reflection.ScriptReflectionRegistry getReflectionRegistry() {
		return engineConfig == null ? null : engineConfig.getReflectionRegistry();
	}

	void setEngineConfig(ScriptEngineConfig engineConfig) {
		this.engineConfig = engineConfig;
	}

	ScriptExecutionState getExecutionState() {
		return executionState;
	}

	void setExecutionState(ScriptExecutionState executionState) {
		this.executionState = executionState;
		if (executionState == null) {
			terminationRequest.set(null);
			return;
		}
		applyTerminationRequest(executionState);
	}

	private void applyTerminationRequest(ScriptExecutionState executionState) {
		ScriptExecutionException.Reason reason = terminationRequest.get();
		if (reason == ScriptExecutionException.Reason.TIMED_OUT) {
			executionState.timeout();
		} else if (reason == ScriptExecutionException.Reason.CANCELLED) {
			executionState.cancel();
		}
	}

	boolean hasTerminationRequest() {
		return terminationRequest.get() != null;
	}

	void beginExecution() {
		if (!abandonedLanguageAsyncTasks.isEmpty()) {
			throw new IllegalStateException("ScriptContext 仍有关联的孤儿异步任务，暂不可复用");
		}
		completedLanguageAsyncTasks.clear();
		failFastLanguageTask.set(null);
		hostAccessCount.set(0);
		hostCallCount.set(0);
		this.running = true;
	}

	void markLanguageAsyncTaskAbandoned(ScriptAsyncResult task) {
		if (!task.hasWorkerExited()) abandonedLanguageAsyncTasks.add(task);
	}

	void languageAsyncWorkerExited(ScriptAsyncResult task) {
		abandonedLanguageAsyncTasks.remove(task);
	}

	public int getOrphanedLanguageTaskCount() { return abandonedLanguageAsyncTasks.size(); }

	void endExecution() {
		this.running = false;
	}

	boolean tryClaimAsyncExecution(long taskId) {
		if (!asyncExecutionClaimed.compareAndSet(false, true)) {
			return false;
		}
		this.asyncTaskId = taskId;
		return true;
	}

	void releaseAsyncExecution(long taskId) {
		if (this.asyncTaskId == taskId) {
			this.asyncTaskId = 0;
			asyncExecutionClaimed.set(false);
		}
	}

	boolean registerLanguageAsyncTask(ScriptAsyncResult task) {
		synchronized (languageAsyncMonitor) {
			int maximum = getLanguageAsyncPolicy().getMaxChildTasks();
			if (maximum > 0 && languageAsyncTasks.size() >= maximum) {
				return false;
			}
			return languageAsyncTasks.add(task);
		}
	}

	void unregisterLanguageAsyncTask(ScriptAsyncResult task) {
		if (languageAsyncTasks.remove(task)) {
			completedLanguageAsyncTasks.add(task);
			if (getLanguageAsyncPolicy().getFailurePolicy() == ScriptAsyncFailurePolicy.FAIL_FAST
					&& isFailedTask(task) && failFastLanguageTask.compareAndSet(null, task)) {
				for (ScriptAsyncResult sibling : languageAsyncTasks) {
					sibling.cancel(true);
				}
			}
			synchronized (languageAsyncMonitor) {
				languageAsyncMonitor.notifyAll();
			}
		}
	}

	void awaitLanguageAsyncTasks() {
		ScriptAsyncPolicy policy = getLanguageAsyncPolicy();
		long timeoutMillis = policy.getScopeJoinTimeoutMillis();
		long deadline = timeoutMillis == 0 ? 0 : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		try {
			synchronized (languageAsyncMonitor) {
				while (!languageAsyncTasks.isEmpty()) {
					try {
						if (deadline == 0) {
							languageAsyncMonitor.wait();
						} else {
							long remaining = deadline - System.nanoTime();
							if (remaining <= 0) {
								timeoutLanguageAsyncTasks();
								throw new ScriptExecutionException(
										ScriptExecutionException.Reason.TIMED_OUT,
										"等待语言级异步子任务超时");
							}
							TimeUnit.NANOSECONDS.timedWait(languageAsyncMonitor, remaining);
						}
					} catch (InterruptedException exception) {
						cancel();
						Thread.currentThread().interrupt();
						throw new ScriptExecutionException(
								ScriptExecutionException.Reason.CANCELLED, "等待异步子任务时执行被取消");
					}
				}
			}
			ScriptAsyncResult failed = firstRelevantAsyncFailure(policy.getFailurePolicy());
			if (failed != null) {
				throw propagateAsyncFailure(failed.getFailure());
			}
		} finally {
			completedLanguageAsyncTasks.clear();
			failFastLanguageTask.set(null);
		}
	}

	void cancelAndAwaitLanguageAsyncTasks() {
		for (ScriptAsyncResult task : languageAsyncTasks) {
			task.cancel(true);
		}
		// Cleanup must remain bounded even when the caller arrives with its interrupt
		// flag set (the normal host timeout path). Preserve the signal for the caller,
		// but allow responsive children to publish their actual terminal state first.
		boolean interrupted = Thread.interrupted();
		long timeoutMillis = getLanguageAsyncPolicy().getCancellationJoinTimeoutMillis();
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		synchronized (languageAsyncMonitor) {
			while (!languageAsyncTasks.isEmpty()) {
				try {
					long remaining = deadline - System.nanoTime();
					if (remaining <= 0) {
						break;
					}
					TimeUnit.NANOSECONDS.timedWait(languageAsyncMonitor, remaining);
				} catch (InterruptedException exception) {
					interrupted = true;
				}
			}
		}
		for (ScriptAsyncResult task : new ArrayList<>(languageAsyncTasks)) {
			task.abandon();
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
		completedLanguageAsyncTasks.clear();
		failFastLanguageTask.set(null);
	}

	private void timeoutLanguageAsyncTasks() {
		for (ScriptAsyncResult task : languageAsyncTasks) {
			task.timeout();
		}
	}

	private ScriptAsyncResult firstRelevantAsyncFailure(ScriptAsyncFailurePolicy policy) {
		if (policy == ScriptAsyncFailurePolicy.IGNORE) {
			return null;
		}
		for (ScriptAsyncResult task : completedLanguageAsyncTasks) {
			if (isFailedTask(task)
					&& (policy == ScriptAsyncFailurePolicy.FAIL_FAST || !task.isFailureObserved())) {
				return task;
			}
		}
		return null;
	}

	private static boolean isFailedTask(ScriptAsyncResult task) {
		ScriptTaskState state = task.getState();
		return state == ScriptTaskState.FAILED || state == ScriptTaskState.TIMED_OUT
				|| state == ScriptTaskState.ABANDONED || state == ScriptTaskState.REJECTED;
	}

	private static RuntimeException propagateAsyncFailure(Throwable failure) {
		if (failure instanceof RuntimeException) {
			return (RuntimeException) failure;
		}
		if (failure instanceof Error) {
			throw (Error) failure;
		}
		return new cn.ykccchen.script.exception.ScriptRuntimeException("语言级异步子任务失败", failure);
	}

	public void pause(int startRow, int startCol, int endRow, int endCol, Variables variables) throws InterruptedException {

	}
}
