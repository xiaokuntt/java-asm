package cn.ykccchen.script;

import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ScriptDebuggerTests {

	private static final String LAMBDA_SCRIPT =
			"var add = value => {\n" +
			"    var nested = value + 1;\n" +
			"    return nested;\n" +
			"};\n" +
			"var result = add(1);\n" +
			"return result;";

	@Test
	public void conditionalBreakpointCanInspectVariablesAndEvaluateExpression() throws Exception {
		ScriptDebugContext context = new ScriptDebugContext(Collections.emptyList());
		context.setScriptBreakpoints(Collections.singletonList(
				ScriptBreakpoint.builder(2)
						.condition(variables -> Integer.valueOf(1).equals(variables.get("first")))
						.build()));
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<Object> result = executor.submit(() -> Script.createDebug(
					"var first = 1;\nreturn first + 1;", null).execute(context));
			Assert.assertTrue(context.await(5, TimeUnit.SECONDS));
			Assert.assertEquals(2, context.getCurrentLine());
			Assert.assertEquals(ScriptDebugContext.PauseReason.BREAKPOINT, context.getPauseReason());
			Assert.assertEquals(3, context.evaluate("first + 2"));
			context.resume();
			Assert.assertEquals(2, result.get(5, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void stepOverSkipsStatementsInsideLambda() throws Exception {
		ScriptDebugContext context = new ScriptDebugContext(Collections.singletonList(5));
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<Object> result = executor.submit(() -> Script.createDebug(LAMBDA_SCRIPT, null).execute(context));
			Assert.assertTrue(context.await(5, TimeUnit.SECONDS));
			Assert.assertEquals(5, context.getCurrentLine());
			context.stepOver();
			Assert.assertTrue(context.await(5, TimeUnit.SECONDS));
			Assert.assertEquals(6, context.getCurrentLine());
			Assert.assertEquals(ScriptDebugContext.PauseReason.STEP, context.getPauseReason());
			context.resume();
			Assert.assertEquals(2, result.get(5, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void stepOutReturnsToCallingFrame() throws Exception {
		ScriptDebugContext context = new ScriptDebugContext(Collections.singletonList(2));
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<Object> result = executor.submit(() -> Script.createDebug(LAMBDA_SCRIPT, null).execute(context));
			Assert.assertTrue(context.await(5, TimeUnit.SECONDS));
			Assert.assertEquals(2, context.getCurrentLine());
			Assert.assertEquals(1, context.getFrameDepth());
			context.stepOut();
			Assert.assertTrue(context.await(5, TimeUnit.SECONDS));
			Assert.assertEquals(6, context.getCurrentLine());
			Assert.assertEquals(0, context.getFrameDepth());
			context.resume();
			Assert.assertEquals(2, result.get(5, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
		}
	}
}
