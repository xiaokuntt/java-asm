package cn.ykccchen.script;

import java.util.concurrent.atomic.AtomicLong;

final class ScriptTaskIds {

	private static final AtomicLong SEQUENCE = new AtomicLong();

	private ScriptTaskIds() {
	}

	static long next() {
		return SEQUENCE.incrementAndGet();
	}
}
