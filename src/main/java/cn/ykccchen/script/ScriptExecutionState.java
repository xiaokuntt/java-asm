package cn.ykccchen.script;

import cn.ykccchen.script.exception.ScriptExecutionException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class ScriptExecutionState {

	private static final long TIME_CHECK_MASK = 1023;

	private volatile ScriptExecutionException.Reason terminationReason;
	private final long deadlineNanos;
	private final boolean timeoutEnabled;
	private final long maxCheckpoints;
	private final AtomicLong checkpoints = new AtomicLong();
	private final long maxHostCalls;
	private final AtomicLong hostCalls = new AtomicLong();

	ScriptExecutionState(ScriptExecutionLimits limits) {
		long timeoutMillis = limits.getTimeoutMillis();
		this.timeoutEnabled = timeoutMillis > 0;
		long timeoutNanos = Math.min(TimeUnit.MILLISECONDS.toNanos(timeoutMillis), Long.MAX_VALUE >>> 1);
		this.deadlineNanos = timeoutEnabled ? System.nanoTime() + timeoutNanos : 0;
		this.maxCheckpoints = limits.getMaxCheckpoints();
		this.maxHostCalls = limits.getMaxHostCalls();
	}

	void hostCall(Class<?> owner, String memberName) {
		if (maxHostCalls > 0 && hostCalls.incrementAndGet() > maxHostCalls) {
			throw new ScriptExecutionException(
					ScriptExecutionException.Reason.HOST_CALL_LIMIT,
					"Java宿主调用超过限制：" + maxHostCalls + "，最后调用："
							+ owner.getName() + "." + memberName);
		}
	}

	void checkpoint() {
		ScriptExecutionException.Reason requestedReason = terminationReason;
		if (requestedReason != null || Thread.currentThread().isInterrupted()) {
			ScriptExecutionException.Reason reason = requestedReason == null
					? ScriptExecutionException.Reason.CANCELLED
					: requestedReason;
			throw new ScriptExecutionException(
					reason, reason == ScriptExecutionException.Reason.TIMED_OUT
							? "脚本执行超时"
							: "脚本执行已取消");
		}
		long current = checkpoints.incrementAndGet();
		if (maxCheckpoints > 0 && current > maxCheckpoints) {
			throw new ScriptExecutionException(
					ScriptExecutionException.Reason.CHECKPOINT_LIMIT,
					"脚本执行检查点超过限制：" + maxCheckpoints);
		}
		if (timeoutEnabled
				&& (current <= 8 || (current & TIME_CHECK_MASK) == 0)
				&& System.nanoTime() - deadlineNanos >= 0) {
			throw new ScriptExecutionException(
					ScriptExecutionException.Reason.TIMED_OUT, "脚本执行超时");
		}
	}

	void cancel() {
		requestTermination(ScriptExecutionException.Reason.CANCELLED);
	}

	void timeout() {
		requestTermination(ScriptExecutionException.Reason.TIMED_OUT);
	}

	boolean isCancellationRequested() {
		return terminationReason != null;
	}

	private void requestTermination(ScriptExecutionException.Reason reason) {
		if (terminationReason == null) {
			terminationReason = reason;
		}
	}

}
