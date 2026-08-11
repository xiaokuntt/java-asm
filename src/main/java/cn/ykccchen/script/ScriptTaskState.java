package cn.ykccchen.script;

/**
 * Lifecycle state of one asynchronous script task.
 */
public enum ScriptTaskState {
	QUEUED,
	RUNNING,
	CANCELLING,
	TIMING_OUT,
	SUCCEEDED,
	FAILED,
	CANCELLED,
	TIMED_OUT,
	ABANDONED,
	REJECTED;

	public boolean isTerminal() {
		return this == SUCCEEDED || this == FAILED || this == CANCELLED
				|| this == TIMED_OUT || this == ABANDONED || this == REJECTED;
	}
}
