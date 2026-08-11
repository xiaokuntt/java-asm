package cn.ykccchen.script;

/**
 * Immutable snapshot produced by {@link ScriptExecutionMetrics}.
 */
public final class ScriptExecutionStats {

	private final long startedCount;
	private final long succeededCount;
	private final long failedCount;
	private final long cancelledCount;
	private final long timedOutCount;
	private final long checkpointLimitCount;
	private final long hostCallLimitCount;
	private final long totalDurationNanos;
	private final long maxDurationNanos;

	ScriptExecutionStats(long startedCount, long succeededCount, long failedCount,
			long cancelledCount, long timedOutCount, long checkpointLimitCount,
			long hostCallLimitCount,
			long totalDurationNanos, long maxDurationNanos) {
		this.startedCount = startedCount;
		this.succeededCount = succeededCount;
		this.failedCount = failedCount;
		this.cancelledCount = cancelledCount;
		this.timedOutCount = timedOutCount;
		this.checkpointLimitCount = checkpointLimitCount;
		this.hostCallLimitCount = hostCallLimitCount;
		this.totalDurationNanos = totalDurationNanos;
		this.maxDurationNanos = maxDurationNanos;
	}

	public long getStartedCount() {
		return startedCount;
	}

	public long getCompletedCount() {
		return succeededCount + failedCount + cancelledCount + timedOutCount
				+ checkpointLimitCount + hostCallLimitCount;
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

	public long getCheckpointLimitCount() {
		return checkpointLimitCount;
	}

	public long getHostCallLimitCount() { return hostCallLimitCount; }

	public long getTotalDurationNanos() {
		return totalDurationNanos;
	}

	public long getMaxDurationNanos() {
		return maxDurationNanos;
	}

	public long getAverageDurationNanos() {
		long completed = getCompletedCount();
		return completed == 0 ? 0 : totalDurationNanos / completed;
	}
}
