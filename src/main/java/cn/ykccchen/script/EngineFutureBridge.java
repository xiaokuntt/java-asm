package cn.ykccchen.script;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Engine-owned adapter pool for blocking {@link java.util.concurrent.Future} values. */
final class EngineFutureBridge implements AutoCloseable {

	private final LongAdder rejections = new LongAdder();
	private final ThreadPoolExecutor executor;

	EngineFutureBridge(int threads, int queueCapacity, String threadNamePrefix) {
		this.executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
				new ArrayBlockingQueue<Runnable>(queueCapacity),
				new BridgeThreadFactory(threadNamePrefix), (runnable, rejectedExecutor) -> {
					rejections.increment();
					throw new RejectedExecutionException("Engine Future bridge capacity exhausted");
				});
		this.executor.allowCoreThreadTimeOut(true);
	}

	ExecutorService executor() {
		return executor;
	}

	ScriptAsyncRuntimeStats stats() {
		return snapshot(executor, rejections.sum());
	}

	boolean isShutdown() {
		return executor.isShutdown();
	}

	@Override
	public void close() {
		executor.shutdownNow();
	}

	static ScriptAsyncRuntimeStats snapshot(ExecutorService executorService, long rejectedCount) {
		if (executorService instanceof ThreadPoolExecutor) {
			ThreadPoolExecutor pool = (ThreadPoolExecutor) executorService;
			return new ScriptAsyncRuntimeStats(pool.getPoolSize(), pool.getActiveCount(),
					pool.getQueue().size(), pool.getCompletedTaskCount(), rejectedCount,
					LanguageAsyncRuntime.stats().getAbandonedTaskCount());
		}
		return new ScriptAsyncRuntimeStats(-1, -1, -1, -1, rejectedCount,
				LanguageAsyncRuntime.stats().getAbandonedTaskCount());
	}

	private static final class BridgeThreadFactory implements ThreadFactory {
		private final String prefix;
		private final AtomicLong sequence = new AtomicLong();

		private BridgeThreadFactory(String prefix) {
			this.prefix = prefix;
		}

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		}
	}
}
