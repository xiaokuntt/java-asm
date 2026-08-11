package cn.ykccchen.script;

/**
 * Receives asynchronous task state changes. Listener failures are isolated.
 */
@FunctionalInterface
public interface ScriptTaskListener {

	void onStateChanged(ScriptTaskEvent event);
}
