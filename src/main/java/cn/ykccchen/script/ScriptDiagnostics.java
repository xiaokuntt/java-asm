package cn.ykccchen.script;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable result returned by {@link Script#validate(String, String)}.
 */
public final class ScriptDiagnostics {

	private final String sourceName;
	private final List<ScriptDiagnostic> diagnostics;

	private ScriptDiagnostics(String sourceName, List<ScriptDiagnostic> diagnostics) {
		this.sourceName = sourceName;
		this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
	}

	static ScriptDiagnostics valid(String sourceName) {
		return new ScriptDiagnostics(sourceName, Collections.emptyList());
	}

	static ScriptDiagnostics of(String sourceName, ScriptDiagnostic diagnostic) {
		return new ScriptDiagnostics(sourceName, Collections.singletonList(diagnostic));
	}

	public String getSourceName() {
		return sourceName;
	}

	public List<ScriptDiagnostic> getDiagnostics() {
		return diagnostics;
	}

	public List<ScriptDiagnostic> getErrors() {
		List<ScriptDiagnostic> errors = new ArrayList<>();
		for (ScriptDiagnostic diagnostic : diagnostics) {
			if (diagnostic.getSeverity() == ScriptDiagnostic.Severity.ERROR) {
				errors.add(diagnostic);
			}
		}
		return Collections.unmodifiableList(errors);
	}

	public List<ScriptDiagnostic> getWarnings() {
		List<ScriptDiagnostic> warnings = new ArrayList<>();
		for (ScriptDiagnostic diagnostic : diagnostics) {
			if (diagnostic.getSeverity() == ScriptDiagnostic.Severity.WARNING) {
				warnings.add(diagnostic);
			}
		}
		return Collections.unmodifiableList(warnings);
	}

	public boolean hasErrors() {
		for (ScriptDiagnostic diagnostic : diagnostics) {
			if (diagnostic.getSeverity() == ScriptDiagnostic.Severity.ERROR) {
				return true;
			}
		}
		return false;
	}

	public boolean isValid() {
		return !hasErrors();
	}
}
