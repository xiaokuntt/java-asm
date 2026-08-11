package cn.ykccchen.script;

import cn.ykccchen.script.parsing.Span;

import java.util.Objects;

/**
 * A structured parse or compile diagnostic produced without executing a script.
 */
public final class ScriptDiagnostic {

	public enum Severity {
		ERROR,
		WARNING
	}

	private final String code;
	private final Severity severity;
	private final String message;
	private final String sourceName;
	private final int startOffset;
	private final int endOffset;
	private final int startLine;
	private final int endLine;
	private final int startColumn;
	private final int endColumn;
	private final String snippet;
	private final Throwable cause;

	private ScriptDiagnostic(String code, Severity severity, String message, String sourceName,
			int startOffset, int endOffset, int startLine, int endLine,
			int startColumn, int endColumn, String snippet, Throwable cause) {
		this.code = Objects.requireNonNull(code, "code");
		this.severity = Objects.requireNonNull(severity, "severity");
		this.message = Objects.requireNonNull(message, "message");
		this.sourceName = sourceName;
		this.startOffset = startOffset;
		this.endOffset = endOffset;
		this.startLine = startLine;
		this.endLine = endLine;
		this.startColumn = startColumn;
		this.endColumn = endColumn;
		this.snippet = snippet;
		this.cause = cause;
	}

	static ScriptDiagnostic error(String code, String message, String sourceName, Span span, Throwable cause) {
		if (span == null) {
			return new ScriptDiagnostic(code, Severity.ERROR, message, sourceName,
					-1, -1, -1, -1, -1, -1, null, cause);
		}
		Span.Line line = span.getLine();
		return new ScriptDiagnostic(code, Severity.ERROR, message, sourceName,
				span.getStart(), span.getEnd(), line.getLineNumber(), line.getEndLineNumber(),
				line.getStartCol(), line.getEndCol(), line.getText(), cause);
	}

	public String getCode() {
		return code;
	}

	public Severity getSeverity() {
		return severity;
	}

	public String getMessage() {
		return message;
	}

	public String getSourceName() {
		return sourceName;
	}

	public boolean hasLocation() {
		return startOffset >= 0;
	}

	public int getStartOffset() {
		return startOffset;
	}

	public int getEndOffset() {
		return endOffset;
	}

	public int getStartLine() {
		return startLine;
	}

	public int getEndLine() {
		return endLine;
	}

	public int getStartColumn() {
		return startColumn;
	}

	public int getEndColumn() {
		return endColumn;
	}

	public String getSnippet() {
		return snippet;
	}

	public Throwable getCause() {
		return cause;
	}
}
