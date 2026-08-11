package cn.ykccchen.script;

import java.util.concurrent.TimeUnit;

/**
 * Immutable snapshot emitted whenever an asynchronous task changes state.
 */
public final class ScriptTaskEvent {

	private final long taskId;
	private final long parentTaskId;
	private final int asyncDepth;
	private final ScriptTaskState state;
	private final String scriptName;
	private final long scriptVersion;
	private final String correlationId;
	private final long submittedAtMillis;
	private final long startedAtMillis;
	private final long completedAtMillis;
	private final long queueDurationNanos;
	private final long executionDurationNanos;
	private final Throwable failure;

	ScriptTaskEvent(long taskId, ScriptTaskState state, String scriptName, long scriptVersion,
			String correlationId, long submittedAtMillis, long startedAtMillis,
			long completedAtMillis, long queueDurationNanos, long executionDurationNanos,
			Throwable failure) {
		this(taskId, 0, 0, state, scriptName, scriptVersion, correlationId, submittedAtMillis,
				startedAtMillis, completedAtMillis, queueDurationNanos, executionDurationNanos, failure);
	}

	ScriptTaskEvent(long taskId, long parentTaskId, int asyncDepth, ScriptTaskState state,
			String scriptName, long scriptVersion, String correlationId, long submittedAtMillis,
			long startedAtMillis, long completedAtMillis, long queueDurationNanos,
			long executionDurationNanos, Throwable failure) {
		this.taskId = taskId;
		this.parentTaskId = parentTaskId;
		this.asyncDepth = asyncDepth;
		this.state = state;
		this.scriptName = scriptName;
		this.scriptVersion = scriptVersion;
		this.correlationId = correlationId;
		this.submittedAtMillis = submittedAtMillis;
		this.startedAtMillis = startedAtMillis;
		this.completedAtMillis = completedAtMillis;
		this.queueDurationNanos = queueDurationNanos;
		this.executionDurationNanos = executionDurationNanos;
		this.failure = failure;
	}

	public long getTaskId() {
		return taskId;
	}

	public long getParentTaskId() { return parentTaskId; }

	public int getAsyncDepth() { return asyncDepth; }

	public ScriptTaskState getState() {
		return state;
	}

	public String getScriptName() {
		return scriptName;
	}

	public long getScriptVersion() {
		return scriptVersion;
	}

	public String getCorrelationId() {
		return correlationId;
	}

	public long getSubmittedAtMillis() {
		return submittedAtMillis;
	}

	public long getStartedAtMillis() {
		return startedAtMillis;
	}

	public long getCompletedAtMillis() {
		return completedAtMillis;
	}

	public long getQueueDurationNanos() {
		return queueDurationNanos;
	}

	public long getQueueDuration(TimeUnit unit) {
		return unit.convert(queueDurationNanos, TimeUnit.NANOSECONDS);
	}

	public long getExecutionDurationNanos() {
		return executionDurationNanos;
	}

	public long getExecutionDuration(TimeUnit unit) {
		return unit.convert(executionDurationNanos, TimeUnit.NANOSECONDS);
	}

	public Throwable getFailure() {
		return failure;
	}
}
