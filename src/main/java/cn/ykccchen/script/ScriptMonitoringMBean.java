package cn.ykccchen.script;

/** JMX view combining synchronous execution and asynchronous task metrics. */
public interface ScriptMonitoringMBean {
	long getExecutionStartedCount();
	long getExecutionCompletedCount();
	long getExecutionSucceededCount();
	long getExecutionFailedCount();
	long getExecutionHostCallLimitCount();
	long getExecutionAverageDurationNanos();
	long getTaskSubmittedCount();
	long getTaskCompletedCount();
	long getTaskActiveCount();
	long getTaskRejectedCount();
	long getTaskAbandonedCount();
	long getTaskAverageQueueDurationNanos();
	long getTaskAverageExecutionDurationNanos();
	int getFutureBridgePoolSize();
	int getFutureBridgeActiveCount();
	int getFutureBridgeQueueSize();
	long getFutureBridgeCompletedCount();
	long getFutureBridgeRejectedCount();
	long getAbandonedLanguageTaskCount();
	void reset();
}
