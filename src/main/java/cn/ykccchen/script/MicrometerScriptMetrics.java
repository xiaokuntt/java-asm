package cn.ykccchen.script;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Optional Micrometer adapter without a hard dependency on Micrometer. Pass an
 * {@code io.micrometer.core.instrument.MeterRegistry} instance to the constructor.
 */
public final class MicrometerScriptMetrics implements ScriptExecutionListener, ScriptTaskListener {

	private final Object registry;
	private final ConcurrentMap<String, Object> meters = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, AtomicLong> gauges = new ConcurrentHashMap<>();
	private final Supplier<ScriptAsyncRuntimeStats> futureBridgeStats;
	private final LongAdder droppedMeasurements = new LongAdder();
	private final AtomicBoolean disabled = new AtomicBoolean();
	private final AtomicBoolean futureBridgeMetricsDisabled = new AtomicBoolean();

	public MicrometerScriptMetrics(Object meterRegistry) {
		this(meterRegistry, null);
	}

	/** Creates an adapter that also publishes gauges for one engine's Future bridge. */
	public MicrometerScriptMetrics(Object meterRegistry, JvmScriptEngine engine) {
		this.registry = Objects.requireNonNull(meterRegistry, "meterRegistry");
		this.futureBridgeStats = engine == null ? null
				: Objects.requireNonNull(engine, "engine")::getFutureBridgeStats;
	}

	@Override public void beforeExecution(ScriptExecutionEvent event) {
		increment("script.execution.started");
		refreshFutureBridgeMetrics();
	}

	@Override
	public void afterExecution(ScriptExecutionEvent event) {
		increment("script.execution." + event.getStatus().name().toLowerCase(Locale.ROOT));
		record("script.execution.duration", event.getDurationNanos());
		refreshFutureBridgeMetrics();
	}

	@Override
	public void onStateChanged(ScriptTaskEvent event) {
		increment("script.task." + event.getState().name().toLowerCase(Locale.ROOT));
		if (event.getState() == ScriptTaskState.RUNNING) {
			record("script.task.queue.duration", event.getQueueDurationNanos());
		} else if (event.getCompletedAtMillis() > 0 && event.getStartedAtMillis() > 0) {
			record("script.task.execution.duration", event.getExecutionDurationNanos());
		}
		refreshFutureBridgeMetrics();
	}

	public long getDroppedMeasurements() { return droppedMeasurements.sum(); }
	public boolean isDisabled() { return disabled.get(); }
	public boolean isFutureBridgeMetricsDisabled() { return futureBridgeMetricsDisabled.get(); }

	/** Refreshes engine-scoped Future bridge gauges without affecting core metrics on failure. */
	public void refreshFutureBridgeMetrics() {
		if (futureBridgeStats == null || futureBridgeMetricsDisabled.get()) return;
		try {
			ScriptAsyncRuntimeStats stats = Objects.requireNonNull(
					futureBridgeStats.get(), "futureBridgeStats returned null");
			gauge("script.future.bridge.pool.size", stats.getBridgePoolSize());
			gauge("script.future.bridge.active", stats.getBridgeActiveCount());
			gauge("script.future.bridge.queue.size", stats.getBridgeQueueSize());
			gauge("script.future.bridge.completed", stats.getBridgeCompletedCount());
			gauge("script.future.bridge.rejected", stats.getBridgeRejectedCount());
			gauge("script.future.bridge.abandoned", stats.getAbandonedTaskCount());
		} catch (ReflectiveOperationException | RuntimeException exception) {
			futureBridgeMetricsDisabled.set(true);
			droppedMeasurements.increment();
		}
	}

	private void gauge(String name, long value) throws ReflectiveOperationException {
		AtomicLong state = gauges.get(name);
		if (state == null) {
			synchronized (gauges) {
				state = gauges.get(name);
				if (state == null) {
					state = new AtomicLong(value);
					MonitoringReflection.invoke(registry, "gauge", name, state);
					gauges.put(name, state);
				}
			}
		}
		state.set(value);
	}

	private void increment(String name) {
		if (disabled.get()) return;
		try {
			Object counter = meter(name, true);
			MonitoringReflection.invoke(counter, "increment");
		} catch (ReflectiveOperationException exception) {
			disabled.set(true);
			droppedMeasurements.increment();
		} catch (RuntimeException exception) {
			droppedMeasurements.increment();
		}
	}

	private void record(String name, long durationNanos) {
		if (disabled.get()) return;
		try {
			Object timer = meter(name, false);
			MonitoringReflection.invoke(timer, "record", durationNanos, TimeUnit.NANOSECONDS);
		} catch (ReflectiveOperationException exception) {
			disabled.set(true);
			droppedMeasurements.increment();
		} catch (RuntimeException exception) {
			droppedMeasurements.increment();
		}
	}

	private Object meter(String name, boolean counter) throws ReflectiveOperationException {
		Object existing = meters.get(name);
		if (existing != null) return existing;
		Object created = MonitoringReflection.invoke(registry, counter ? "counter" : "timer",
				name, new String[0]);
		Object raced = meters.putIfAbsent(name, created);
		return raced == null ? created : raced;
	}
}
