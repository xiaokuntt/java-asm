package cn.ykccchen.script;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Immutable lifecycle event for one script execution.
 */
public final class ScriptExecutionEvent {

	public enum Status {
		RUNNING,
		SUCCEEDED,
		FAILED,
		CANCELLED,
		TIMED_OUT,
		CHECKPOINT_LIMIT,
		HOST_CALL_LIMIT
	}

	private final long executionId;
	private final long taskId;
	private final String scriptName;
	private final String correlationId;
	private final long startedAtMillis;
	private final long durationNanos;
	private final Status status;
	private final Throwable failure;
	private final long hostAccessCount;
	private final long hostCallCount;

	private ScriptExecutionEvent(long executionId, long taskId, String scriptName, String correlationId, long startedAtMillis,
			long durationNanos, Status status, Throwable failure) {
		this(executionId, taskId, scriptName, correlationId, startedAtMillis,
				durationNanos, status, failure, 0, 0);
	}

	private ScriptExecutionEvent(long executionId, long taskId, String scriptName,
			String correlationId, long startedAtMillis, long durationNanos, Status status,
			Throwable failure, long hostAccessCount, long hostCallCount) {
		this.executionId = executionId;
		this.taskId = taskId;
		this.scriptName = scriptName;
		this.correlationId = correlationId;
		this.startedAtMillis = startedAtMillis;
		this.durationNanos = durationNanos;
		this.status = Objects.requireNonNull(status, "status");
		this.failure = failure;
		this.hostAccessCount = hostAccessCount;
		this.hostCallCount = hostCallCount;
	}

	static ScriptExecutionEvent started(long executionId, String scriptName, long startedAtMillis) {
		return started(executionId, 0, scriptName, null, startedAtMillis);
	}

	static ScriptExecutionEvent started(long executionId, long taskId, String scriptName,
			String correlationId, long startedAtMillis) {
		return new ScriptExecutionEvent(executionId, taskId, scriptName, correlationId, startedAtMillis,
				0, Status.RUNNING, null);
	}

	static ScriptExecutionEvent completed(ScriptExecutionEvent started, long durationNanos,
			Status status, Throwable failure) {
		return completed(started, durationNanos, status, failure, 0, 0);
	}

	static ScriptExecutionEvent completed(ScriptExecutionEvent started, long durationNanos,
			Status status, Throwable failure, long hostAccessCount, long hostCallCount) {
		return new ScriptExecutionEvent(started.executionId, started.taskId, started.scriptName,
				started.correlationId, started.startedAtMillis,
				durationNanos, status, failure, hostAccessCount, hostCallCount);
	}

	public long getExecutionId() {
		return executionId;
	}

	public long getTaskId() {
		return taskId;
	}

	public String getScriptName() {
		return scriptName;
	}

	public String getCorrelationId() {
		return correlationId;
	}

	public long getStartedAtMillis() {
		return startedAtMillis;
	}

	public long getDurationNanos() {
		return durationNanos;
	}

	public long getDuration(TimeUnit unit) {
		return Objects.requireNonNull(unit, "unit").convert(durationNanos, TimeUnit.NANOSECONDS);
	}

	public Status getStatus() {
		return status;
	}

	public Throwable getFailure() {
		return failure;
	}

	public long getHostAccessCount() { return hostAccessCount; }
	public long getHostCallCount() { return hostCallCount; }

	public boolean isCompleted() {
		return status != Status.RUNNING;
	}

	public boolean isSuccessful() {
		return status == Status.SUCCEEDED;
	}
}
