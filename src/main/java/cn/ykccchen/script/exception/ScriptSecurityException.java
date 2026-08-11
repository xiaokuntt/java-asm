package cn.ykccchen.script.exception;

public class ScriptSecurityException extends ScriptRuntimeException {
	private static final long serialVersionUID = 1L;

	public ScriptSecurityException(String message) {
		super(ScriptErrorCode.SCRIPT_SECURITY_ERROR, message);
	}
}
