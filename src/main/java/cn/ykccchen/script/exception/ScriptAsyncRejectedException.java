package cn.ykccchen.script.exception;

import java.util.concurrent.RejectedExecutionException;

/** Rejection with a stable script error code while preserving JDK compatibility. */
public final class ScriptAsyncRejectedException extends RejectedExecutionException {
	private static final long serialVersionUID = 1L;

	public ScriptAsyncRejectedException(String message) {
		super(message);
	}

	public ScriptAsyncRejectedException(String message, Throwable cause) {
		super(message, cause);
	}

	public ScriptErrorCode getErrorCode() {
		return ScriptErrorCode.SCRIPT_ASYNC_REJECTED;
	}
}
