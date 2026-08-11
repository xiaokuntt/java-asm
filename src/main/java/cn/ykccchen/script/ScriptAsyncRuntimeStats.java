package cn.ykccchen.script;

/** Snapshot of shared language-async infrastructure. */
public final class ScriptAsyncRuntimeStats {
	private final int bridgePoolSize;
	private final int bridgeActiveCount;
	private final int bridgeQueueSize;
	private final long bridgeCompletedCount;
	private final long bridgeRejectedCount;
	private final long abandonedTaskCount;

	ScriptAsyncRuntimeStats(int bridgePoolSize, int bridgeActiveCount, int bridgeQueueSize,
			long bridgeCompletedCount, long bridgeRejectedCount, long abandonedTaskCount) {
		this.bridgePoolSize = bridgePoolSize;
		this.bridgeActiveCount = bridgeActiveCount;
		this.bridgeQueueSize = bridgeQueueSize;
		this.bridgeCompletedCount = bridgeCompletedCount;
		this.bridgeRejectedCount = bridgeRejectedCount;
		this.abandonedTaskCount = abandonedTaskCount;
	}

	public int getBridgePoolSize() { return bridgePoolSize; }
	public int getBridgeActiveCount() { return bridgeActiveCount; }
	public int getBridgeQueueSize() { return bridgeQueueSize; }
	public long getBridgeCompletedCount() { return bridgeCompletedCount; }
	public long getBridgeRejectedCount() { return bridgeRejectedCount; }
	public long getAbandonedTaskCount() { return abandonedTaskCount; }
}
