package cn.ykccchen.script;

import cn.ykccchen.script.exception.ScriptExecutionException;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ScriptExecutionMonitoringTests {

	@Test
	public void contextListenerShouldReceiveSuccessfulNamedExecution() {
		ScriptRepository repository = new ScriptRepository();
		repository.reload("pricing", "return amount * 2;");
		ScriptContext context = new ScriptContext().set("amount", 4);
		context.setScriptName("caller-name");
		List<ScriptExecutionEvent> started = new ArrayList<>();
		List<ScriptExecutionEvent> completed = new ArrayList<>();
		Registration registration = context.addExecutionListener(new ScriptExecutionListener() {
			@Override
			public void beforeExecution(ScriptExecutionEvent event) {
				started.add(event);
			}

			@Override
			public void afterExecution(ScriptExecutionEvent event) {
				completed.add(event);
			}
		});

		Assert.assertEquals(8, repository.execute("pricing", context));
		Assert.assertEquals("caller-name", context.getScriptName());
		Assert.assertEquals(1, started.size());
		Assert.assertEquals(ScriptExecutionEvent.Status.RUNNING, started.get(0).getStatus());
		Assert.assertEquals("pricing", started.get(0).getScriptName());
		Assert.assertEquals(started.get(0).getExecutionId(), completed.get(0).getExecutionId());
		Assert.assertEquals(ScriptExecutionEvent.Status.SUCCEEDED, completed.get(0).getStatus());
		Assert.assertTrue(completed.get(0).isCompleted());
		Assert.assertTrue(completed.get(0).isSuccessful());
		Assert.assertTrue(completed.get(0).getDurationNanos() >= 0);

		registration.close();
		repository.execute("pricing", context);
		Assert.assertEquals(1, completed.size());
	}

	@Test
	public void engineAndContextListenersShouldBeCombinedWithoutDuplicates() {
		ScriptExecutionMetrics metrics = new ScriptExecutionMetrics();
		ScriptEngineConfig config = ScriptEngineConfig.builder()
				.addExecutionListener(metrics)
				.build();
		JvmScriptEngine engine = new JvmScriptEngine(new ScriptEngineFactory(config), config);
		ScriptContext context = new ScriptContext();
		context.addExecutionListener(metrics);
		Assert.assertEquals(42, Script.create("return 42;", engine).execute(context));

		ScriptExecutionStats stats = metrics.snapshot();
		Assert.assertEquals(1, stats.getStartedCount());
		Assert.assertEquals(1, stats.getCompletedCount());
		Assert.assertEquals(1, stats.getSucceededCount());
		Assert.assertEquals(0, stats.getFailedCount());
		Assert.assertTrue(stats.getMaxDurationNanos() >= stats.getAverageDurationNanos());
		Assert.assertTrue(config.getExecutionListeners().contains(metrics));
	}

	@Test
	public void metricsShouldClassifyFailuresAndExecutionLimits() {
		ScriptExecutionMetrics metrics = new ScriptExecutionMetrics();
		ScriptContext failed = new ScriptContext();
		failed.addExecutionListener(metrics);
		try {
			Script.create("return missing.method();", null).execute(failed);
			Assert.fail("Expected runtime failure");
		} catch (RuntimeException expected) {
			// expected
		}

		ScriptContext limited = new ScriptContext().setExecutionLimits(
				ScriptExecutionLimits.builder().maxCheckpoints(2).build());
		limited.addExecutionListener(metrics);
		try {
			Script.create("while(true){}", null).execute(limited);
			Assert.fail("Expected checkpoint limit");
		} catch (ScriptExecutionException expected) {
			Assert.assertEquals(ScriptExecutionException.Reason.CHECKPOINT_LIMIT, expected.getReason());
		}

		ScriptExecutionStats stats = metrics.snapshot();
		Assert.assertEquals(2, stats.getStartedCount());
		Assert.assertEquals(1, stats.getFailedCount());
		Assert.assertEquals(1, stats.getCheckpointLimitCount());
		Assert.assertEquals(0, stats.getTimedOutCount());
		metrics.reset();
		Assert.assertEquals(0, metrics.snapshot().getStartedCount());
	}

	@Test
	public void listenerFailureShouldNotChangeScriptResult() {
		ScriptContext context = new ScriptContext();
		context.addExecutionListener(new ScriptExecutionListener() {
			@Override
			public void beforeExecution(ScriptExecutionEvent event) {
				throw new IllegalStateException("before");
			}

			@Override
			public void afterExecution(ScriptExecutionEvent event) {
				throw new IllegalStateException("after");
			}
		});
		Assert.assertEquals(42, Script.create("return 42;", null).execute(context));
	}

	@Test
	public void metricsShouldAggregateCancellationAndTimeoutEvents() {
		ScriptExecutionMetrics metrics = new ScriptExecutionMetrics();
		ScriptExecutionEvent first = ScriptExecutionEvent.started(100, "cancelled", 1234);
		metrics.beforeExecution(first);
		ScriptExecutionEvent cancelled = ScriptExecutionEvent.completed(first, 2_000_000,
				ScriptExecutionEvent.Status.CANCELLED, new RuntimeException("cancelled"));
		metrics.afterExecution(cancelled);

		ScriptExecutionEvent second = ScriptExecutionEvent.started(101, "timeout", 1235);
		metrics.beforeExecution(second);
		metrics.afterExecution(ScriptExecutionEvent.completed(second, 4_000_000,
				ScriptExecutionEvent.Status.TIMED_OUT, new RuntimeException("timeout")));

		ScriptExecutionStats stats = metrics.snapshot();
		Assert.assertEquals(1, stats.getCancelledCount());
		Assert.assertEquals(1, stats.getTimedOutCount());
		Assert.assertEquals(6_000_000, stats.getTotalDurationNanos());
		Assert.assertEquals(4_000_000, stats.getMaxDurationNanos());
		Assert.assertEquals(2, stats.getCompletedCount());
		Assert.assertEquals(2, cancelled.getDuration(TimeUnit.MILLISECONDS));
		Assert.assertEquals(1234, cancelled.getStartedAtMillis());
		Assert.assertNotNull(cancelled.getFailure());
		Assert.assertFalse(cancelled.isSuccessful());
	}

	@Test
	public void hostCallLimitShouldHaveDistinctStatusAndBoundaryCounts() {
		ScriptExecutionMetrics metrics = new ScriptExecutionMetrics();
		List<ScriptExecutionEvent> completed = new ArrayList<>();
		ScriptContext context = new ScriptContext().setExecutionLimits(
				ScriptExecutionLimits.builder().maxHostCalls(1).build());
		context.addExecutionListener(metrics);
		context.addExecutionListener(new ScriptExecutionListener() {
			@Override public void afterExecution(ScriptExecutionEvent event) { completed.add(event); }
		});
		try {
			Script.create("var values = [1]; values.size(); return values.size();", null)
					.execute(context);
			Assert.fail("Expected host call limit");
		} catch (ScriptExecutionException expected) {
			Assert.assertEquals(ScriptExecutionException.Reason.HOST_CALL_LIMIT, expected.getReason());
		}
		Assert.assertEquals(ScriptExecutionEvent.Status.HOST_CALL_LIMIT, completed.get(0).getStatus());
		Assert.assertTrue(completed.get(0).getHostAccessCount() >= 2);
		Assert.assertEquals(2, completed.get(0).getHostCallCount());
		Assert.assertEquals(1, metrics.snapshot().getHostCallLimitCount());
	}
}
