package cn.ykccchen.script.compile;

public class ScriptCompileException extends RuntimeException{

	public ScriptCompileException(Throwable cause) {
		super(cause);
	}

	public ScriptCompileException(String message, Throwable cause) {
		super(message, cause);
	}

	public ScriptCompileException(String message) {
		super(message);
	}
}
