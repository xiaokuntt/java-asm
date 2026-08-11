package cn.ykccchen.script.exception;

public class ScriptRuntimeException extends RuntimeException {
	private static final long serialVersionUID = 1L;
	private final ScriptErrorCode errorCode;

	public ScriptRuntimeException() {
		this(ScriptErrorCode.SCRIPT_RUNTIME_ERROR, null, null);
	}

	public ScriptRuntimeException(String message) {
		this(ScriptErrorCode.SCRIPT_RUNTIME_ERROR, message, null);
	}

	public ScriptRuntimeException(String message, Throwable cause) {
		this(ScriptErrorCode.SCRIPT_RUNTIME_ERROR, message, cause);
	}

	public ScriptRuntimeException(Throwable cause) {
		this(ScriptErrorCode.SCRIPT_RUNTIME_ERROR, cause == null ? null : cause.getMessage(), cause);
	}

	public ScriptRuntimeException(ScriptErrorCode errorCode, String message) {
		this(errorCode, message, null);
	}

	public ScriptRuntimeException(ScriptErrorCode errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode == null ? ScriptErrorCode.SCRIPT_RUNTIME_ERROR : errorCode;
	}

	public ScriptErrorCode getErrorCode() {
		return errorCode;
	}

	public static ScriptRuntimeException create(Object target){
		if(target instanceof Throwable){
			return new ScriptRuntimeException((Throwable) target);
		}else if(target instanceof String){
			return new ScriptRuntimeException(target.toString());
		}
		return new ScriptRuntimeException();
	}
}
