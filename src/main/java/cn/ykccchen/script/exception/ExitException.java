package cn.ykccchen.script.exception;

import cn.ykccchen.script.runtime.ExitValue;

public class ExitException extends RuntimeException {

	/**
	 * exit type
	 */
	private final ExitValue exitValue;

	public ExitException(ExitValue exitValue) {
		this.exitValue = exitValue;
	}

	public ExitValue getExitValue() {
		return exitValue;
	}
}
