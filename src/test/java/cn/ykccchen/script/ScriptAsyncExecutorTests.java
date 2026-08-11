package cn.ykccchen.script;

import cn.ykccchen.script.exception.ResourceNotFoundException;
import cn.ykccchen.script.exception.ScriptExecutionException;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class ScriptAsyncExecutorTests {

	@Test(timeout = 10000)
	public void pinnedAndLatestPoliciesShouldResolveAtDifferentTimes() throws Exception {
		ExecutorService worker = Executors.newSingleThreadExecutor();
		CountDownLatch occupied = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		worker.submit(() -> {
			occupied.countDown();
			release.await();
			return null;
		});
		Assert.assertTrue(occupied.await(2, TimeUnit.SECONDS));

		ScriptAsyncExecutor executor = ScriptAsyncExecutor.builder()
				.executorService(worker)
				.maxOutstandingTasks(4)
				.build();
		try {
			ScriptRepository repository = new ScriptRepository();
			ScriptRevision first = repository.reload("pricing", "return 1;");
			ScriptTask pinned = executor.submit(repository, "pricing", ScriptVersionPolicy.PINNED,
					ScriptContext::new);
			ScriptTask latest = executor.submit(repository, "pricing", ScriptVersionPolicy.LATEST,
					ScriptContext::new);
			ScriptRevision second = repository.reload("pricing", "return 2;");
			release.countDown();

			Assert.assertEquals(1, await(pinned));
			Assert.assertEquals(first.getVersion(), pinned.getScriptVersion());
			Assert.assertEquals(2, await(latest));
			Assert.assertEquals(second.getVersion(), latest.getScriptVersion());
		} finally {
			release.countDown();
			executor.close();
			Assert.assertFalse(worker.isShutdown());
			worker.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void executionEventShouldCarryTaskAndCorrelationIds() throws Exception {
		List<ScriptTaskEvent> taskEvents = new ArrayList<>();
		AtomicLong executionTaskId = new AtomicLong();
		List<String> correlations = new ArrayList<>();
		ScriptAsyncExecutor executor = ScriptAsyncExecutor.builder()
				.corePoolSize(1)
				.maximumPoolSize(1)
				.queueCapacity(4)
				.addTaskListener(event -> {
					taskEvents.add(event);
					if (event.getState() == ScriptTaskState.RUNNING) {
						throw new IllegalStateException("listener failure must be isolated");
					}
				})
				.build();
		try {
			Script script = Script.create("return 42;", null);
			ScriptTask task = executor.submit(script, () -> {
				ScriptContext context = new ScriptContext().setCorrelationId("request-7");
				context.setScriptName("direct");
				context.addExecutionListener(new ScriptExecutionListener() {
					@Override
					public void beforeExecution(ScriptExecutionEvent event) {
						executionTaskId.set(event.getTaskId());
						correlations.add(event.getCorrelationId());
					}
				});
				return context;
			});

			Assert.assertEquals(42, await(task));
			Assert.assertEquals(task.getTaskId(), executionTaskId.get());
			Assert.assertEquals("request-7", task.getCorrelationId());
			Assert.assertEquals("request-7", correlations.get(0));
			Assert.assertEquals("direct", task.getScriptName());
			Assert.assertTrue(task.getStartedAtMillis() >= task.getSubmittedAtMillis());
			Assert.assertTrue(task.getCompletedAtMillis() >= task.getStartedAtMillis());
			Assert.assertEquals(ScriptTaskState.SUCCEEDED, task.getState());
			Assert.assertTrue(taskEvents.stream()
					.anyMatch(event -> event.getState() == ScriptTaskState.SUCCEEDED
							&& event.getExecutionDurationNanos() >= 0));
		} finally {
			executor.close();
		}
	}

	@Test(timeout = 10000)
	public void queuedCancellationShouldNotCreateContext() throws Exception {
		ExecutorService worker = Executors.newSingleThreadExecutor();
		CountDownLatch release = new CountDownLatch(1);
		worker.submit(() -> {
			release.await();
			return null;
		});
		ScriptAsyncExecutor executor = ScriptAsyncExecutor.builder()
				.executorService(worker)
				.maxOutstandingTasks(2)
				.build();
		AtomicInteger contexts = new AtomicInteger();
		try {
			ScriptTask task = executor.submit(Script.create("return 1;", null), () -> {
				contexts.incrementAndGet();
				return new ScriptContext();
			});
			Assert.assertTrue(task.cancel());
			assertCancellation(task);
			Assert.assertEquals(0, contexts.get());
			Assert.assertEquals(ScriptTaskState.CANCELLED, task.getState());
		} finally {
			release.countDown();
			executor.close();
			worker.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void runningCancellationShouldCompleteAfterWorkerStops() throws Exception {
		CountDownLatch executionStarted = new CountDownLatch(1);
		ScriptAsyncExecutor executor = ScriptAsyncExecutor.builder()
				.corePoolSize(1)
				.maximumPoolSize(1)
				.queueCapacity(2)
				.build();
		try {
			ScriptTask task = executor.submit(Script.create("while(true){}", null), () -> {
				ScriptContext context = new ScriptContext();
				context.addExecutionListener(new ScriptExecutionListener() {
					@Override
					public void beforeExecution(ScriptExecutionEvent event) {
						executionStarted.countDown();
					}
				});
				return context;
			});
			Assert.assertTrue(executionStarted.await(2, TimeUnit.SECONDS));
			Assert.assertTrue(task.cancel());
			Assert.assertTrue(task.getState() == ScriptTaskState.CANCELLING
					|| task.getState() == ScriptTaskState.CANCELLED);
			assertCancellation(task);
			Assert.assertEquals(ScriptTaskState.CANCELLED, task.getState());
			Assert.assertFalse(task.cancel());
		} finally {
			executor.close();
		}
	}

	@Test(timeout = 10000)
	public void timeoutShouldHaveDistinctTerminalStateAndReason() throws Exception {
		AtomicReference<ScriptExecutionEvent.Status> executionStatus = new AtomicReference<>();
		ScriptAsyncExecutor executor = ScriptAsyncExecutor.builder()
				.corePoolSize(1)
				.maximumPoolSize(1)
				.queueCapacity(2)
				.defaultTimeout(20, TimeUnit.MILLISECONDS)
				.build();
		try {
			ScriptTask task = executor.submit(Script.create("while(true){}", null), () -> {
				ScriptContext context = new ScriptContext();
				context.addExecutionListener(new ScriptExecutionListener() {
					@Override
					public void afterExecution(ScriptExecutionEvent event) {
						executionStatus.set(event.getStatus());
					}
				});
				return context;
			});
			try {
				await(task);
				Assert.fail("Expected timeout");
			} catch (ExecutionException exception) {
				Assert.assertTrue(exception.getCause() instanceof ScriptExecutionException);
				Assert.assertEquals(ScriptExecutionException.Reason.TIMED_OUT,
						((ScriptExecutionException) exception.getCause()).getReason());
			}
			Assert.assertEquals(ScriptTaskState.TIMED_OUT, task.getState());
			Assert.assertEquals(ScriptExecutionEvent.Status.TIMED_OUT, executionStatus.get());
		} finally {
			executor.close();
		}
	}

	@Test(timeout = 10000)
	public void outstandingLimitShouldRejectWithoutInvokingContextFactory() throws Exception {
		CountDownLatch executionStarted = new CountDownLatch(1);
		ScriptAsyncExecutor executor = ScriptAsyncExecutor.builder()
				.corePoolSize(1)
				.maximumPoolSize(1)
				.queueCapacity(2)
				.maxOutstandingTasks(1)
				.build();
		try {
			ScriptTask first = executor.submit(Script.create("while(true){}", null), () -> {
				ScriptContext context = new ScriptContext();
				context.addExecutionListener(new ScriptExecutionListener() {
					@Override
					public void beforeExecution(ScriptExecutionEvent event) {
						executionStarted.countDown();
					}
				});
				return context;
			});
			Assert.assertTrue(executionStarted.await(2, TimeUnit.SECONDS));
			AtomicInteger rejectedContexts = new AtomicInteger();
			ScriptTask rejected = executor.submit(Script.create("return 2;", null), () -> {
				rejectedContexts.incrementAndGet();
				return new ScriptContext();
			});
			Assert.assertEquals(ScriptTaskState.REJECTED, rejected.getState());
			try {
				await(rejected);
				Assert.fail("Expected rejection");
			} catch (ExecutionException exception) {
				Assert.assertTrue(exception.getCause() instanceof RejectedExecutionException);
			}
			Assert.assertEquals(0, rejectedContexts.get());
			first.cancel();
			assertCancellation(first);
		} finally {
			executor.close();
		}
	}

	@Test(timeout = 10000)
	public void sharedContextAndMissingPinnedScriptShouldFailAsTasks() throws Exception {
		ScriptAsyncExecutor executor = ScriptAsyncExecutor.builder()
				.corePoolSize(2)
				.maximumPoolSize(2)
				.queueCapacity(2)
				.build();
		ScriptContext shared = new ScriptContext();
		CountDownLatch firstStarted = new CountDownLatch(1);
		shared.addExecutionListener(new ScriptExecutionListener() {
			@Override
			public void beforeExecution(ScriptExecutionEvent event) {
				firstStarted.countDown();
			}
		});
		try {
			Script script = Script.create("while(true){}", null);
			ScriptTask first = executor.submit(script, () -> shared);
			Assert.assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
			ScriptTask second = executor.submit(script, () -> shared);
			try {
				await(second);
				Assert.fail("Expected shared context rejection");
			} catch (ExecutionException exception) {
				Assert.assertTrue(exception.getCause() instanceof IllegalStateException);
			}
			Assert.assertEquals(ScriptTaskState.FAILED, second.getState());
			first.cancel();
			assertCancellation(first);

			ScriptTask missing = executor.submit(new ScriptRepository(), "missing", ScriptContext::new);
			Assert.assertEquals(ScriptTaskState.FAILED, missing.getState());
			try {
				await(missing);
				Assert.fail("Expected missing script failure");
			} catch (ExecutionException exception) {
				Assert.assertTrue(exception.getCause() instanceof ResourceNotFoundException);
			}
		} finally {
			executor.close();
		}
	}

	@Test(timeout = 10000)
	public void shutdownShouldRejectNewTasksAndPreserveExternalExecutor() throws Exception {
		ExecutorService worker = Executors.newSingleThreadExecutor();
		ScriptAsyncExecutor executor = ScriptAsyncExecutor.builder()
				.executorService(worker)
				.maxOutstandingTasks(2)
				.build();
		Assert.assertEquals(1, await(executor.submit(Script.create("return 1;", null), ScriptContext::new)));
		executor.shutdown();
		Assert.assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
		Assert.assertTrue(executor.isShutdown());
		Assert.assertTrue(executor.isTerminated());
		Assert.assertFalse(worker.isShutdown());
		ScriptTask rejected = executor.submit(Script.create("return 2;", null), ScriptContext::new);
		Assert.assertEquals(ScriptTaskState.REJECTED, rejected.getState());
		worker.shutdownNow();
	}

	@Test(timeout = 10000)
	public void taskMetricsShouldAggregateSuccessFailureAndRejection() throws Exception {
		ScriptTaskMetrics metrics = new ScriptTaskMetrics();
		ScriptAsyncExecutor executor = ScriptAsyncExecutor.builder()
				.corePoolSize(1)
				.maximumPoolSize(1)
				.queueCapacity(2)
				.addTaskListener(metrics)
				.build();
		try {
			Assert.assertEquals(1, await(executor.submit(
					Script.create("return 1;", null), ScriptContext::new)));
			try {
				await(executor.submit(Script.create("return missing.method();", null), ScriptContext::new));
				Assert.fail("Expected script failure");
			} catch (ExecutionException expected) {
				// expected
			}
			executor.shutdown();
			ScriptTask rejected = executor.submit(Script.create("return 2;", null), ScriptContext::new);
			Assert.assertEquals(ScriptTaskState.REJECTED, rejected.getState());

			ScriptTaskStats stats = metrics.snapshot();
			Assert.assertEquals(3, stats.getSubmittedCount());
			Assert.assertEquals(2, stats.getStartedCount());
			Assert.assertEquals(3, stats.getCompletedCount());
			Assert.assertEquals(1, stats.getSucceededCount());
			Assert.assertEquals(1, stats.getFailedCount());
			Assert.assertEquals(1, stats.getRejectedCount());
			Assert.assertEquals(0, stats.getActiveCount());
			Assert.assertTrue(stats.getAverageQueueDurationNanos() >= 0);
			Assert.assertTrue(stats.getAverageExecutionDurationNanos() >= 0);
			metrics.reset();
			Assert.assertEquals(0, metrics.snapshot().getSubmittedCount());
		} finally {
			executor.close();
		}
	}

	@Test
	public void executionAverageShouldExcludeStillRunningTasks() {
		ScriptTaskMetrics metrics = new ScriptTaskMetrics();
		metrics.onStateChanged(new ScriptTaskEvent(1, ScriptTaskState.QUEUED, "one", 0,
				null, 1, 0, 0, 0, 0, null));
		metrics.onStateChanged(new ScriptTaskEvent(1, ScriptTaskState.RUNNING, "one", 0,
				null, 1, 2, 0, 10, 0, null));
		metrics.onStateChanged(new ScriptTaskEvent(1, ScriptTaskState.SUCCEEDED, "one", 0,
				null, 1, 2, 3, 10, 100, null));
		metrics.onStateChanged(new ScriptTaskEvent(2, ScriptTaskState.QUEUED, "two", 0,
				null, 1, 0, 0, 0, 0, null));
		metrics.onStateChanged(new ScriptTaskEvent(2, ScriptTaskState.RUNNING, "two", 0,
				null, 1, 2, 0, 20, 0, null));

		ScriptTaskStats stats = metrics.snapshot();
		Assert.assertEquals(2, stats.getStartedCount());
		Assert.assertEquals(1, stats.getCompletedStartedCount());
		Assert.assertEquals(100, stats.getAverageExecutionDurationNanos());
		Assert.assertEquals(15, stats.getAverageQueueDurationNanos());
	}

	@Test(timeout = 10000)
	public void builderShouldValidateConfigurationAndPreserveExternalScheduler() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> ScriptAsyncExecutor.builder().corePoolSize(0));
		assertThrows(IllegalArgumentException.class, () -> ScriptAsyncExecutor.builder().maximumPoolSize(0));
		assertThrows(IllegalArgumentException.class, () -> ScriptAsyncExecutor.builder().queueCapacity(0));
		assertThrows(IllegalArgumentException.class, () -> ScriptAsyncExecutor.builder().maxOutstandingTasks(0));
		assertThrows(IllegalArgumentException.class,
				() -> ScriptAsyncExecutor.builder().keepAlive(-1, TimeUnit.MILLISECONDS));
		assertThrows(IllegalArgumentException.class,
				() -> ScriptAsyncExecutor.builder().defaultTimeout(1, TimeUnit.NANOSECONDS));
		assertThrows(IllegalArgumentException.class,
				() -> ScriptAsyncExecutor.builder().closeTimeout(-1, TimeUnit.MILLISECONDS));
		assertThrows(IllegalArgumentException.class,
				() -> ScriptAsyncExecutor.builder().threadNamePrefix("  "));
		assertThrows(NullPointerException.class,
				() -> ScriptAsyncExecutor.builder().executorService(null));
		assertThrows(NullPointerException.class,
				() -> ScriptAsyncExecutor.builder().scheduler(null));
		assertThrows(NullPointerException.class,
				() -> ScriptAsyncExecutor.builder().addTaskListener(null));
		ExecutorService incompatibleExternal = Executors.newSingleThreadExecutor();
		try {
			assertThrows(IllegalStateException.class, () -> ScriptAsyncExecutor.builder()
					.executorService(incompatibleExternal).corePoolSize(1).build());
		} finally {
			incompatibleExternal.shutdownNow();
		}
		assertThrows(IllegalStateException.class, () -> ScriptAsyncExecutor.builder()
				.corePoolSize(2).maximumPoolSize(1).build());

		ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
		AtomicReference<String> threadName = new AtomicReference<>();
		AtomicReference<Boolean> daemon = new AtomicReference<>();
		ScriptAsyncExecutor executor = ScriptAsyncExecutor.builder()
				.scheduler(scheduler)
				.corePoolSize(1)
				.maximumPoolSize(1)
				.queueCapacity(1)
				.keepAlive(0, TimeUnit.MILLISECONDS)
				.defaultTimeout(0, TimeUnit.MILLISECONDS)
				.closeTimeout(1, TimeUnit.SECONDS)
				.threadNamePrefix("contract")
				.daemonThreads(true)
				.build();
		try {
			Assert.assertEquals(1, await(executor.submit(Script.create("return 1;", null), () -> {
				ScriptContext context = new ScriptContext();
				context.addExecutionListener(new ScriptExecutionListener() {
					@Override
					public void beforeExecution(ScriptExecutionEvent event) {
						threadName.set(Thread.currentThread().getName());
						daemon.set(Thread.currentThread().isDaemon());
					}
				});
				return context;
			})));
			Assert.assertTrue(threadName.get().startsWith("contract-worker-"));
			Assert.assertEquals(Boolean.TRUE, daemon.get());
		} finally {
			executor.close();
			Assert.assertFalse(scheduler.isShutdown());
			scheduler.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void shutdownNowAndExternalAwaitShouldCoverLifecycleBoundaries() throws Exception {
		ExecutorService worker = Executors.newSingleThreadExecutor();
		CountDownLatch occupied = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		worker.submit(() -> {
			occupied.countDown();
			release.await();
			return null;
		});
		Assert.assertTrue(occupied.await(2, TimeUnit.SECONDS));
		ScriptAsyncExecutor executor = ScriptAsyncExecutor.builder()
				.executorService(worker).maxOutstandingTasks(2).build();
		try {
			ScriptTask queued = executor.submit(Script.create("return 1;", null), ScriptContext::new);
			Assert.assertEquals(1, executor.getOutstandingTaskCount());
			Assert.assertFalse(executor.isTerminated());
			Assert.assertFalse(executor.awaitTermination(1, TimeUnit.MILLISECONDS));
			List<ScriptTask> cancelled = executor.shutdownNow();
			Assert.assertTrue(cancelled.contains(queued));
			Assert.assertTrue(executor.isShutdown());
			Assert.assertFalse(worker.isShutdown());

			ScriptRepository repository = new ScriptRepository();
			repository.reload("after-shutdown", "return 2;");
			ScriptTask rejected = executor.submit(repository, "after-shutdown", ScriptContext::new);
			Assert.assertEquals(ScriptTaskState.REJECTED, rejected.getState());
			assertThrows(IllegalArgumentException.class,
					() -> executor.submit(repository, "  ", ScriptContext::new));
			try {
				executor.awaitTermination(1, null);
				Assert.fail("Expected null unit rejection");
			} catch (NullPointerException expected) {
				// expected
			}
		} finally {
			release.countDown();
			worker.shutdownNow();
		}
	}

	private static Object await(ScriptTask task) throws Exception {
		return task.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
	}

	private static void assertCancellation(ScriptTask task) throws Exception {
		try {
			await(task);
			Assert.fail("Expected cancellation");
		} catch (CancellationException expected) {
			// expected
		}
	}

	private static void assertThrows(Class<? extends Throwable> type, Runnable operation) {
		try {
			operation.run();
			Assert.fail("Expected " + type.getName());
		} catch (Throwable failure) {
			Assert.assertTrue("Unexpected failure: " + failure, type.isInstance(failure));
		}
	}
}
