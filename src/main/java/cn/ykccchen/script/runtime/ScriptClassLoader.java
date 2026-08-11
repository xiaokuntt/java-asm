package cn.ykccchen.script.runtime;

import cn.ykccchen.script.ScriptClassLoadingStats;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class ScriptClassLoader extends ClassLoader{

	private static final LongAdder CREATED = new LongAdder();
	private static final LongAdder DEFINED = new LongAdder();
	private static final LongAdder BYTECODE_BYTES = new LongAdder();
	private static final AtomicLong PEAK_LIVE = new AtomicLong();
	private static final Map<ScriptClassLoader, Boolean> LIVE_LOADERS =
			Collections.synchronizedMap(new WeakHashMap<ScriptClassLoader, Boolean>());

	public ScriptClassLoader(ClassLoader parent) {
		super(parent);
		CREATED.increment();
		LIVE_LOADERS.put(this, Boolean.TRUE);
		updatePeakLive(liveLoaderCount());
	}

	public synchronized Class<ScriptRuntime> load(String className, byte[] bytecode) throws ClassNotFoundException {
		defineClass(className, bytecode, 0 , bytecode.length);
		DEFINED.increment();
		BYTECODE_BYTES.add(bytecode.length);
		return (Class<ScriptRuntime>) loadClass(className);
	}

	public static ScriptClassLoadingStats stats() {
		return ScriptClassLoadingStats.snapshot(CREATED.sum(), DEFINED.sum(), BYTECODE_BYTES.sum(),
				liveLoaderCount(), PEAK_LIVE.get());
	}

	public static void resetStats() {
		CREATED.reset();
		DEFINED.reset();
		BYTECODE_BYTES.reset();
		PEAK_LIVE.set(liveLoaderCount());
	}

	private static int liveLoaderCount() {
		synchronized (LIVE_LOADERS) {
			return LIVE_LOADERS.size();
		}
	}

	private static void updatePeakLive(long candidate) {
		long current;
		do {
			current = PEAK_LIVE.get();
			if (candidate <= current) {
				return;
			}
		} while (!PEAK_LIVE.compareAndSet(current, candidate));
	}
}
