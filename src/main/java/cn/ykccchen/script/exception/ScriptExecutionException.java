package cn.ykccchen.script.exception;

/**
 * Signals cooperative cancellation or an execution resource limit.
 */
public class ScriptExecutionException extends ScriptRuntimeException {

	private static final long serialVersionUID = 1L;

	public enum Reason {
		CANCELLED,
		TIMED_OUT,
		CHECKPOINT_LIMIT,
		HOST_CALL_LIMIT
	}

	private final Reason reason;

	public ScriptExecutionException(Reason reason, String message) {
		super(toErrorCode(reason), message);
		this.reason = reason;
	}

	public Reason getReason() {
		return reason;
	}

	private static ScriptErrorCode toErrorCode(Reason reason) {
		switch (reason) {
			case CANCELLED:
				return ScriptErrorCode.SCRIPT_CANCELLED;
			case TIMED_OUT:
				return ScriptErrorCode.SCRIPT_TIMED_OUT;
			case CHECKPOINT_LIMIT:
				return ScriptErrorCode.SCRIPT_CHECKPOINT_LIMIT;
			case HOST_CALL_LIMIT:
				return ScriptErrorCode.SCRIPT_HOST_CALL_LIMIT;
			default:
				return ScriptErrorCode.SCRIPT_RUNTIME_ERROR;
		}
	}
}
