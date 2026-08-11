package cn.ykccchen.script;

import cn.ykccchen.script.exception.ScriptRuntimeException;
import cn.ykccchen.script.exception.ScriptExecutionException;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Future;
import java.util.concurrent.CompletionStage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class LanguageAsyncTests {

	@Test(timeout = 10000)
	public void asyncLambdaAndFunctionCallShouldReturnFutureResults() {
		ExecutorService languageExecutor = Executors.newFixedThreadPool(2);
		try {
			ScriptContext context = new ScriptContext().setLanguageAsyncExecutor(languageExecutor);
			Assert.assertEquals(42, execute(
					"var task = async ()=> 42; return task.get();", context));
			Assert.assertEquals(42, execute(
					"var answer = ()=> 41; var task = async answer(); return task.get() + 1;", context));
			Assert.assertEquals(42, execute(
					"return await async ()=> 42;", context));
			Assert.assertEquals(42, execute(BaseTest.readScript("grammar/async.ms"), context));
			context.set("hostFuture", CompletableFuture.completedFuture(42));
			Assert.assertEquals(42, execute("return await hostFuture;", context));
		} finally {
			languageExecutor.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void asyncLambdaShouldCaptureSubmissionSnapshotAndCurrentParameter() {
		ExecutorService languageExecutor = Executors.newSingleThreadExecutor();
		try {
			ScriptContext context = new ScriptContext().setLanguageAsyncExecutor(languageExecutor);
			Object value = execute("var value = 3; "
					+ "var task = async (value)=> value + 1; "
					+ "value = 9; return [task.get(), value];", context);
			Assert.assertEquals("[4, 9]", String.valueOf(value));
		} finally {
			languageExecutor.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void asyncLambdaShouldCaptureMultipleParameters() {
		ExecutorService languageExecutor = Executors.newSingleThreadExecutor();
		try {
			ScriptContext context = new ScriptContext().setLanguageAsyncExecutor(languageExecutor);
			Assert.assertEquals(3, execute(
					"var a = 1; var b = 2; var task = async (a, b)=> a + b; return await task;",
					context));
		} finally {
			languageExecutor.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void nestedAsyncShouldRunWithoutSingleThreadDeadlock() {
		ExecutorService languageExecutor = Executors.newSingleThreadExecutor();
		try {
			ScriptContext context = new ScriptContext().setLanguageAsyncExecutor(languageExecutor);
			Object value = execute("var outer = async ()=> { "
					+ "var inner = async ()=> 42; return inner.get(); "
					+ "}; return outer.get();", context);
			Assert.assertEquals(42, value);
		} finally {
			languageExecutor.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void parentExecutionShouldAwaitDetachedLanguageTasks() {
		ExecutorService languageExecutor = Executors.newSingleThreadExecutor();
		AtomicInteger counter = new AtomicInteger();
		try {
			ScriptContext context = new ScriptContext()
					.set("counter", counter)
					.setLanguageAsyncExecutor(languageExecutor);
			Assert.assertEquals(1, execute(
					"async ()=> counter.incrementAndGet(); return 1;", context));
			Assert.assertEquals(1, counter.get());
		} finally {
			languageExecutor.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void engineConfigShouldProvideExecutorAndTaskMetrics() {
		ExecutorService languageExecutor = Executors.newSingleThreadExecutor();
		ScriptTaskMetrics metrics = new ScriptTaskMetrics();
		try {
			ScriptEngineConfig config = ScriptEngineConfig.builder()
					.languageAsyncExecutor(languageExecutor)
					.addLanguageAsyncListener(metrics)
					.build();
			JvmScriptEngine engine = new JvmScriptEngine(new ScriptEngineFactory(config), config);
			Script script = Script.create("var task = async ()=> 7; return task.get();", engine);
			Assert.assertEquals(7, script.execute(new ScriptContext().setCorrelationId("request-async")));
			ScriptTaskStats stats = metrics.snapshot();
			Assert.assertEquals(1, stats.getSubmittedCount());
			Assert.assertEquals(1, stats.getStartedCount());
			Assert.assertEquals(1, stats.getSucceededCount());
			Assert.assertEquals(0, stats.getActiveCount());
		} finally {
			languageExecutor.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void missingOrRejectedExecutorShouldHaveExplicitFailures() throws Exception {
		try {
			execute("return async ()=> 1;", new ScriptContext());
			Assert.fail("Expected missing executor failure");
		} catch (RuntimeException exception) {
			Assert.assertTrue(hasCause(exception, ScriptRuntimeException.class));
		}

		ExecutorService rejectedExecutor = Executors.newSingleThreadExecutor();
		rejectedExecutor.shutdownNow();
		ScriptAsyncResult result = (ScriptAsyncResult) execute(
				"return async ()=> 1;",
				new ScriptContext().setLanguageAsyncExecutor(rejectedExecutor));
		Assert.assertEquals(ScriptTaskState.REJECTED, result.getState());
		try {
			result.get();
			Assert.fail("Expected rejection");
		} catch (ExecutionException exception) {
			Assert.assertTrue(exception.getCause() instanceof RejectedExecutionException);
		}

		ExecutorService debugExecutor = Executors.newSingleThreadExecutor();
		try {
			ScriptDebugContext debugContext = new ScriptDebugContext(null);
			debugContext.setLanguageAsyncExecutor(debugExecutor);
			execute("return async ()=> 1;", debugContext);
			Assert.fail("Expected async debug rejection");
		} catch (RuntimeException exception) {
			Assert.assertTrue(hasCause(exception, ScriptRuntimeException.class));
		} finally {
			debugExecutor.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void hostCancellationShouldPropagateToLanguageChild() throws Exception {
		ExecutorService languageExecutor = Executors.newSingleThreadExecutor();
		CountDownLatch childStarted = new CountDownLatch(1);
		ScriptAsyncExecutor hostExecutor = ScriptAsyncExecutor.builder()
				.corePoolSize(1)
				.maximumPoolSize(1)
				.queueCapacity(2)
				.build();
		try {
			Script script = Script.create(
					"var task = async ()=> { while(true){} }; return task.get();", null);
			ScriptTask hostTask = hostExecutor.submit(script, () -> {
				ScriptContext context = new ScriptContext().setLanguageAsyncExecutor(languageExecutor);
				context.addLanguageAsyncListener(event -> {
					if (event.getState() == ScriptTaskState.RUNNING) {
						childStarted.countDown();
					}
				});
				return context;
			});
			Assert.assertTrue(childStarted.await(2, TimeUnit.SECONDS));
			Assert.assertTrue(hostTask.cancel());
			try {
				hostTask.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
				Assert.fail("Expected cancellation");
			} catch (CancellationException expected) {
				// expected
			}
			Assert.assertEquals(ScriptTaskState.CANCELLED, hostTask.getState());
		} finally {
			hostExecutor.close();
			languageExecutor.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void hostTimeoutShouldPropagateDistinctStateToLanguageChild() throws Exception {
		ExecutorService languageExecutor = Executors.newSingleThreadExecutor();
		CountDownLatch childStarted = new CountDownLatch(1);
		CountDownLatch childCompleted = new CountDownLatch(1);
		AtomicInteger childTimedOut = new AtomicInteger();
		ScriptAsyncExecutor hostExecutor = ScriptAsyncExecutor.builder()
				.corePoolSize(1)
				.maximumPoolSize(1)
				.queueCapacity(2)
				.defaultTimeout(200, TimeUnit.MILLISECONDS)
				.build();
		try {
			Script script = Script.create(
					"var task = async ()=> { while(true){} }; return await task;", null);
			script.compile();
			ScriptTask hostTask = hostExecutor.submit(script, () -> {
				ScriptContext context = new ScriptContext().setLanguageAsyncExecutor(languageExecutor);
				context.addLanguageAsyncListener(event -> {
					if (event.getState() == ScriptTaskState.RUNNING) {
						childStarted.countDown();
					} else if (event.getState() == ScriptTaskState.TIMED_OUT) {
						childTimedOut.incrementAndGet();
						childCompleted.countDown();
					}
				});
				return context;
			});
			Assert.assertTrue(childStarted.await(2, TimeUnit.SECONDS));
			try {
				hostTask.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
				Assert.fail("Expected timeout");
			} catch (ExecutionException expected) {
				// expected
			}
			Assert.assertEquals(ScriptTaskState.TIMED_OUT, hostTask.getState());
			Assert.assertTrue(childCompleted.await(2, TimeUnit.SECONDS));
			Assert.assertEquals(1, childTimedOut.get());
		} finally {
			hostExecutor.close();
			languageExecutor.shutdownNow();
		}
	}

	@Test
	public void invalidAsyncOperandShouldFailValidation() {
		ScriptDiagnostics diagnostics = Script.validate("return async 1 + 2;", "invalid-async.ms");
		Assert.assertFalse(diagnostics.isValid());
		Assert.assertTrue(diagnostics.getDiagnostics().get(0).getMessage().contains("Expected MethodCall"));
		ScriptDiagnostics invalidAwait = Script.validate("return await 1;", "invalid-await.ms");
		Assert.assertTrue(invalidAwait.isValid());
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			execute("return await 1;", new ScriptContext().setLanguageAsyncExecutor(executor));
			Assert.fail("Expected invalid await value");
		} catch (RuntimeException exception) {
			Assert.assertTrue(hasCause(exception, ScriptRuntimeException.class));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void asyncCompositionShouldSupportAllRaceAndTimeout() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
		try {
			ScriptContext context = new ScriptContext()
					.setLanguageAsyncExecutor(executor)
					.setLanguageAsyncScheduler(scheduler);
			Assert.assertEquals("[1, 2]", String.valueOf(execute(
					"var one = async ()=> 1; var two = async ()=> 2; "
							+ "return await Async.all([one, two]);", context)));

			context.set("fast", CompletableFuture.completedFuture(7));
			context.set("slow", new CompletableFuture<>());
			Assert.assertEquals(7, execute("return await Async.race([fast, slow]);", context));

			context.set("never", new CompletableFuture<>());
			try {
				execute("return await Async.timeout(never, 10);", context);
				Assert.fail("Expected composition timeout");
			} catch (RuntimeException exception) {
				Assert.assertTrue(hasCause(exception, java.util.concurrent.TimeoutException.class));
			}
		} finally {
			executor.shutdownNow();
			scheduler.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void compositionCancellationShouldPropagateAndRaceShouldCancelLosers() throws Exception {
		ScriptContext context = new ScriptContext();
		CompletableFuture<Object> first = new CompletableFuture<>();
		CompletableFuture<Object> second = new CompletableFuture<>();
		CompletableFuture<Object> all = LanguageAsyncRuntime.all(
				context, Arrays.asList(first, second)).toCompletableFuture();
		Assert.assertTrue(all.cancel(true));
		Assert.assertTrue(first.isCancelled());
		Assert.assertTrue(second.isCancelled());

		CompletableFuture<Object> winner = new CompletableFuture<>();
		CompletableFuture<Object> loser = new CompletableFuture<>();
		CompletionStage<Object> race = LanguageAsyncRuntime.race(context, Arrays.asList(winner, loser));
		winner.complete(42);
		Assert.assertEquals(42, race.toCompletableFuture().get());
		Assert.assertTrue(loser.isCancelled());

		CompletableFuture<Object> retained = new CompletableFuture<>();
		ScriptContext retainingContext = new ScriptContext().setLanguageAsyncPolicy(
				ScriptAsyncPolicy.builder().cancelRaceLosers(false).build());
		CompletableFuture<Object> retainedWinner = CompletableFuture.completedFuture(7);
		Assert.assertEquals(7, LanguageAsyncRuntime.race(retainingContext,
				Arrays.asList(retainedWinner, retained)).toCompletableFuture().get());
		Assert.assertFalse(retained.isCancelled());
	}

	@Test(timeout = 10000)
	public void ordinaryFutureBridgeShouldNotConsumeLanguageWorker() throws Exception {
		ExecutorService languageExecutor = Executors.newSingleThreadExecutor();
		ExecutorService hostExecutor = Executors.newSingleThreadExecutor();
		try {
			Future<Integer> hostFuture = hostExecutor.submit(() -> 42);
			ScriptContext context = new ScriptContext().setLanguageAsyncExecutor(languageExecutor);
			ScriptAsyncResult outer = LanguageAsyncRuntime.submit(context, ignored ->
					LanguageAsyncRuntime.await(context,
							LanguageAsyncRuntime.all(context, Arrays.asList(hostFuture))), null);
			Assert.assertEquals("[42]", String.valueOf(outer.get(2, TimeUnit.SECONDS)));
		} finally {
			languageExecutor.shutdownNow();
			hostExecutor.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void nestedAsyncTasksShouldExposeParentTrace() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		List<ScriptTaskEvent> queued = new ArrayList<>();
		try {
			ScriptContext context = new ScriptContext().setLanguageAsyncExecutor(executor);
			context.addLanguageAsyncListener(event -> {
				if (event.getState() == ScriptTaskState.QUEUED) {
					queued.add(event);
				}
			});
			ScriptAsyncResult parent = LanguageAsyncRuntime.submit(context, ignored -> {
				ScriptAsyncResult child = LanguageAsyncRuntime.submit(context, nested -> 42, null);
				Assert.assertEquals(parentTaskId(queued), child.getTrace().getParentTaskId());
				Assert.assertEquals(2, child.getTrace().getDepth());
				return child;
			}, null);
			Assert.assertTrue(parent.getTrace().getSubmittingThread().length() > 0);
			parent.get(2, TimeUnit.SECONDS);
			Assert.assertEquals(2, queued.size());
			Assert.assertEquals(queued.get(0).getTaskId(), queued.get(1).getParentTaskId());
			Assert.assertEquals(2, queued.get(1).getAsyncDepth());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void autoNestedPolicyShouldUseSpareWorkerAndInlineShouldRemainAvailable() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			AtomicReference<String> outerThread = new AtomicReference<>();
			AtomicReference<String> childThread = new AtomicReference<>();
			ScriptContext automatic = new ScriptContext().setLanguageAsyncExecutor(executor);
			ScriptAsyncResult outer = LanguageAsyncRuntime.submit(automatic, ignored -> {
				outerThread.set(Thread.currentThread().getName());
				ScriptAsyncResult child = LanguageAsyncRuntime.submit(automatic, nested -> {
					childThread.set(Thread.currentThread().getName());
					return 42;
				}, null);
				try {
					return child.get();
				} catch (Exception exception) {
					throw new RuntimeException(exception);
				}
			}, null);
			Assert.assertEquals(42, outer.get(2, TimeUnit.SECONDS));
			Assert.assertNotEquals(outerThread.get(), childThread.get());

			ScriptContext inline = new ScriptContext().setLanguageAsyncExecutor(executor)
					.setLanguageAsyncPolicy(ScriptAsyncPolicy.builder()
							.nestedAsyncPolicy(ScriptNestedAsyncPolicy.INLINE).build());
			AtomicReference<String> inlineOuter = new AtomicReference<>();
			AtomicReference<String> inlineChild = new AtomicReference<>();
			ScriptAsyncResult inlineResult = LanguageAsyncRuntime.submit(inline, ignored -> {
				inlineOuter.set(Thread.currentThread().getName());
				ScriptAsyncResult child = LanguageAsyncRuntime.submit(inline, nested -> {
					inlineChild.set(Thread.currentThread().getName());
					return 7;
				}, null);
				return child;
			}, null);
			inlineResult.get(2, TimeUnit.SECONDS);
			Assert.assertEquals(inlineOuter.get(), inlineChild.get());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void cancellationCleanupShouldAbandonNonInterruptibleChildWithinDeadline() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "non-interruptible-language-child");
			thread.setDaemon(true);
			return thread;
		});
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		long previousAbandoned = LanguageAsyncRuntime.stats().getAbandonedTaskCount();
		ScriptContext context = new ScriptContext().setLanguageAsyncExecutor(executor)
				.setLanguageAsyncPolicy(ScriptAsyncPolicy.builder()
						.cancellationJoinTimeout(20, TimeUnit.MILLISECONDS).build());
		try {
			ScriptAsyncResult task = LanguageAsyncRuntime.submit(context, ignored -> {
				entered.countDown();
				while (release.getCount() > 0) {
					try {
						release.await();
					} catch (InterruptedException ignoredInterrupt) {
						// Deliberately ignore cancellation to exercise abandonment.
					}
				}
				return 1;
			}, null);
			Assert.assertTrue(entered.await(2, TimeUnit.SECONDS));
			context.cancelAndAwaitLanguageAsyncTasks();
			Assert.assertEquals(ScriptTaskState.ABANDONED, task.getState());
			Assert.assertEquals(1, context.getOrphanedLanguageTaskCount());
			try {
				execute("return 1;", context);
				Assert.fail("Expected context reuse rejection while orphan is running");
			} catch (IllegalStateException expected) {
				// expected
			}
			Assert.assertEquals(previousAbandoned + 1,
					LanguageAsyncRuntime.stats().getAbandonedTaskCount());
			Assert.assertTrue(LanguageAsyncRuntime.stats().getBridgeQueueSize() >= 0);
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	private static long parentTaskId(List<ScriptTaskEvent> events) {
		return events.isEmpty() ? 0 : events.get(0).getTaskId();
	}

	@Test(timeout = 10000)
	public void childQuotaShouldRejectExcessLanguageTasks() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ScriptContext context = new ScriptContext()
				.setLanguageAsyncExecutor(executor)
				.setLanguageAsyncPolicy(ScriptAsyncPolicy.builder().maxChildTasks(1).build());
		try {
			ScriptAsyncResult first = LanguageAsyncRuntime.submit(context, ignored -> {
				entered.countDown();
				try {
					release.await();
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
				return 1;
			}, null);
			Assert.assertTrue(entered.await(2, TimeUnit.SECONDS));
			ScriptAsyncResult rejected = LanguageAsyncRuntime.submit(context, ignored -> 2, null);
			Assert.assertEquals(ScriptTaskState.REJECTED, rejected.getState());
			try {
				rejected.get();
				Assert.fail("Expected quota rejection");
			} catch (ExecutionException exception) {
				Assert.assertTrue(exception.getCause() instanceof RejectedExecutionException);
			}
			release.countDown();
			Assert.assertEquals(1, first.get(2, TimeUnit.SECONDS));
		} finally {
			release.countDown();
			context.cancelAndAwaitLanguageAsyncTasks();
			executor.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void asyncFailurePoliciesAndScopeTimeoutShouldBeEnforced() {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			ScriptContext waitAll = new ScriptContext().setLanguageAsyncExecutor(executor);
			try {
				execute("async ()=> missing.method(); return 1;", waitAll);
				Assert.fail("Expected detached child failure");
			} catch (RuntimeException expected) {
				// WAIT_ALL propagates an unobserved failure.
			}

			ScriptContext ignored = new ScriptContext()
					.setLanguageAsyncExecutor(executor)
					.setLanguageAsyncPolicy(ScriptAsyncPolicy.builder()
							.failurePolicy(ScriptAsyncFailurePolicy.IGNORE).build());
			Assert.assertEquals(1, execute("async ()=> missing.method(); return 1;", ignored));

			ScriptContext timed = new ScriptContext()
					.setLanguageAsyncExecutor(executor)
					.setLanguageAsyncPolicy(ScriptAsyncPolicy.builder()
							.scopeJoinTimeout(20, TimeUnit.MILLISECONDS).build());
			try {
				execute("async ()=> { while(true){} }; return 1;", timed);
				Assert.fail("Expected scope timeout");
			} catch (RuntimeException exception) {
				Assert.assertTrue(hasCause(exception, ScriptExecutionException.class));
			}
		} finally {
			executor.shutdownNow();
		}
	}

	private static Object execute(String source, ScriptContext context) {
		return Script.create(source, null).execute(context);
	}

	private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
		Throwable current = throwable;
		while (current != null) {
			if (type.isInstance(current)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
