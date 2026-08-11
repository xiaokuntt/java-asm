package cn.ykccchen.script.exception;

import cn.ykccchen.script.parsing.Span;

public class ScriptEvaluationException extends RuntimeException {
	private static final long serialVersionUID = 1L;
	private String simpleMessage;
	private Span location;
	private final ScriptErrorCode errorCode;
	private final String sourceName;

	public ScriptEvaluationException(String errorMessage, String simpleMessage, Span location) {
		this(ScriptErrorCode.SCRIPT_RUNTIME_ERROR, errorMessage, simpleMessage, null, location, null);
	}

	public ScriptEvaluationException(String errorMessage, Span location) {
		this(errorMessage, errorMessage, location);
	}

	public ScriptEvaluationException(String errorMessage) {
		this(errorMessage, errorMessage, null);
	}

	public ScriptEvaluationException(String message, String simpleMessage, Throwable cause, Span location) {
		this(ScriptErrorCode.SCRIPT_RUNTIME_ERROR, message, simpleMessage, cause, location, null);
	}

	public ScriptEvaluationException(ScriptErrorCode errorCode, String message, String simpleMessage,
			Throwable cause, Span location, String sourceName) {
		super(message, cause);
		this.simpleMessage = simpleMessage;
		this.location = location;
		this.errorCode = errorCode == null ? ScriptErrorCode.SCRIPT_RUNTIME_ERROR : errorCode;
		this.sourceName = sourceName;
	}

	public ScriptErrorCode getErrorCode() {
		return errorCode;
	}

	public String getSourceName() {
		return sourceName;
	}

	public String getSimpleMessage() {
		return simpleMessage;
	}

	public Span getLocation() {
		return location;
	}

	public Span.Line getLine() {
		return location == null ? null : location.getLine();
	}
}
