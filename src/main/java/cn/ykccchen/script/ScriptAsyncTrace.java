package cn.ykccchen.script;

/** Immutable parent/child trace for one language-level asynchronous task. */
public final class ScriptAsyncTrace {

	private final long taskId;
	private final long parentTaskId;
	private final int depth;
	private final String scriptName;
	private final String correlationId;
	private final String submittingThread;

	ScriptAsyncTrace(long taskId, long parentTaskId, int depth, ScriptContext context) {
		this.taskId = taskId;
		this.parentTaskId = parentTaskId;
		this.depth = depth;
		this.scriptName = context.getScriptName();
		this.correlationId = context.getCorrelationId();
		this.submittingThread = Thread.currentThread().getName();
	}

	public long getTaskId() { return taskId; }
	public long getParentTaskId() { return parentTaskId; }
	public int getDepth() { return depth; }
	public String getScriptName() { return scriptName; }
	public String getCorrelationId() { return correlationId; }
	public String getSubmittingThread() { return submittingThread; }
}
