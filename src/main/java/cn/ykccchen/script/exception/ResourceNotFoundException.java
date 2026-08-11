package cn.ykccchen.script.exception;

public class ResourceNotFoundException extends ScriptRuntimeException {
	private static final long serialVersionUID = 1L;

	public ResourceNotFoundException(String module) {
		super(ScriptErrorCode.SCRIPT_RESOURCE_NOT_FOUND, module);
	}
}
