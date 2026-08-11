package cn.ykccchen.script;

import java.lang.management.ManagementFactory;
import java.util.Objects;
import java.util.function.Supplier;
import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/** Native JMX adapter and reusable aggregate listener. */
public final class ScriptMonitoring implements ScriptMonitoringMBean,
		ScriptExecutionListener, ScriptTaskListener {

	private final ScriptExecutionMetrics executions = new ScriptExecutionMetrics();
	private final ScriptTaskMetrics tasks = new ScriptTaskMetrics();
	private final Supplier<ScriptAsyncRuntimeStats> futureBridgeStats;

	/** Creates a compatibility monitor for the legacy process-wide Future bridge. */
	public ScriptMonitoring() {
		this(LanguageAsyncRuntime::stats);
	}

	/** Creates a monitor whose Future bridge metrics are scoped to one engine. */
	public ScriptMonitoring(JvmScriptEngine engine) {
		this(Objects.requireNonNull(engine, "engine")::getFutureBridgeStats);
	}

	ScriptMonitoring(Supplier<ScriptAsyncRuntimeStats> futureBridgeStats) {
		this.futureBridgeStats = Objects.requireNonNull(futureBridgeStats, "futureBridgeStats");
	}

	@Override public void beforeExecution(ScriptExecutionEvent event) { executions.beforeExecution(event); }
	@Override public void afterExecution(ScriptExecutionEvent event) { executions.afterExecution(event); }
	@Override public void onStateChanged(ScriptTaskEvent event) { tasks.onStateChanged(event); }

	public ScriptExecutionStats executionSnapshot() { return executions.snapshot(); }
	public ScriptTaskStats taskSnapshot() { return tasks.snapshot(); }

	public Registration registerMBean(String objectName) throws JMException {
		ObjectName name = new ObjectName(Objects.requireNonNull(objectName, "objectName"));
		MBeanServer server = ManagementFactory.getPlatformMBeanServer();
		server.registerMBean(this, name);
		return Registration.of(() -> {
			try {
				if (server.isRegistered(name)) server.unregisterMBean(name);
			} catch (JMException exception) {
				throw new IllegalStateException("Unable to unregister script monitoring MBean", exception);
			}
		});
	}

	@Override public long getExecutionStartedCount() { return executions.snapshot().getStartedCount(); }
	@Override public long getExecutionCompletedCount() { return executions.snapshot().getCompletedCount(); }
	@Override public long getExecutionSucceededCount() { return executions.snapshot().getSucceededCount(); }
	@Override public long getExecutionFailedCount() { return executions.snapshot().getFailedCount(); }
	@Override public long getExecutionHostCallLimitCount() { return executions.snapshot().getHostCallLimitCount(); }
	@Override public long getExecutionAverageDurationNanos() { return executions.snapshot().getAverageDurationNanos(); }
	@Override public long getTaskSubmittedCount() { return tasks.snapshot().getSubmittedCount(); }
	@Override public long getTaskCompletedCount() { return tasks.snapshot().getCompletedCount(); }
	@Override public long getTaskActiveCount() { return tasks.snapshot().getActiveCount(); }
	@Override public long getTaskRejectedCount() { return tasks.snapshot().getRejectedCount(); }
	@Override public long getTaskAbandonedCount() { return tasks.snapshot().getAbandonedCount(); }
	@Override public long getTaskAverageQueueDurationNanos() { return tasks.snapshot().getAverageQueueDurationNanos(); }
	@Override public long getTaskAverageExecutionDurationNanos() { return tasks.snapshot().getAverageExecutionDurationNanos(); }
	@Override public int getFutureBridgePoolSize() { return bridgeStats().getBridgePoolSize(); }
	@Override public int getFutureBridgeActiveCount() { return bridgeStats().getBridgeActiveCount(); }
	@Override public int getFutureBridgeQueueSize() { return bridgeStats().getBridgeQueueSize(); }
	@Override public long getFutureBridgeCompletedCount() { return bridgeStats().getBridgeCompletedCount(); }
	@Override public long getFutureBridgeRejectedCount() { return bridgeStats().getBridgeRejectedCount(); }
	@Override public long getAbandonedLanguageTaskCount() { return bridgeStats().getAbandonedTaskCount(); }

	private ScriptAsyncRuntimeStats bridgeStats() {
		return Objects.requireNonNull(futureBridgeStats.get(), "futureBridgeStats returned null");
	}

	@Override
	public void reset() {
		executions.reset();
		tasks.reset();
	}
}
