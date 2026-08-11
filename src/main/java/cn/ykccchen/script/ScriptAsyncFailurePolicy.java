package cn.ykccchen.script;

/**
 * Defines how failures from language-level asynchronous child tasks affect the
 * enclosing script execution.
 */
public enum ScriptAsyncFailurePolicy {

	/** Cancel sibling tasks as soon as one child fails, then fail the scope. */
	FAIL_FAST,

	/** Wait for every child and propagate the first unobserved failure. */
	WAIT_ALL,

	/** Wait for every child but do not propagate detached child failures. */
	IGNORE
}
