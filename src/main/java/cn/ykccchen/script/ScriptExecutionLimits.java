package cn.ykccchen.script;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Cooperative limits enforced at generated script checkpoints.
 */
public final class ScriptExecutionLimits {

	private static final ScriptExecutionLimits UNLIMITED = new ScriptExecutionLimits(false, 0, 0, 0);
	private static final ScriptExecutionLimits CANCELLABLE = new ScriptExecutionLimits(true, 0, 0, 0);

	private final boolean cancellationEnabled;
	private final long timeoutMillis;
	private final long maxCheckpoints;
	private final long maxHostCalls;

	private ScriptExecutionLimits(boolean cancellationEnabled, long timeoutMillis, long maxCheckpoints,
			long maxHostCalls) {
		this.cancellationEnabled = cancellationEnabled;
		this.timeoutMillis = timeoutMillis;
		this.maxCheckpoints = maxCheckpoints;
		this.maxHostCalls = maxHostCalls;
	}

	public static ScriptExecutionLimits unlimited() {
		return UNLIMITED;
	}

	public static ScriptExecutionLimits cancellable() {
		return CANCELLABLE;
	}

	public static Builder builder() {
		return new Builder();
	}

	public long getTimeoutMillis() {
		return timeoutMillis;
	}

	public boolean isCancellationEnabled() {
		return cancellationEnabled;
	}

	public long getMaxCheckpoints() {
		return maxCheckpoints;
	}

	public long getMaxHostCalls() {
		return maxHostCalls;
	}

	public boolean isUnlimited() {
		return !cancellationEnabled && timeoutMillis == 0 && maxCheckpoints == 0 && maxHostCalls == 0;
	}

	public static final class Builder {
		private long timeoutMillis;
		private long maxCheckpoints;
		private boolean cancellationEnabled;
		private long maxHostCalls;

		private Builder() {
		}

		public Builder timeout(long timeout, TimeUnit unit) {
			if (timeout <= 0) {
				throw new IllegalArgumentException("timeout must be greater than zero");
			}
			this.timeoutMillis = Objects.requireNonNull(unit, "unit").toMillis(timeout);
			if (timeoutMillis <= 0) {
				throw new IllegalArgumentException("timeout must be at least one millisecond");
			}
			this.cancellationEnabled = true;
			return this;
		}

		public Builder maxCheckpoints(long maxCheckpoints) {
			if (maxCheckpoints <= 0) {
				throw new IllegalArgumentException("maxCheckpoints must be greater than zero");
			}
			this.maxCheckpoints = maxCheckpoints;
			this.cancellationEnabled = true;
			return this;
		}

		public Builder maxHostCalls(long maxHostCalls) {
			if (maxHostCalls <= 0) {
				throw new IllegalArgumentException("maxHostCalls must be greater than zero");
			}
			this.maxHostCalls = maxHostCalls;
			this.cancellationEnabled = true;
			return this;
		}

		public ScriptExecutionLimits build() {
			if (!cancellationEnabled && timeoutMillis == 0 && maxCheckpoints == 0 && maxHostCalls == 0) {
				return unlimited();
			}
			if (cancellationEnabled && timeoutMillis == 0 && maxCheckpoints == 0 && maxHostCalls == 0) {
				return cancellable();
			}
			return new ScriptExecutionLimits(cancellationEnabled, timeoutMillis, maxCheckpoints, maxHostCalls);
		}
	}
}
