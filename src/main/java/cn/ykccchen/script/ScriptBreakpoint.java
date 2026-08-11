package cn.ykccchen.script;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Immutable breakpoint definition with an optional condition and hit threshold.
 */
public final class ScriptBreakpoint {

	private final int line;
	private final Predicate<Map<String, Object>> condition;
	private final long hitCount;
	private final AtomicLong hits = new AtomicLong();

	private ScriptBreakpoint(Builder builder) {
		this.line = builder.line;
		this.condition = builder.condition;
		this.hitCount = builder.hitCount;
	}

	public static Builder builder(int line) {
		return new Builder(line);
	}

	public static ScriptBreakpoint atLine(int line) {
		return builder(line).build();
	}

	public int getLine() {
		return line;
	}

	public long getHits() {
		return hits.get();
	}

	boolean matches(int currentLine, Map<String, Object> variables) {
		if (line != currentLine || !condition.test(variables)) {
			return false;
		}
		return hits.incrementAndGet() >= hitCount;
	}

	public static final class Builder {
		private final int line;
		private Predicate<Map<String, Object>> condition = variables -> true;
		private long hitCount = 1;

		private Builder(int line) {
			if (line <= 0) {
				throw new IllegalArgumentException("line must be greater than zero");
			}
			this.line = line;
		}

		public Builder condition(Predicate<Map<String, Object>> condition) {
			this.condition = Objects.requireNonNull(condition, "condition");
			return this;
		}

		public Builder hitCount(long hitCount) {
			if (hitCount <= 0) {
				throw new IllegalArgumentException("hitCount must be greater than zero");
			}
			this.hitCount = hitCount;
			return this;
		}

		public ScriptBreakpoint build() {
			return new ScriptBreakpoint(this);
		}
	}
}
