package cn.ykccchen.script.compile;

import cn.ykccchen.script.JvmScriptEngine;
import cn.ykccchen.script.Script;
import cn.ykccchen.script.ScriptEngineConfig;
import cn.ykccchen.script.ScriptEngineFactory;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class CompileCacheLifecycleTests {

	@Test
	public void recordsHitsMissesLoadsAndEvictions() {
		CompileCache cache = new CompileCache(1);
		AtomicInteger loads = new AtomicInteger();
		Script first = Script.create("return 1;", null);
		Script second = Script.create("return 2;", null);

		Assert.assertSame(first, cache.get("one", () -> {
			loads.incrementAndGet();
			return first;
		}));
		Assert.assertSame(first, cache.get("one", () -> second));
		cache.get("two", () -> second);

		CompileCacheStats stats = cache.stats();
		Assert.assertEquals(1, stats.getHitCount());
		Assert.assertEquals(2, stats.getMissCount());
		Assert.assertEquals(2, stats.getLoadSuccessCount());
		Assert.assertEquals(1, stats.getEvictionCount());
		Assert.assertEquals(1, loads.get());
		Assert.assertEquals(1, stats.getSize());
		Assert.assertEquals(1, stats.getCapacity());
	}

	@Test
	public void supportsInvalidateAndClear() {
		CompileCache cache = new CompileCache(4);
		cache.put("alpha", Script.create("return 1;", null));
		cache.put("beta", Script.create("return 2;", null));

		Assert.assertTrue(cache.invalidate("alpha"));
		Assert.assertFalse(cache.invalidate("missing"));
		Assert.assertEquals(1, cache.size());
		cache.clear();
		Assert.assertEquals(0, cache.size());
	}

	@Test
	public void directPutShouldRetainCapacityAndPromoteRecentEntries() {
		CompileCache cache = new CompileCache(2);
		Script first = Script.create("return 1;", null);
		Script second = Script.create("return 2;", null);
		Script third = Script.create("return 3;", null);
		cache.put("first", first);
		cache.put("second", second);
		Assert.assertEquals(2, cache.size());
		Assert.assertSame(first, cache.get("first"));
		cache.put("third", third);
		Assert.assertSame(first, cache.get("first"));
		Assert.assertNull(cache.get("second"));
		Assert.assertSame(third, cache.get("third"));
		Assert.assertEquals(1, cache.stats().getEvictionCount());

		cache.clear();
		cache.put("after-clear", first);
		Assert.assertEquals(1, cache.size());
		Assert.assertSame(first, cache.get("after-clear"));
	}

	@Test
	public void isolatedEngineCanInvalidateBySource() {
		JvmScriptEngine engine = new JvmScriptEngine(new ScriptEngineFactory(),
				ScriptEngineConfig.builder().compileCacheSize(4).build());
		engine.compile("return 1;");
		engine.compile("return 2;");
		Assert.assertEquals(2, engine.getCompileCacheStats().getSize());

		Assert.assertEquals(1, engine.invalidateCompileCache("return 1;"));
		Assert.assertEquals(1, engine.getCompileCacheStats().getSize());
		engine.clearCompileCache();
		Assert.assertEquals(0, engine.getCompileCacheStats().getSize());
	}

	@Test
	public void failedLoadIsCountedAndNotCached() {
		CompileCache cache = new CompileCache(2);
		try {
			cache.get("broken", () -> {
				throw new IllegalStateException("broken");
			});
			Assert.fail("load should fail");
		} catch (IllegalStateException expected) {
			Assert.assertEquals("broken", expected.getMessage());
		}

		Assert.assertEquals(1, cache.stats().getLoadFailureCount());
		Assert.assertEquals(0, cache.size());
	}

	@Test
	public void concurrentMissShouldLoadOnceAndNullKeyShouldRemainSupported() throws Exception {
		CompileCache cache = new CompileCache(4);
		Script value = Script.create("return 42;", null);
		AtomicInteger loads = new AtomicInteger();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(8);
		try {
			List<Future<Script>> futures = new ArrayList<>();
			for (int i = 0; i < 32; i++) {
				futures.add(executor.submit(() -> {
					start.await();
					return cache.get(null, () -> {
						loads.incrementAndGet();
						return value;
					});
				}));
			}
			start.countDown();
			for (Future<Script> future : futures) {
				Assert.assertSame(value, future.get());
			}
			Assert.assertEquals(1, loads.get());
			Assert.assertEquals(1, cache.invalidateMatching(key -> key == null));
			Assert.assertEquals(0, cache.size());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void validatesCapacityAndResetsEveryMetric() {
		try {
			new CompileCache(0);
			Assert.fail("Expected invalid capacity");
		} catch (IllegalArgumentException expected) {
			// expected
		}
		CompileCache cache = new CompileCache(2);
		Assert.assertNull(cache.get("missing"));
		Script value = Script.create("return 123456789;", null);
		cache.put("value", value);
		Assert.assertSame(value, cache.get("value"));
		try {
			cache.put("null", null);
			Assert.fail("Expected null value rejection");
		} catch (NullPointerException expected) {
			// expected
		}
		cache.resetStats();
		CompileCacheStats stats = cache.stats();
		Assert.assertEquals(0, stats.getHitCount());
		Assert.assertEquals(0, stats.getMissCount());
		Assert.assertEquals(0, stats.getEvictionCount());
		Assert.assertEquals(0, stats.getLoadSuccessCount());
		Assert.assertEquals(0, stats.getLoadFailureCount());
		Assert.assertEquals(0, stats.getRecencyContentionCount());
	}

	@Test(timeout = 10000)
	public void clearShouldPreventInFlightLoadFromRepopulatingCache() throws Exception {
		CompileCache cache = new CompileCache(2);
		Script value = Script.create("return 77;", null);
		CountDownLatch loading = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<Script> future = executor.submit(() -> cache.get("value", () -> {
				loading.countDown();
				try {
					release.await();
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
				return value;
			}));
			Assert.assertTrue(loading.await(2, TimeUnit.SECONDS));
			long previousEpoch = cache.stats().getEpoch();
			cache.clear();
			release.countDown();
			Assert.assertSame(value, future.get(2, TimeUnit.SECONDS));
			Assert.assertNull(cache.get("value"));
			Assert.assertTrue(cache.stats().getEpoch() > previousEpoch);
			Assert.assertEquals(1, cache.stats().getStaleLoadDiscardCount());
			cache.resetStats();
			Assert.assertEquals(0, cache.stats().getStaleLoadDiscardCount());
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void cacheMutationsShouldReleaseLocksForOtherThreads() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		Script value = Script.create("return 9;", null);
		try {
			CompileCache afterPut = new CompileCache(2);
			afterPut.put("one", value);
			Assert.assertEquals(2, executor.submit(() -> {
				afterPut.put("two", value);
				return afterPut.size();
			}).get(2, TimeUnit.SECONDS).intValue());

			CompileCache afterInvalidate = new CompileCache(2);
			afterInvalidate.put("one", value);
			afterInvalidate.invalidate("one");
			Assert.assertEquals(1, executor.submit(() -> {
				afterInvalidate.put("two", value);
				return afterInvalidate.size();
			}).get(2, TimeUnit.SECONDS).intValue());

			CompileCache afterMatching = new CompileCache(2);
			afterMatching.put("one", value);
			afterMatching.invalidateMatching("one"::equals);
			Assert.assertEquals(1, executor.submit(() -> {
				afterMatching.put("two", value);
				return afterMatching.size();
			}).get(2, TimeUnit.SECONDS).intValue());

			CompileCache afterLoad = new CompileCache(2);
			Assert.assertSame(value, afterLoad.get("one", () -> value));
			Assert.assertEquals(2, executor.submit(() -> {
				afterLoad.put("two", value);
				return afterLoad.size();
			}).get(2, TimeUnit.SECONDS).intValue());
		} finally {
			executor.shutdownNow();
		}
	}
}
