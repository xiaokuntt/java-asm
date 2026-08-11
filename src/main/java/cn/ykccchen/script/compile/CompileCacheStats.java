package cn.ykccchen.script.compile;

/**
 * Immutable snapshot of compile-cache activity.
 */
public final class CompileCacheStats {

	private final long hitCount;
	private final long missCount;
	private final long evictionCount;
	private final long loadSuccessCount;
	private final long loadFailureCount;
	private final long recencyContentionCount;
	private final long staleLoadDiscardCount;
	private final long epoch;
	private final int size;
	private final int capacity;

	CompileCacheStats(long hitCount, long missCount, long evictionCount,
			long loadSuccessCount, long loadFailureCount, long recencyContentionCount,
			long staleLoadDiscardCount, long epoch,
			int size, int capacity) {
		this.hitCount = hitCount;
		this.missCount = missCount;
		this.evictionCount = evictionCount;
		this.loadSuccessCount = loadSuccessCount;
		this.loadFailureCount = loadFailureCount;
		this.recencyContentionCount = recencyContentionCount;
		this.staleLoadDiscardCount = staleLoadDiscardCount;
		this.epoch = epoch;
		this.size = size;
		this.capacity = capacity;
	}

	public long getHitCount() {
		return hitCount;
	}

	public long getMissCount() {
		return missCount;
	}

	public long getEvictionCount() {
		return evictionCount;
	}

	public long getLoadSuccessCount() {
		return loadSuccessCount;
	}

	public long getLoadFailureCount() {
		return loadFailureCount;
	}

	/** Hits served without LRU promotion because another thread held the recency lock. */
	public long getRecencyContentionCount() {
		return recencyContentionCount;
	}

	public long getStaleLoadDiscardCount() { return staleLoadDiscardCount; }

	public long getEpoch() { return epoch; }

	public int getSize() {
		return size;
	}

	public int getCapacity() {
		return capacity;
	}

	public double getHitRate() {
		long requests = hitCount + missCount;
		return requests == 0 ? 0D : (double) hitCount / requests;
	}
}
