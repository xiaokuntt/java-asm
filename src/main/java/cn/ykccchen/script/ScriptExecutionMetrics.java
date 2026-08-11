package cn.ykccchen.script;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free aggregate execution metrics that can be registered as a listener.
 */
public final class ScriptExecutionMetrics implements ScriptExecutionListener {

	private final LongAdder started = new LongAdder();
	private final LongAdder succeeded = new LongAdder();
	private final LongAdder failed = new LongAdder();
	private final LongAdder cancelled = new LongAdder();
	private final LongAdder timedOut = new LongAdder();
	private final LongAdder checkpointLimit = new LongAdder();
	private final LongAdder hostCallLimit = new LongAdder();
	private final LongAdder totalDurationNanos = new LongAdder();
	private final AtomicLong maxDurationNanos = new AtomicLong();

	@Override
	public void beforeExecution(ScriptExecutionEvent event) {
		started.increment();
	}

	@Override
	public void afterExecution(ScriptExecutionEvent event) {
		totalDurationNanos.add(event.getDurationNanos());
		updateMax(event.getDurationNanos());
		switch (event.getStatus()) {
			case SUCCEEDED:
				succeeded.increment();
				break;
			case CANCELLED:
				cancelled.increment();
				break;
			case TIMED_OUT:
				timedOut.increment();
				break;
			case CHECKPOINT_LIMIT:
				checkpointLimit.increment();
				break;
			case HOST_CALL_LIMIT:
				hostCallLimit.increment();
				break;
			case FAILED:
				failed.increment();
				break;
			default:
				break;
		}
	}

	public ScriptExecutionStats snapshot() {
		return new ScriptExecutionStats(started.sum(), succeeded.sum(), failed.sum(),
				cancelled.sum(), timedOut.sum(), checkpointLimit.sum(), hostCallLimit.sum(),
				totalDurationNanos.sum(), maxDurationNanos.get());
	}

	public void reset() {
		started.reset();
		succeeded.reset();
		failed.reset();
		cancelled.reset();
		timedOut.reset();
		checkpointLimit.reset();
		hostCallLimit.reset();
		totalDurationNanos.reset();
		maxDurationNanos.set(0);
	}

	private void updateMax(long durationNanos) {
		long current = maxDurationNanos.get();
		while (durationNanos > current && !maxDurationNanos.compareAndSet(current, durationNanos)) {
			current = maxDurationNanos.get();
		}
	}
}
