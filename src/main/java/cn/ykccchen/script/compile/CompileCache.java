package cn.ykccchen.script.compile;

import cn.ykccchen.script.Script;

import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class CompileCache {

	private final ConcurrentMap<Object, Script> cacheMap;
	private final LinkedHashMap<Object, Boolean> recency;
	private final int capacity;

	private final ReentrantLock lock = new ReentrantLock();
	private final ConcurrentMap<Object, CompletableFuture<Script>> inFlight = new ConcurrentHashMap<>();
	private final LongAdder hits = new LongAdder();
	private final LongAdder misses = new LongAdder();
	private final LongAdder evictions = new LongAdder();
	private final LongAdder loadSuccesses = new LongAdder();
	private final LongAdder loadFailures = new LongAdder();
	private final LongAdder recencyContentions = new LongAdder();
	private final LongAdder staleLoadDiscards = new LongAdder();
	private final AtomicLong epoch = new AtomicLong();
	private static final Object NULL_KEY = new Object();

	public CompileCache(int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity must be greater than zero");
		}
		this.capacity = capacity;
		this.cacheMap = new ConcurrentHashMap<>((int) Math.ceil(capacity / 0.75) + 1);
		this.recency = new LinkedHashMap<>((int) Math.ceil(capacity / 0.75) + 1, 0.75f, true);

	}

	public void put(Object key, Script script) {
		Object normalizedKey = normalizeKey(key);
		lock.lock();
		try {
			cacheMap.put(normalizedKey, java.util.Objects.requireNonNull(script, "script"));
			recency.put(normalizedKey, Boolean.TRUE);
			while (recency.size() > capacity) {
				Object eldest = recency.keySet().iterator().next();
				recency.remove(eldest);
				if (cacheMap.remove(eldest) != null) {
					evictions.increment();
				}
			}
		} finally {
			lock.unlock();
		}
	}

	public Script get(Object key) {
		Script script = lookup(key);
		if (script == null) {
			misses.increment();
		} else {
			hits.increment();
		}
		return script;
	}

	private Script lookup(Object key) {
		Object normalizedKey = normalizeKey(key);
		Script script = cacheMap.get(normalizedKey);
		if (script != null) {
			// LRU recency is advisory: a contended hit remains lock-free and may skip
			// promotion. Capacity and value visibility remain exact.
			if (lock.tryLock()) {
				try {
					if (cacheMap.containsKey(normalizedKey)) {
						recency.get(normalizedKey);
					}
				} finally {
					lock.unlock();
				}
			} else {
				recencyContentions.increment();
			}
		}
		return script;
	}

	public Script get(Object key, Supplier<Script> value) {
		Script cached = lookup(key);
		if (cached != null) {
			hits.increment();
			return cached;
		}
		misses.increment();

		long loadEpoch = epoch.get();
		Object normalizedKey = normalizeKey(key);
		LoadKey inFlightKey = new LoadKey(normalizedKey, loadEpoch);
		CompletableFuture<Script> created = new CompletableFuture<>();
		CompletableFuture<Script> pending = inFlight.putIfAbsent(inFlightKey, created);
		if (pending != null) {
			return await(pending);
		}

		try {
			// A previous loader may have populated the LRU between the first lookup
			// and this key becoming the in-flight owner.
			Script script = lookup(key);
			if (script == null) {
				script = value.get();
				if (script != null) {
					if (!putIfEpoch(normalizedKey, script, loadEpoch)) {
						staleLoadDiscards.increment();
					}
				}
				loadSuccesses.increment();
			}
			created.complete(script);
			return script;
		} catch (Throwable throwable) {
			loadFailures.increment();
			created.completeExceptionally(throwable);
			throw propagate(throwable);
		} finally {
			inFlight.remove(inFlightKey, created);
		}
	}

	public boolean invalidate(Object key) {
		Object normalizedKey = normalizeKey(key);
		lock.lock();
		try {
			epoch.incrementAndGet();
			recency.remove(normalizedKey);
			return cacheMap.remove(normalizedKey) != null;
		} finally {
			lock.unlock();
		}
	}

	public int invalidateMatching(Predicate<Object> predicate) {
		lock.lock();
		try {
			epoch.incrementAndGet();
			int previousSize = cacheMap.size();
			java.util.Iterator<Object> iterator = recency.keySet().iterator();
			while (iterator.hasNext()) {
				Object key = iterator.next();
				if (predicate.test(denormalizeKey(key))) {
					iterator.remove();
					cacheMap.remove(key);
				}
			}
			return previousSize - cacheMap.size();
		} finally {
			lock.unlock();
		}
	}

	public void clear() {
		lock.lock();
		try {
			epoch.incrementAndGet();
			cacheMap.clear();
			recency.clear();
		} finally {
			lock.unlock();
		}
	}

	public int size() {
		return cacheMap.size();
	}

	public CompileCacheStats stats() {
		return new CompileCacheStats(hits.sum(), misses.sum(), evictions.sum(),
				loadSuccesses.sum(), loadFailures.sum(), recencyContentions.sum(),
				staleLoadDiscards.sum(), epoch.get(), size(), capacity);
	}

	public void resetStats() {
		hits.reset();
		misses.reset();
		evictions.reset();
		loadSuccesses.reset();
		loadFailures.reset();
		recencyContentions.reset();
		staleLoadDiscards.reset();
	}

	private boolean putIfEpoch(Object normalizedKey, Script script, long expectedEpoch) {
		lock.lock();
		try {
			if (epoch.get() != expectedEpoch) {
				return false;
			}
			cacheMap.put(normalizedKey, java.util.Objects.requireNonNull(script, "script"));
			recency.put(normalizedKey, Boolean.TRUE);
			while (recency.size() > capacity) {
				Object eldest = recency.keySet().iterator().next();
				recency.remove(eldest);
				if (cacheMap.remove(eldest) != null) evictions.increment();
			}
			return true;
		} finally {
			lock.unlock();
		}
	}

	private static Object normalizeKey(Object key) {
		return key == null ? NULL_KEY : key;
	}

	private static Object denormalizeKey(Object key) {
		return key == NULL_KEY ? null : key;
	}

	private static Script await(CompletableFuture<Script> pending) {
		try {
			return pending.join();
		} catch (CompletionException exception) {
			throw propagate(exception.getCause());
		}
	}

	private static RuntimeException propagate(Throwable throwable) {
		if (throwable instanceof RuntimeException) {
			return (RuntimeException) throwable;
		}
		if (throwable instanceof Error) {
			throw (Error) throwable;
		}
		return new IllegalStateException(throwable);
	}

	private static final class LoadKey {
		private final Object key;
		private final long epoch;
		private final int hashCode;

		private LoadKey(Object key, long epoch) {
			this.key = key;
			this.epoch = epoch;
			this.hashCode = 31 * key.hashCode() + Long.hashCode(epoch);
		}

		@Override public int hashCode() { return hashCode; }
		@Override public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof LoadKey)) return false;
			LoadKey other = (LoadKey) object;
			return epoch == other.epoch && key.equals(other.key);
		}
	}

}
