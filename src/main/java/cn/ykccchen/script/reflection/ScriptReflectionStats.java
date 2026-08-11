package cn.ykccchen.script.reflection;

/** Immutable engine-scoped reflection cache snapshot. */
public final class ScriptReflectionStats {
	private final long hitCount;
	private final long missCount;
	private final long evictionCount;
	private final int functionCacheSize;
	private final int methodCacheSize;
	private final int maximumEntriesPerCache;

	ScriptReflectionStats(long hitCount, long missCount, long evictionCount,
			int functionCacheSize, int methodCacheSize, int maximumEntriesPerCache) {
		this.hitCount = hitCount;
		this.missCount = missCount;
		this.evictionCount = evictionCount;
		this.functionCacheSize = functionCacheSize;
		this.methodCacheSize = methodCacheSize;
		this.maximumEntriesPerCache = maximumEntriesPerCache;
	}

	public long getHitCount() { return hitCount; }
	public long getMissCount() { return missCount; }
	public long getEvictionCount() { return evictionCount; }
	public int getFunctionCacheSize() { return functionCacheSize; }
	public int getMethodCacheSize() { return methodCacheSize; }
	public int getMaximumEntriesPerCache() { return maximumEntriesPerCache; }
	public double getHitRate() {
		long requests = hitCount + missCount;
		return requests == 0 ? 0D : (double) hitCount / requests;
	}
}
