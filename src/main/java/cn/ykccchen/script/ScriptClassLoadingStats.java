package cn.ykccchen.script;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;

/** Immutable class-loading and Metaspace snapshot for generated scripts. */
public final class ScriptClassLoadingStats {

	private final long createdLoaderCount;
	private final long definedClassCount;
	private final long definedBytecodeBytes;
	private final int estimatedLiveLoaderCount;
	private final long peakEstimatedLiveLoaderCount;
	private final long metaspaceUsedBytes;
	private final long metaspaceCommittedBytes;
	private final long metaspaceMaxBytes;

	private ScriptClassLoadingStats(long createdLoaderCount, long definedClassCount,
			long definedBytecodeBytes, int estimatedLiveLoaderCount,
			long peakEstimatedLiveLoaderCount, long metaspaceUsedBytes,
			long metaspaceCommittedBytes, long metaspaceMaxBytes) {
		this.createdLoaderCount = createdLoaderCount;
		this.definedClassCount = definedClassCount;
		this.definedBytecodeBytes = definedBytecodeBytes;
		this.estimatedLiveLoaderCount = estimatedLiveLoaderCount;
		this.peakEstimatedLiveLoaderCount = peakEstimatedLiveLoaderCount;
		this.metaspaceUsedBytes = metaspaceUsedBytes;
		this.metaspaceCommittedBytes = metaspaceCommittedBytes;
		this.metaspaceMaxBytes = metaspaceMaxBytes;
	}

	public static ScriptClassLoadingStats snapshot(long createdLoaderCount, long definedClassCount,
			long definedBytecodeBytes, int estimatedLiveLoaderCount, long peakEstimatedLiveLoaderCount) {
		long used = -1;
		long committed = -1;
		long max = -1;
		for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
			if (pool.getName().toLowerCase().contains("metaspace")) {
				MemoryUsage usage = pool.getUsage();
				if (usage != null) {
					used = usage.getUsed();
					committed = usage.getCommitted();
					max = usage.getMax();
				}
				break;
			}
		}
		return new ScriptClassLoadingStats(createdLoaderCount, definedClassCount,
				definedBytecodeBytes, estimatedLiveLoaderCount, peakEstimatedLiveLoaderCount,
				used, committed, max);
	}

	public long getCreatedLoaderCount() { return createdLoaderCount; }
	public long getDefinedClassCount() { return definedClassCount; }
	public long getDefinedBytecodeBytes() { return definedBytecodeBytes; }
	public int getEstimatedLiveLoaderCount() { return estimatedLiveLoaderCount; }
	public long getPeakEstimatedLiveLoaderCount() { return peakEstimatedLiveLoaderCount; }
	public long getMetaspaceUsedBytes() { return metaspaceUsedBytes; }
	public long getMetaspaceCommittedBytes() { return metaspaceCommittedBytes; }
	public long getMetaspaceMaxBytes() { return metaspaceMaxBytes; }
}
