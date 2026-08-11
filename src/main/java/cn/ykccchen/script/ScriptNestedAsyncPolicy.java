package cn.ykccchen.script;

/** Scheduling strategy for async work submitted by an async worker. */
public enum ScriptNestedAsyncPolicy {
	/** Preserve deadlock-safe historical behavior by running nested work inline. */
	INLINE,
	/** Always submit nested work to the configured executor. */
	EXECUTOR,
	/** Submit only when a known thread pool has spare workers; otherwise run inline. */
	AUTO
}
