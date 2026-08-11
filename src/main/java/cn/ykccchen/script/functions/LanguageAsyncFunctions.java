package cn.ykccchen.script.functions;

import cn.ykccchen.script.LanguageAsyncRuntime;
import cn.ykccchen.script.runtime.RuntimeContext;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Script-facing async composition helpers exposed as the default {@code Async} import. */
public final class LanguageAsyncFunctions {

	private LanguageAsyncFunctions() {
	}

	public static CompletionStage<Object> all(RuntimeContext context, Object tasks) {
		return LanguageAsyncRuntime.all(context.getScriptContext(), tasks);
	}

	public static CompletionStage<Object> race(RuntimeContext context, Object tasks) {
		return LanguageAsyncRuntime.race(context.getScriptContext(), tasks);
	}

	public static CompletionStage<Object> timeout(RuntimeContext context, Object task, long timeoutMillis) {
		return LanguageAsyncRuntime.timeout(
				context.getScriptContext(), task, timeoutMillis, TimeUnit.MILLISECONDS);
	}
}
