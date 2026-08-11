package cn.ykccchen.script;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Immutable limits and failure semantics for one language-level async scope.
 */
public final class ScriptAsyncPolicy {

	private static final ScriptAsyncPolicy DEFAULT = builder().build();

	private final int maxChildTasks;
	private final long scopeJoinTimeoutMillis;
	private final ScriptAsyncFailurePolicy failurePolicy;
	private final boolean cancelRaceLosers;
	private final long cancellationJoinTimeoutMillis;
	private final ScriptNestedAsyncPolicy nestedAsyncPolicy;

	private ScriptAsyncPolicy(Builder builder) {
		this.maxChildTasks = builder.maxChildTasks;
		this.scopeJoinTimeoutMillis = builder.scopeJoinTimeoutMillis;
		this.failurePolicy = builder.failurePolicy;
		this.cancelRaceLosers = builder.cancelRaceLosers;
		this.cancellationJoinTimeoutMillis = builder.cancellationJoinTimeoutMillis;
		this.nestedAsyncPolicy = builder.nestedAsyncPolicy;
	}

	public static ScriptAsyncPolicy defaults() {
		return DEFAULT;
	}

	public static Builder builder() {
		return new Builder();
	}

	/** Zero means unlimited. */
	public int getMaxChildTasks() {
		return maxChildTasks;
	}

	/** Zero means wait without a deadline. */
	public long getScopeJoinTimeoutMillis() {
		return scopeJoinTimeoutMillis;
	}

	public ScriptAsyncFailurePolicy getFailurePolicy() {
		return failurePolicy;
	}

	/** Whether a completed {@code Async.race} cancels unfinished inputs. */
	public boolean isCancelRaceLosers() {
		return cancelRaceLosers;
	}

	/** Maximum cleanup wait after cancellation before remaining children are abandoned. */
	public long getCancellationJoinTimeoutMillis() { return cancellationJoinTimeoutMillis; }

	public ScriptNestedAsyncPolicy getNestedAsyncPolicy() { return nestedAsyncPolicy; }

	public static final class Builder {
		private int maxChildTasks;
		private long scopeJoinTimeoutMillis;
		private ScriptAsyncFailurePolicy failurePolicy = ScriptAsyncFailurePolicy.WAIT_ALL;
		private boolean cancelRaceLosers = true;
		private long cancellationJoinTimeoutMillis = 5_000;
		private ScriptNestedAsyncPolicy nestedAsyncPolicy = ScriptNestedAsyncPolicy.AUTO;

		private Builder() {
		}

		public Builder maxChildTasks(int maxChildTasks) {
			if (maxChildTasks < 0) {
				throw new IllegalArgumentException("maxChildTasks must not be negative");
			}
			this.maxChildTasks = maxChildTasks;
			return this;
		}

		public Builder scopeJoinTimeout(long timeout, TimeUnit unit) {
			if (timeout < 0) {
				throw new IllegalArgumentException("timeout must not be negative");
			}
			if (timeout == 0) {
				this.scopeJoinTimeoutMillis = 0;
				return this;
			}
			long millis = Objects.requireNonNull(unit, "unit").toMillis(timeout);
			if (millis <= 0) {
				throw new IllegalArgumentException("timeout must be at least one millisecond");
			}
			this.scopeJoinTimeoutMillis = millis;
			return this;
		}

		public Builder failurePolicy(ScriptAsyncFailurePolicy failurePolicy) {
			this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
			return this;
		}

		public Builder cancelRaceLosers(boolean cancelRaceLosers) {
			this.cancelRaceLosers = cancelRaceLosers;
			return this;
		}

		public Builder cancellationJoinTimeout(long timeout, TimeUnit unit) {
			if (timeout <= 0) {
				throw new IllegalArgumentException("timeout must be greater than zero");
			}
			long millis = Objects.requireNonNull(unit, "unit").toMillis(timeout);
			if (millis <= 0) {
				throw new IllegalArgumentException("timeout must be at least one millisecond");
			}
			this.cancellationJoinTimeoutMillis = millis;
			return this;
		}

		public Builder nestedAsyncPolicy(ScriptNestedAsyncPolicy policy) {
			this.nestedAsyncPolicy = Objects.requireNonNull(policy, "policy");
			return this;
		}

		public ScriptAsyncPolicy build() {
			return new ScriptAsyncPolicy(this);
		}
	}
}
