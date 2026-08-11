package cn.ykccchen.script.compile;

import cn.ykccchen.script.Script;
import cn.ykccchen.script.category.PerformanceCategory;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Repeatable contention benchmark included in the performance Maven profile. */
@Category(PerformanceCategory.class)
public class CompileCachePerformanceTests {

	@Test(timeout = 30000)
	public void concurrentHotKeyThroughput() throws Exception {
		final int threads = 8;
		final int iterations = 100_000;
		CompileCache cache = new CompileCache(128);
		Script script = Script.create("return 1;", null);
		cache.put("hot", script);
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();
		long started = System.nanoTime();
		try {
			for (int thread = 0; thread < threads; thread++) {
				futures.add(executor.submit(() -> {
					start.await();
					for (int i = 0; i < iterations; i++) {
						Assert.assertSame(script, cache.get("hot"));
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> future : futures) {
				future.get();
			}
		} finally {
			executor.shutdownNow();
		}
		long elapsed = System.nanoTime() - started;
		double operationsPerSecond = (double) threads * iterations * 1_000_000_000D / elapsed;
		Assert.assertEquals((long) threads * iterations, cache.stats().getHitCount());
		System.out.println("compile-cache hot-key ops/s=" + (long) operationsPerSecond
				+ ", skipped-promotions=" + cache.stats().getRecencyContentionCount());
	}
}
