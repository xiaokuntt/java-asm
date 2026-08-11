package cn.ykccchen.script;

/**
 * Immutable aggregate snapshot produced by {@link ScriptTaskMetrics}.
 */
public final class ScriptTaskStats {

	private final long submittedCount;
	private final long startedCount;
	private final long succeededCount;
	private final long failedCount;
	private final long cancelledCount;
	private final long timedOutCount;
	private final long rejectedCount;
	private final long abandonedCount;
	private final long activeCount;
	private final long completedStartedCount;
	private final long totalQueueDurationNanos;
	private final long totalExecutionDurationNanos;

	ScriptTaskStats(long submittedCount, long startedCount, long succeededCount,
			long failedCount, long cancelledCount, long timedOutCount, long rejectedCount,
			long abandonedCount,
			long activeCount, long completedStartedCount, long totalQueueDurationNanos,
			long totalExecutionDurationNanos) {
		this.submittedCount = submittedCount;
		this.startedCount = startedCount;
		this.succeededCount = succeededCount;
		this.failedCount = failedCount;
		this.cancelledCount = cancelledCount;
		this.timedOutCount = timedOutCount;
		this.rejectedCount = rejectedCount;
		this.abandonedCount = abandonedCount;
		this.activeCount = activeCount;
		this.completedStartedCount = completedStartedCount;
		this.totalQueueDurationNanos = totalQueueDurationNanos;
		this.totalExecutionDurationNanos = totalExecutionDurationNanos;
	}

	public long getSubmittedCount() {
		return submittedCount;
	}

	public long getStartedCount() {
		return startedCount;
	}

	public long getCompletedCount() {
		return succeededCount + failedCount + cancelledCount + timedOutCount + rejectedCount + abandonedCount;
	}

	public long getSucceededCount() {
		return succeededCount;
	}

	public long getFailedCount() {
		return failedCount;
	}

	public long getCancelledCount() {
		return cancelledCount;
	}

	public long getTimedOutCount() {
		return timedOutCount;
	}

	public long getRejectedCount() {
		return rejectedCount;
	}

	public long getAbandonedCount() { return abandonedCount; }

	public long getActiveCount() {
		return activeCount;
	}

	/**
	 * Number of tasks that entered RUNNING and subsequently reached a terminal state.
	 * Rejected and queued-cancelled tasks are deliberately excluded.
	 */
	public long getCompletedStartedCount() {
		return completedStartedCount;
	}

	public long getTotalQueueDurationNanos() {
		return totalQueueDurationNanos;
	}

	public long getAverageQueueDurationNanos() {
		return startedCount == 0 ? 0 : totalQueueDurationNanos / startedCount;
	}

	public long getTotalExecutionDurationNanos() {
		return totalExecutionDurationNanos;
	}

	public long getAverageExecutionDurationNanos() {
		return completedStartedCount == 0 ? 0 : totalExecutionDurationNanos / completedStartedCount;
	}
}
