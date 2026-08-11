package cn.ykccchen.script;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class ScriptEngineFactory implements javax.script.ScriptEngineFactory {
	private final ScriptEngineConfig config;

	public ScriptEngineFactory() {
		this(null);
	}

	public ScriptEngineFactory(ScriptEngineConfig config) {
		this.config = config;
	}

	@Override
	public String getEngineName() {
		return "Script";
	}

	@Override
	public String getEngineVersion() {
		return Objects.toString(ScriptEngineFactory.class.getPackage().getImplementationVersion(), "unknown");
	}

	@Override
	public List<String> getExtensions() {
		return Collections.singletonList("ms");
	}

	@Override
	public List<String> getMimeTypes() {
		return Collections.singletonList("application/script");
	}

	@Override
	public List<String> getNames() {
		return Arrays.asList("Script", "script");
	}

	@Override
	public String getLanguageName() {
		return "Script";
	}

	@Override
	public String getLanguageVersion() {
		return Objects.toString(ScriptEngineFactory.class.getPackage().getImplementationVersion(), "unknown");
	}

	@Override
	public Object getParameter(String key) {
		if (javax.script.ScriptEngine.ENGINE.equals(key)) {
			return getEngineName();
		} else if (javax.script.ScriptEngine.ENGINE_VERSION.equals(key)) {
			return getEngineVersion();
		} else if (javax.script.ScriptEngine.LANGUAGE_VERSION.equals(key)) {
			return getLanguageVersion();
		} else if (javax.script.ScriptEngine.LANGUAGE.equals(key)) {
			return getLanguageName();
		} else if ("THREADING".equals(key)) {
			return null;
		}
		throw new IllegalArgumentException("Invalid key:" + key);
	}

	@Override
	public String getMethodCallSyntax(String obj, String m, String... args) {
		return obj + "." + m + "(" + String.join(", ", args) + ")";
	}

	@Override
	public String getOutputStatement(String toDisplay) {
		String escaped = toDisplay
				.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\r", "\\r")
				.replace("\n", "\\n");
		return "System.out.println(\"" + escaped + "\");";
	}

	@Override
	public String getProgram(String... statements) {
		StringBuilder program = new StringBuilder();
		for (String statement : statements) {
			if (statement == null || statement.trim().isEmpty()) {
				continue;
			}
			program.append(statement);
			if (!statement.trim().endsWith(";")) {
				program.append(';');
			}
			program.append(System.lineSeparator());
		}
		return program.toString();
	}

	@Override
	public javax.script.ScriptEngine getScriptEngine() {
		return new JvmScriptEngine(this, config);
	}
}
