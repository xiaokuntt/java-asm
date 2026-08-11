package cn.ykccchen.script;

import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free aggregate metrics for asynchronous task state events.
 */
public final class ScriptTaskMetrics implements ScriptTaskListener {

	private final LongAdder submitted = new LongAdder();
	private final LongAdder started = new LongAdder();
	private final LongAdder completedStarted = new LongAdder();
	private final LongAdder succeeded = new LongAdder();
	private final LongAdder failed = new LongAdder();
	private final LongAdder cancelled = new LongAdder();
	private final LongAdder timedOut = new LongAdder();
	private final LongAdder rejected = new LongAdder();
	private final LongAdder abandoned = new LongAdder();
	private final LongAdder active = new LongAdder();
	private final LongAdder totalQueueDurationNanos = new LongAdder();
	private final LongAdder totalExecutionDurationNanos = new LongAdder();

	@Override
	public void onStateChanged(ScriptTaskEvent event) {
		switch (event.getState()) {
			case QUEUED:
				submitted.increment();
				break;
			case RUNNING:
				started.increment();
				active.increment();
				totalQueueDurationNanos.add(event.getQueueDurationNanos());
				break;
			case SUCCEEDED:
				succeeded.increment();
				completeStartedTask(event);
				break;
			case FAILED:
				failed.increment();
				completeStartedTask(event);
				break;
			case CANCELLED:
				cancelled.increment();
				completeStartedTask(event);
				break;
			case TIMED_OUT:
				timedOut.increment();
				completeStartedTask(event);
				break;
			case REJECTED:
				rejected.increment();
				break;
			case ABANDONED:
				abandoned.increment();
				completeStartedTask(event);
				break;
			default:
				break;
		}
	}

	public ScriptTaskStats snapshot() {
		return new ScriptTaskStats(submitted.sum(), started.sum(), succeeded.sum(), failed.sum(),
				cancelled.sum(), timedOut.sum(), rejected.sum(), abandoned.sum(), active.sum(),
				completedStarted.sum(), totalQueueDurationNanos.sum(), totalExecutionDurationNanos.sum());
	}

	public void reset() {
		submitted.reset();
		started.reset();
		completedStarted.reset();
		succeeded.reset();
		failed.reset();
		cancelled.reset();
		timedOut.reset();
		rejected.reset();
		abandoned.reset();
		active.reset();
		totalQueueDurationNanos.reset();
		totalExecutionDurationNanos.reset();
	}

	private void completeStartedTask(ScriptTaskEvent event) {
		if (event.getStartedAtMillis() > 0) {
			active.decrement();
			completedStarted.increment();
			totalExecutionDurationNanos.add(event.getExecutionDurationNanos());
		}
	}
}
