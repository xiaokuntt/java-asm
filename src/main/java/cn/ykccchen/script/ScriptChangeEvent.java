package cn.ykccchen.script;

import java.util.Objects;

/**
 * A successful change to a {@link ScriptRepository}.
 */
public final class ScriptChangeEvent {

	public enum Type {
		ADDED,
		UPDATED,
		ROLLED_BACK,
		REMOVED
	}

	private final Type type;
	private final long sequence;
	private final long publishedAtMillis;
	private final String name;
	private final ScriptRevision previousRevision;
	private final ScriptRevision currentRevision;

	ScriptChangeEvent(Type type, String name, ScriptRevision previousRevision, ScriptRevision currentRevision) {
		this(0, System.currentTimeMillis(), type, name, previousRevision, currentRevision);
	}

	ScriptChangeEvent(long sequence, long publishedAtMillis, Type type, String name,
			ScriptRevision previousRevision, ScriptRevision currentRevision) {
		this.sequence = sequence;
		this.publishedAtMillis = publishedAtMillis;
		this.type = Objects.requireNonNull(type, "type");
		this.name = Objects.requireNonNull(name, "name");
		this.previousRevision = previousRevision;
		this.currentRevision = currentRevision;
	}

	public Type getType() {
		return type;
	}

	public long getSequence() { return sequence; }

	public long getPublishedAtMillis() { return publishedAtMillis; }

	public String getName() {
		return name;
	}

	public ScriptRevision getPreviousRevision() {
		return previousRevision;
	}

	public ScriptRevision getCurrentRevision() {
		return currentRevision;
	}
}
