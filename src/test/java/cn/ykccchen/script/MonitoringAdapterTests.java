package cn.ykccchen.script;

import org.junit.Assert;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.ObjectName;

public class MonitoringAdapterTests {

	@Test
	public void jmxMonitoringShouldAggregateAndRegister() throws Exception {
		ScriptMonitoring monitoring = new ScriptMonitoring();
		ScriptExecutionEvent started = ScriptExecutionEvent.started(1, "rule", 100);
		monitoring.beforeExecution(started);
		monitoring.afterExecution(ScriptExecutionEvent.completed(started, 1000,
				ScriptExecutionEvent.Status.SUCCEEDED, null));
		monitoring.onStateChanged(taskEvent(ScriptTaskState.QUEUED, 0, 0));
		monitoring.onStateChanged(taskEvent(ScriptTaskState.RUNNING, 10, 0));
		monitoring.onStateChanged(taskEvent(ScriptTaskState.SUCCEEDED, 10, 20));

		Assert.assertEquals(1, monitoring.getExecutionCompletedCount());
		Assert.assertEquals(1, monitoring.getTaskCompletedCount());
		String value = "cn.ykccchen.script:type=Monitoring,name=Test" + System.nanoTime();
		ObjectName name = new ObjectName(value);
		Registration registration = monitoring.registerMBean(value);
		Assert.assertTrue(ManagementFactory.getPlatformMBeanServer().isRegistered(name));
		registration.close();
		Assert.assertFalse(ManagementFactory.getPlatformMBeanServer().isRegistered(name));
		monitoring.reset();
		Assert.assertEquals(0, monitoring.getExecutionStartedCount());
	}

	@Test
	public void micrometerAdapterShouldRecordCountersAndTimers() {
		FakeMeterRegistry registry = new FakeMeterRegistry();
		MicrometerScriptMetrics metrics = new MicrometerScriptMetrics(registry);
		ScriptExecutionEvent started = ScriptExecutionEvent.started(2, "rule", 100);
		metrics.beforeExecution(started);
		metrics.afterExecution(ScriptExecutionEvent.completed(started, 2000,
				ScriptExecutionEvent.Status.SUCCEEDED, null));
		metrics.onStateChanged(taskEvent(ScriptTaskState.RUNNING, 10, 0));
		metrics.onStateChanged(taskEvent(ScriptTaskState.SUCCEEDED, 10, 20));

		Assert.assertEquals(1, registry.counters.get("script.execution.started").count.get());
		Assert.assertEquals(2000, registry.timers.get("script.execution.duration").total.get());
		Assert.assertEquals(0, metrics.getDroppedMeasurements());
	}

	@Test
	public void openTelemetryAdapterShouldBuildAndRecordInstruments() {
		FakeOpenTelemetry telemetry = new FakeOpenTelemetry();
		OpenTelemetryScriptMetrics metrics = OpenTelemetryScriptMetrics.fromOpenTelemetry(
				telemetry, "script-test");
		ScriptExecutionEvent started = ScriptExecutionEvent.started(3, "rule", 100);
		metrics.beforeExecution(started);
		metrics.afterExecution(ScriptExecutionEvent.completed(started, 3000,
				ScriptExecutionEvent.Status.FAILED, new RuntimeException("failed")));
		metrics.onStateChanged(taskEvent(ScriptTaskState.REJECTED, 0, 20));

		Assert.assertEquals(1, telemetry.meter.counters.get("script.execution.started").value.get());
		Assert.assertEquals(3000, telemetry.meter.histograms.get("script.execution.duration").value.get());
		Assert.assertEquals(0, metrics.getDroppedMeasurements());
	}

	@Test
	public void incompatibleAdaptersShouldOpenCircuitAfterFirstReflectionFailure() {
		MicrometerScriptMetrics micrometer = new MicrometerScriptMetrics(new Object());
		ScriptExecutionEvent started = ScriptExecutionEvent.started(4, "rule", 100);
		micrometer.beforeExecution(started);
		micrometer.beforeExecution(started);
		Assert.assertTrue(micrometer.isDisabled());
		Assert.assertEquals(1, micrometer.getDroppedMeasurements());

		OpenTelemetryScriptMetrics telemetry = new OpenTelemetryScriptMetrics(new Object());
		telemetry.beforeExecution(started);
		telemetry.beforeExecution(started);
		Assert.assertTrue(telemetry.isDisabled());
		Assert.assertEquals(1, telemetry.getDroppedMeasurements());
	}

