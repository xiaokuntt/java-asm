package cn.ykccchen.script.exception;

public class ScriptAssertException extends RuntimeException {

	private int code;

	private String message;

	public ScriptAssertException(int code, String message) {
		this.code = code;
		this.message = message;
	}

	public int getCode() {
		return code;
	}

	@Override
	public String getMessage() {
		return message;
	}

}
