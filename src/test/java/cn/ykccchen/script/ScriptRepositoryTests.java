package cn.ykccchen.script;

import cn.ykccchen.script.exception.ResourceNotFoundException;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.ConcurrentModificationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScriptRepositoryTests {

	@Test
	public void reloadShouldCompileVersionAndPublishChanges() {
		ScriptRepository repository = new ScriptRepository();
		List<ScriptChangeEvent> changes = new ArrayList<>();
		Registration listener = repository.addChangeListener(changes::add);
		try {
			ScriptRevision first = repository.reload(" price ", "return price * 2;");
			Assert.assertEquals(1L, first.getVersion());
			Assert.assertEquals("price", first.getName());
			Assert.assertEquals(10, repository.execute("price", new ScriptContext().set("price", 5)));

			ScriptRevision second = repository.reload("price", "return price * 3;");
			Assert.assertTrue(second.getVersion() > first.getVersion());
			Assert.assertEquals(15, repository.execute("price", new ScriptContext().set("price", 5)));
			Assert.assertEquals(Arrays.asList(ScriptChangeEvent.Type.ADDED, ScriptChangeEvent.Type.UPDATED),
					Arrays.asList(changes.get(0).getType(), changes.get(1).getType()));
			Assert.assertSame(first, changes.get(1).getPreviousRevision());
			Assert.assertSame(second, changes.get(1).getCurrentRevision());
			Assert.assertTrue(changes.get(0).getSequence() > 0);
			Assert.assertEquals(changes.get(0).getSequence() + 1, changes.get(1).getSequence());
			Assert.assertTrue(changes.get(1).getPublishedAtMillis() > 0);
			Assert.assertEquals(2, repository.getHistory("price").size());
			Assert.assertSame(second, repository.getHistory("price").get(0));

			Assert.assertTrue(repository.remove("price"));
			Assert.assertFalse(repository.remove("price"));
			Assert.assertEquals(ScriptChangeEvent.Type.REMOVED, changes.get(2).getType());
			assertMissing(() -> repository.get("price"));
		} finally {
			listener.close();
		}
	}

	@Test
	public void failedReloadShouldKeepActiveRevision() {
		ScriptRepository repository = new ScriptRepository();
		ScriptRevision active = repository.reload("stable", "return 42;");
		try {
			repository.reload("stable", "var total = ; return total;");
			Assert.fail("Invalid reload must fail");
		} catch (RuntimeException expected) {
			Assert.assertSame(active, repository.get("stable"));
			Assert.assertEquals(42, repository.execute("stable", new ScriptContext()));
		}
	}

	@Test
	public void batchReloadShouldNotPartiallyPublishCompileFailures() {
		ScriptRepository repository = new ScriptRepository();
		Map<String, String> sources = new LinkedHashMap<>();
		sources.put("first", "return 1;");
		sources.put("broken", "return ++1;");
		try {
			repository.reloadAll(sources);
			Assert.fail("Invalid batch must fail");
		} catch (RuntimeException expected) {
			Assert.assertTrue(repository.getNames().isEmpty());
		}
	}

	@Test(timeout = 10000)
	public void concurrentReadersShouldObserveCompleteRevisions() throws Exception {
		ScriptRepository repository = new ScriptRepository();
		repository.reload("value", "return 1;");
		ExecutorService executor = Executors.newFixedThreadPool(4);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<?>> readers = new ArrayList<>();
			for (int thread = 0; thread < 3; thread++) {
				readers.add(executor.submit(() -> {
					start.await();
					for (int i = 0; i < 200; i++) {
						Object result = repository.execute("value", new ScriptContext());
						Assert.assertTrue(result.equals(1) || result.equals(2));
					}
					return null;
				}));
			}
			start.countDown();
			repository.reload("value", "return 2;");
			for (Future<?> reader : readers) {
				reader.get(5, TimeUnit.SECONDS);
			}
			Assert.assertEquals(2, repository.execute("value", new ScriptContext()));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test(expected = UnsupportedOperationException.class)
	public void repositoryNamesShouldBeImmutable() {
		ScriptRepository repository = new ScriptRepository();
		repository.reload("name", "return 1;");
		repository.getNames().remove("name");
	}

	@Test
	public void successfulBatchAndListenerFailureShouldRemainObservable() {
		JvmScriptEngine engine = new JvmScriptEngine(new ScriptEngineFactory());
		ScriptRepository repository = new ScriptRepository(engine);
		repository.addChangeListener(event -> {
			throw new IllegalStateException("observer failure");
		});
		Map<String, String> sources = new LinkedHashMap<>();
		sources.put("second", "return 2;");
		sources.put("first", "return 1;");

		Map<String, ScriptRevision> loaded = repository.reloadAll(sources);
		Assert.assertSame(engine, repository.getEngine());
		Assert.assertEquals(2, loaded.size());
		Assert.assertTrue(repository.find("first").isPresent());
		Assert.assertFalse(repository.find("missing").isPresent());
		Assert.assertEquals(Arrays.asList("first", "second"), Arrays.asList(
				repository.getRevisions().get(0).getName(),
				repository.getRevisions().get(1).getName()));
		Assert.assertTrue(repository.get("first").getLoadedAtMillis() > 0);
		Assert.assertEquals("return 1;", repository.get("first").getSource());
		Assert.assertEquals(2, repository.getListenerFailureCount());
		Assert.assertSame(loaded.get("first"), repository.getHistory("first").get(0));
		Assert.assertSame(loaded.get("second"), repository.getHistory("second").get(0));
	}

	@Test(timeout = 10000)
	public void slowListenerShouldNotHoldRepositoryMutationLock() throws Exception {
		ScriptRepository repository = new ScriptRepository();
		CountDownLatch listenerEntered = new CountDownLatch(1);
		CountDownLatch releaseListener = new CountDownLatch(1);
		repository.addChangeListener(event -> {
			if ("slow".equals(event.getName())) {
				listenerEntered.countDown();
				try {
					releaseListener.await();
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			}
		});
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> slow = executor.submit(() -> repository.reload("slow", "return 1;"));
			Assert.assertTrue(listenerEntered.await(2, TimeUnit.SECONDS));
			Future<?> fast = executor.submit(() -> repository.reload("fast", "return 2;"));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (!repository.find("fast").isPresent() && System.nanoTime() < deadline) {
				Thread.yield();
			}
			Assert.assertTrue("Second mutation should commit while listener is blocked",
					repository.find("fast").isPresent());
			releaseListener.countDown();
			slow.get(2, TimeUnit.SECONDS);
			fast.get(2, TimeUnit.SECONDS);
		} finally {
			releaseListener.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	public void batchListenersShouldObserveOnePublishedSnapshot() {
		ScriptRepository repository = new ScriptRepository();
		Map<String, String> initial = new LinkedHashMap<>();
		initial.put("left", "return 1;");
		initial.put("right", "return 1;");
		repository.reloadAll(initial);

		List<List<Object>> observed = new ArrayList<>();
		repository.addChangeListener(event -> observed.add(Arrays.asList(
				repository.execute("left", new ScriptContext()),
				repository.execute("right", new ScriptContext()))));
		Map<String, String> updated = new LinkedHashMap<>();
		updated.put("left", "return 2;");
		updated.put("right", "return 2;");
		repository.reloadAll(updated);

		Assert.assertEquals(2, observed.size());
		Assert.assertEquals(Arrays.asList(2, 2), observed.get(0));
		Assert.assertEquals(Arrays.asList(2, 2), observed.get(1));
	}

	@Test
	public void optimisticReloadShouldRejectStaleVersions() {
		ScriptRepository repository = new ScriptRepository();
		List<ScriptChangeEvent> events = new ArrayList<>();
		repository.addChangeListener(events::add);
		ScriptRevision first = repository.reload("versioned", 0, "return 1;");
		ScriptRevision second = repository.reload("versioned", first.getVersion(), "return 2;");
		Assert.assertEquals(2, events.size());
		Assert.assertEquals(ScriptChangeEvent.Type.ADDED, events.get(0).getType());
		Assert.assertEquals(ScriptChangeEvent.Type.UPDATED, events.get(1).getType());
		Assert.assertEquals(Arrays.asList(second, first), repository.getHistory("versioned"));
		try {
			repository.reload("versioned", first.getVersion(), "return 3;");
			Assert.fail("Stale version must be rejected");
		} catch (ConcurrentModificationException expected) {
			Assert.assertSame(second, repository.get("versioned"));
			Assert.assertEquals(2, repository.execute("versioned", new ScriptContext()));
		}
		try {
			repository.reload("versioned", -1, "return 4;");
			Assert.fail("Negative versions must be rejected");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	@Test
	public void rollbackShouldPublishNewRevisionAndRetainBoundedHistory() {
		ScriptRepository repository = new ScriptRepository(null, 3);
		List<ScriptChangeEvent> events = new ArrayList<>();
		repository.addChangeListener(events::add);
		ScriptRevision first = repository.reload("rule", "return 1;");
		repository.reload("rule", "return 2;");
		repository.reload("rule", "return 3;");
		ScriptRevision rollback = repository.rollback("rule", first.getVersion());

		Assert.assertEquals(1, repository.execute("rule", new ScriptContext()));
		Assert.assertTrue(rollback.getVersion() > first.getVersion());
		Assert.assertEquals(3, repository.getHistory("rule").size());
		Assert.assertSame(rollback, repository.getHistory("rule").get(0));
		Assert.assertFalse(repository.getHistory("rule").contains(first));
		Assert.assertEquals(ScriptChangeEvent.Type.ROLLED_BACK,
				events.get(events.size() - 1).getType());
		assertMissing(() -> repository.rollback("rule", 999999));
	}

	@Test
	public void repositorySnapshotShouldRoundTripUnicodeSources() throws Exception {
		Path directory = Files.createTempDirectory("script-repository-test");
		Path snapshot = directory.resolve("repository.bin");
		ScriptRepository source = new ScriptRepository();
		source.reload("问候", "return '你好';");
		source.reload("sum", "return left + right;");
		source.save(snapshot);

		ScriptRepository restored = new ScriptRepository();
		List<ScriptChangeEvent> events = new ArrayList<>();
		restored.addChangeListener(events::add);
		restored.reload("stale", "return 0;");
		events.clear();
		Map<String, ScriptRevision> loaded = restored.restore(snapshot);
		Assert.assertEquals(2, loaded.size());
		Assert.assertFalse(restored.find("stale").isPresent());
		Assert.assertEquals("你好", restored.execute("问候", new ScriptContext()));
		Assert.assertEquals(7, restored.execute("sum",
				new ScriptContext().set("left", 3).set("right", 4)));
		Assert.assertEquals(3, events.size());
		Assert.assertEquals(ScriptChangeEvent.Type.REMOVED, events.get(0).getType());
		Assert.assertSame(loaded.get("问候"), restored.getHistory("问候").get(0));
		Assert.assertSame(loaded.get("sum"), restored.getHistory("sum").get(0));
	}

	@Test
	public void historyShouldSupportGlobalLimitTtlAndExplicitPurge() throws Exception {
		ScriptRepository limited = new ScriptRepository(null, 3, 0, 2);
		ScriptRevision oldest = limited.reload("a", "return 1;");
		ScriptRevision middle = limited.reload("b", "return 2;");
		ScriptRevision newest = limited.reload("a", "return 3;");
		int retained = limited.getHistory("a").size() + limited.getHistory("b").size()
				+ limited.getHistory("c").size();
		Assert.assertEquals(2, retained);
		Assert.assertFalse(limited.getHistory("a").stream()
				.anyMatch(revision -> revision.getVersion() == oldest.getVersion()));
		Assert.assertEquals(newest.getVersion(), limited.getHistory("a").get(0).getVersion());
		Assert.assertEquals(middle.getVersion(), limited.getHistory("b").get(0).getVersion());
		limited.reload("c", "return 4;");
		Assert.assertTrue(limited.getHistory("b").isEmpty());
		Assert.assertEquals(2, limited.purgeAllHistory());
		Assert.assertTrue(limited.getHistory("a").isEmpty());
		Assert.assertTrue(limited.getHistory("c").isEmpty());
		Assert.assertEquals(0, limited.purgeAllHistory());

		ScriptRepository expiring = new ScriptRepository(null, 3, 1, 10);
		expiring.reload("temporary", "return 1;");
		Thread.sleep(5);
		Assert.assertTrue(expiring.getHistory("temporary").isEmpty());
		expiring.reload("temporary", "return 2;");
		Assert.assertTrue(expiring.remove("temporary", true));
		Assert.assertEquals(0, expiring.purgeHistory("temporary"));
	}

	@Test
	public void corruptedSnapshotShouldFailChecksumWithoutChangingRepository() throws Exception {
		Path directory = Files.createTempDirectory("script-repository-checksum");
		Path snapshot = directory.resolve("repository.bin");
		ScriptRepository source = new ScriptRepository();
		source.reload("value", "return 1;");
		source.save(snapshot);
		byte[] bytes = Files.readAllBytes(snapshot);
		bytes[bytes.length - 1] ^= 1;
		Files.write(snapshot, bytes);

		ScriptRepository target = new ScriptRepository();
		target.reload("stable", "return 42;");
		try {
			target.restore(snapshot);
			Assert.fail("Expected checksum failure");
		} catch (java.io.IOException expected) {
			Assert.assertTrue(expected.getMessage().contains("checksum")
					|| expected.getMessage().contains("trailing"));
		}
		Assert.assertEquals(42, target.execute("stable", new ScriptContext()));
	}

	@Test
	public void repositoryShouldValidateConfigurationAndSnapshotEnvelope() throws Exception {
		assertIllegalArgument(() -> new ScriptRepository(null, 0, 0, 1));
		assertIllegalArgument(() -> new ScriptRepository(null, 1, -1, 1));
		assertIllegalArgument(() -> new ScriptRepository(null, 1, 0, 0));
		assertIllegalArgument(() -> new ScriptRepository().rollback("missing", 0));

		Path directory = Files.createTempDirectory("script-repository-envelope");
		Path valid = directory.resolve("valid.bin");
		ScriptRepository source = new ScriptRepository();
		source.reload("value", "return 1;");
		source.save(valid);
		byte[] bytes = Files.readAllBytes(valid);

		byte[] badMagic = bytes.clone();
		ByteBuffer.wrap(badMagic).putInt(0, 0);
		assertRestoreFails(directory.resolve("bad-magic.bin"), badMagic);

		byte[] badVersion = bytes.clone();
		ByteBuffer.wrap(badVersion).putInt(4, 99);
		assertRestoreFails(directory.resolve("bad-version.bin"), badVersion);

		byte[] badCount = bytes.clone();
		ByteBuffer.wrap(badCount).putInt(8, -1);
		assertRestoreFails(directory.resolve("bad-count.bin"), badCount);

		assertRestoreFails(directory.resolve("truncated.bin"),
				Arrays.copyOf(bytes, bytes.length - 1));
		byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1);
		assertRestoreFails(directory.resolve("trailing.bin"), trailing);
	}

	@Test
	public void removeAndPurgeShouldReportExactHistoryChanges() {
		ScriptRepository repository = new ScriptRepository();
		repository.reload("retained", "return 1;");
		repository.reload("retained", "return 2;");
		Assert.assertTrue(repository.remove("retained"));
		Assert.assertEquals(2, repository.getHistory("retained").size());
		Assert.assertEquals(2, repository.purgeHistory("retained"));
		Assert.assertTrue(repository.getHistory("retained").isEmpty());
		Assert.assertFalse(repository.remove("retained"));
	}

	@Test
	public void reentrantListenerShouldPreserveSequenceForEveryObserver() {
		ScriptRepository repository = new ScriptRepository();
		AtomicBoolean nested = new AtomicBoolean();
		List<Long> observed = new ArrayList<>();
		repository.addChangeListener(event -> {
			if ("outer".equals(event.getName()) && nested.compareAndSet(false, true)) {
				repository.reload("inner", "return 2;");
			}
		});
		repository.addChangeListener(event -> observed.add(event.getSequence()));
		repository.reload("outer", "return 1;");
		Assert.assertEquals(2, observed.size());
		Assert.assertEquals(observed.get(0) + 1, observed.get(1).longValue());
	}

	private static void assertMissing(Runnable operation) {
		try {
			operation.run();
			Assert.fail("Expected missing script");
		} catch (ResourceNotFoundException expected) {
			// expected
		}
	}

	private static void assertIllegalArgument(Runnable operation) {
		try {
			operation.run();
			Assert.fail("Expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	private static void assertRestoreFails(Path path, byte[] bytes) throws Exception {
		Files.write(path, bytes);
		ScriptRepository repository = new ScriptRepository();
		repository.reload("stable", "return 42;");
		try {
			repository.restore(path);
			Assert.fail("Expected invalid repository snapshot");
		} catch (java.io.IOException expected) {
			// expected
		}
		Assert.assertEquals(42, repository.execute("stable", new ScriptContext()));
	}
}
