package cn.ykccchen.script;

import java.util.concurrent.CompletionStage;

/**
 * Read-only handle for one asynchronous script execution.
 */
public interface ScriptTask {

	long getTaskId();

	ScriptTaskState getState();

	String getScriptName();

	long getScriptVersion();

	String getCorrelationId();

	long getSubmittedAtMillis();

	long getStartedAtMillis();

	long getCompletedAtMillis();

	CompletionStage<Object> completion();

	/**
	 * Request cancellation. A {@code true} result means that the request won the
	 * state transition, not that a blocking Java call has already stopped.
	 */
	boolean cancel();

	default boolean isDone() {
		return getState().isTerminal();
	}
}
