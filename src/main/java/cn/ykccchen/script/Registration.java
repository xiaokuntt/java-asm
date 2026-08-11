package cn.ykccchen.script;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Idempotent handle used to unregister a previously registered extension or resource.
 */
public final class Registration implements AutoCloseable {

	private final AtomicBoolean closed = new AtomicBoolean();
	private final Runnable cleanup;

	private Registration(Runnable cleanup) {
		this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
	}

	public static Registration of(Runnable cleanup) {
		return new Registration(cleanup);
	}

	@Override
	public void close() {
		if (closed.compareAndSet(false, true)) {
			cleanup.run();
		}
	}

	public boolean isClosed() {
		return closed.get();
	}
}
