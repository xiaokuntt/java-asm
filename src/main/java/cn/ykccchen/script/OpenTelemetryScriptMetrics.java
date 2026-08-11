package cn.ykccchen.script;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Reflection-backed adapter for an OpenTelemetry API {@code Meter}. */
public final class OpenTelemetryScriptMetrics implements ScriptExecutionListener, ScriptTaskListener {

	private final Object meter;
	private final ConcurrentMap<String, Object> counters = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, Object> histograms = new ConcurrentHashMap<>();
	private final Supplier<ScriptAsyncRuntimeStats> futureBridgeStats;
	private final LongAdder droppedMeasurements = new LongAdder();
	private final AtomicBoolean disabled = new AtomicBoolean();

	public OpenTelemetryScriptMetrics(Object meter) {
		this(meter, null);
	}

	/** Creates an adapter that also samples one engine's Future bridge state. */
	public OpenTelemetryScriptMetrics(Object meter, JvmScriptEngine engine) {
		this.meter = Objects.requireNonNull(meter, "meter");
		this.futureBridgeStats = engine == null ? null
				: Objects.requireNonNull(engine, "engine")::getFutureBridgeStats;
	}

	public static OpenTelemetryScriptMetrics fromOpenTelemetry(Object openTelemetry,
			String instrumentationName) {
		return fromOpenTelemetry(openTelemetry, instrumentationName, null);
	}

	public static OpenTelemetryScriptMetrics fromOpenTelemetry(Object openTelemetry,
			String instrumentationName, JvmScriptEngine engine) {
		try {
			Object meter = MonitoringReflection.invoke(Objects.requireNonNull(openTelemetry, "openTelemetry"),
					"getMeter", Objects.requireNonNull(instrumentationName, "instrumentationName"));
			return new OpenTelemetryScriptMetrics(meter, engine);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalArgumentException("Object does not expose OpenTelemetry.getMeter(String)", exception);
		}
	}

	@Override public void beforeExecution(ScriptExecutionEvent event) {
		add("script.execution.started");
		refreshFutureBridgeMetrics();
	}

	@Override
	public void afterExecution(ScriptExecutionEvent event) {
		add("script.execution." + event.getStatus().name().toLowerCase(Locale.ROOT));
		record("script.execution.duration", event.getDurationNanos());
		refreshFutureBridgeMetrics();
	}

	@Override
	public void onStateChanged(ScriptTaskEvent event) {
		add("script.task." + event.getState().name().toLowerCase(Locale.ROOT));
		if (event.getState() == ScriptTaskState.RUNNING) {
			record("script.task.queue.duration", event.getQueueDurationNanos());
		} else if (event.getCompletedAtMillis() > 0 && event.getStartedAtMillis() > 0) {
			record("script.task.execution.duration", event.getExecutionDurationNanos());
		}
		refreshFutureBridgeMetrics();
	}

	public long getDroppedMeasurements() { return droppedMeasurements.sum(); }
	public boolean isDisabled() { return disabled.get(); }

	/** Records a point-in-time sample of the bound engine's Future bridge. */
	public void refreshFutureBridgeMetrics() {
		if (futureBridgeStats == null || disabled.get()) return;
		try {
			ScriptAsyncRuntimeStats stats = Objects.requireNonNull(
					futureBridgeStats.get(), "futureBridgeStats returned null");
			recordIfAvailable("script.future.bridge.pool.size", stats.getBridgePoolSize());
			recordIfAvailable("script.future.bridge.active", stats.getBridgeActiveCount());
			recordIfAvailable("script.future.bridge.queue.size", stats.getBridgeQueueSize());
			recordIfAvailable("script.future.bridge.completed", stats.getBridgeCompletedCount());
			recordIfAvailable("script.future.bridge.rejected", stats.getBridgeRejectedCount());
			recordIfAvailable("script.future.bridge.abandoned", stats.getAbandonedTaskCount());
		} catch (RuntimeException exception) {
			droppedMeasurements.increment();
		}
	}

	private void recordIfAvailable(String name, long value) {
		if (value >= 0) record(name, value);
	}

	private void add(String name) {
		if (disabled.get()) return;
		try {
			Object instrument = counters.get(name);
			if (instrument == null) {
				Object builder = MonitoringReflection.invoke(meter, "counterBuilder", name);
				Object created = MonitoringReflection.invoke(builder, "build");
				Object raced = counters.putIfAbsent(name, created);
				instrument = raced == null ? created : raced;
			}
			MonitoringReflection.invoke(instrument, "add", 1L);
		} catch (ReflectiveOperationException exception) {
			disabled.set(true);
			droppedMeasurements.increment();
		} catch (RuntimeException exception) {
			droppedMeasurements.increment();
		}
	}

	private void record(String name, long value) {
		if (disabled.get()) return;
		try {
			Object instrument = histograms.get(name);
			if (instrument == null) {
				Object builder = MonitoringReflection.invoke(meter, "histogramBuilder", name);
				builder = MonitoringReflection.invoke(builder, "setUnit", "ns");
				builder = MonitoringReflection.invoke(builder, "ofLongs");
				Object created = MonitoringReflection.invoke(builder, "build");
				Object raced = histograms.putIfAbsent(name, created);
				instrument = raced == null ? created : raced;
			}
			MonitoringReflection.invoke(instrument, "record", value);
		} catch (ReflectiveOperationException exception) {
			disabled.set(true);
			droppedMeasurements.increment();
		} catch (RuntimeException exception) {
			droppedMeasurements.increment();
		}
	}
}
