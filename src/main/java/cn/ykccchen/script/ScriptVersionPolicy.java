package cn.ykccchen.script;

/**
 * Determines when a named script revision is resolved for an asynchronous task.
 */
public enum ScriptVersionPolicy {
	/** Resolve and retain the active revision when the task is submitted. */
	PINNED,
	/** Resolve the active revision when a worker starts the task. */
	LATEST
}
