package cn.ykccchen.script;

/**
 * Receives script execution lifecycle events. Listener failures are isolated
 * and never change the script result.
 */
public interface ScriptExecutionListener {

	default void beforeExecution(ScriptExecutionEvent event) {
	}

	default void afterExecution(ScriptExecutionEvent event) {
	}
}
