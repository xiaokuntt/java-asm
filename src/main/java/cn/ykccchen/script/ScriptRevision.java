package cn.ykccchen.script;

import java.util.Objects;

/**
 * Immutable, compiled revision of a named script.
 */
public final class ScriptRevision {

	private final String name;
	private final String source;
	private final long version;
	private final long loadedAtMillis;
	private final Script script;

	ScriptRevision(String name, String source, long version, long loadedAtMillis, Script script) {
		this.name = Objects.requireNonNull(name, "name");
		this.source = Objects.requireNonNull(source, "source");
		this.version = version;
		this.loadedAtMillis = loadedAtMillis;
		this.script = Objects.requireNonNull(script, "script");
	}

	public String getName() {
		return name;
	}

	public String getSource() {
		return source;
	}

	public long getVersion() {
		return version;
	}

	public long getLoadedAtMillis() {
		return loadedAtMillis;
	}

	public Script getScript() {
		return script;
	}

	/**
	 * Execute this exact immutable revision, even if the repository has since
	 * published a newer version.
	 */
	public Object execute(ScriptContext context) {
		ScriptContext requiredContext = Objects.requireNonNull(context, "context");
		String previousName = requiredContext.getScriptName();
		requiredContext.setScriptName(name);
		try {
			return script.execute(requiredContext);
		} finally {
			requiredContext.setScriptName(previousName);
		}
	}
}
