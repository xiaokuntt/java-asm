package cn.ykccchen.script.runtime.handle;

import cn.ykccchen.script.ScriptDebugContext;
import cn.ykccchen.script.runtime.RuntimeContext;
import cn.ykccchen.script.runtime.function.ScriptLambdaFunction;

import java.util.function.Function;

/**
 * Centralizes function invocation and debugger frame tracking.
 */
final class ScriptFunctionInvoker {

	private ScriptFunctionInvoker() {
	}

	static Object invoke(RuntimeContext runtimeContext, ScriptLambdaFunction function, Object[] arguments) {
		return withinDebugFrame(runtimeContext,
				() -> function.apply(runtimeContext.getVariables(), arguments));
	}

	@SuppressWarnings("unchecked")
	static Object invoke(RuntimeContext runtimeContext, Function function, Object argument) {
		return withinDebugFrame(runtimeContext, () -> function.apply(argument));
	}

	private static Object withinDebugFrame(RuntimeContext runtimeContext, Invocation invocation) {
		ScriptDebugContext debugContext = runtimeContext.getScriptContext() instanceof ScriptDebugContext
				? (ScriptDebugContext) runtimeContext.getScriptContext()
				: null;
		if (debugContext != null) {
			debugContext.enterFrame();
		}
		try {
			return invocation.invoke();
		} finally {
			if (debugContext != null) {
				debugContext.exitFrame();
			}
		}
	}

	@FunctionalInterface
	private interface Invocation {
		Object invoke();
	}
}