	@Test
	public void monitoringAdaptersShouldUseEngineScopedFutureBridgeStats() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ScriptEngineConfig config = ScriptEngineConfig.builder()
				.languageBlockingThreads(1)
				.languageBlockingQueueCapacity(1)
				.build();
		JvmScriptEngine engine = new JvmScriptEngine(new ScriptEngineFactory(config), config);
		try {
			engine.getLanguageBlockingExecutor().submit(() -> {
				entered.countDown();
				release.await();
				return null;
			});
			Assert.assertTrue(entered.await(2, TimeUnit.SECONDS));

			ScriptMonitoring monitoring = new ScriptMonitoring(engine);
			Assert.assertEquals(1, monitoring.getFutureBridgePoolSize());
			Assert.assertEquals(1, monitoring.getFutureBridgeActiveCount());
			Assert.assertEquals(0, monitoring.getFutureBridgeQueueSize());

			FakeMeterRegistry registry = new FakeMeterRegistry();
			MicrometerScriptMetrics micrometer = new MicrometerScriptMetrics(registry, engine);
			micrometer.refreshFutureBridgeMetrics();
			Assert.assertEquals(1L, registry.gauges.get("script.future.bridge.active").longValue());
			Assert.assertFalse(micrometer.isFutureBridgeMetricsDisabled());

			FakeOpenTelemetry telemetry = new FakeOpenTelemetry();
			OpenTelemetryScriptMetrics openTelemetry = OpenTelemetryScriptMetrics.fromOpenTelemetry(
					telemetry, "script-test", engine);
			openTelemetry.refreshFutureBridgeMetrics();
			Assert.assertEquals(1L, telemetry.meter.histograms
					.get("script.future.bridge.active").value.get());
		} finally {
			release.countDown();
			engine.close();
		}
	}

	private static ScriptTaskEvent taskEvent(ScriptTaskState state, long started, long completed) {
		return new ScriptTaskEvent(1, state, "rule", 1, "correlation", 1,
				started, completed, 5, 10, null);
	}

	public static final class FakeMeterRegistry {
		final Map<String, FakeCounter> counters = new ConcurrentHashMap<>();
		final Map<String, FakeTimer> timers = new ConcurrentHashMap<>();
		final Map<String, Number> gauges = new ConcurrentHashMap<>();
		public FakeCounter counter(String name, String... tags) {
			return counters.computeIfAbsent(name, ignored -> new FakeCounter());
		}
		public FakeTimer timer(String name, String... tags) {
			return timers.computeIfAbsent(name, ignored -> new FakeTimer());
		}
		public <T extends Number> T gauge(String name, T value) {
			gauges.put(name, value);
			return value;
		}
	}

	public static final class FakeCounter {
		final AtomicLong count = new AtomicLong();
		public void increment() { count.incrementAndGet(); }
	}

	public static final class FakeTimer {
		final AtomicLong total = new AtomicLong();
		public void record(long amount, TimeUnit unit) { total.addAndGet(unit.toNanos(amount)); }
	}

	public static final class FakeOpenTelemetry {
		final FakeMeter meter = new FakeMeter();
		public FakeMeter getMeter(String name) { return meter; }
	}

	public static final class FakeMeter {
		final Map<String, FakeLongCounter> counters = new ConcurrentHashMap<>();
		final Map<String, FakeLongHistogram> histograms = new ConcurrentHashMap<>();
		public FakeCounterBuilder counterBuilder(String name) { return new FakeCounterBuilder(name, this); }
		public FakeHistogramBuilder histogramBuilder(String name) { return new FakeHistogramBuilder(name, this); }
	}

	public static final class FakeCounterBuilder {
		private final String name;
		private final FakeMeter meter;
		FakeCounterBuilder(String name, FakeMeter meter) { this.name = name; this.meter = meter; }
		public FakeLongCounter build() {
			return meter.counters.computeIfAbsent(name, ignored -> new FakeLongCounter());
		}
	}

	public static final class FakeHistogramBuilder {
		private final String name;
		private final FakeMeter meter;
		FakeHistogramBuilder(String name, FakeMeter meter) { this.name = name; this.meter = meter; }
		public FakeHistogramBuilder setUnit(String unit) { return this; }
		public FakeHistogramBuilder ofLongs() { return this; }
		public FakeLongHistogram build() {
			return meter.histograms.computeIfAbsent(name, ignored -> new FakeLongHistogram());
		}
	}

	public static final class FakeLongCounter {
		final AtomicLong value = new AtomicLong();
		public void add(long amount) { value.addAndGet(amount); }
	}

	public static final class FakeLongHistogram {
		final AtomicLong value = new AtomicLong();
		public void record(long amount) { value.addAndGet(amount); }
	}
}
