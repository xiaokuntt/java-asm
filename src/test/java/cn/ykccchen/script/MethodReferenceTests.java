package cn.ykccchen.script;

import cn.ykccchen.script.exception.ScriptErrorCode;
import cn.ykccchen.script.exception.ScriptExecutionException;
import cn.ykccchen.script.exception.ScriptRuntimeException;
import cn.ykccchen.script.parsing.TokenStream;
import cn.ykccchen.script.parsing.TokenType;
import cn.ykccchen.script.parsing.Tokenizer;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public class MethodReferenceTests {

	public static final class OrderService {
		public String getOrder() {
			return "ORDER-0";
		}

		public String findOrder(String id) {
			return "ORDER-" + id;
		}

		public String formatOrder(String id, int quantity) {
			return id + "x" + quantity;
		}

		public String overloaded(int value) {
			return "int-" + value;
		}

		public String overloaded(String value) {
			return "string-" + value;
		}

		public static String staticOrder(String id) {
			return "STATIC-" + id;
		}
	}

	public static final class FunctionalHost {
		public String fromSupplier(Supplier<String> supplier) {
			return supplier.get();
		}

		public String fromFunction(Function<String, String> function, String value) {
			return function.apply(value);
		}

		public String fromBiFunction(BiFunction<String, Integer, String> function,
				String value, int quantity) {
			return function.apply(value, quantity);
		}
	}

	@Test
	public void tokenizerRecognizesDoubleColonAsOneToken() {
		TokenStream tokens = Tokenizer.tokenize("service::getOrder");
		Assert.assertEquals(TokenType.Identifier, tokens.consume().getType());
		Assert.assertEquals(TokenType.DoubleColon, tokens.consume().getType());
		Assert.assertEquals(TokenType.Identifier, tokens.consume().getType());
		Assert.assertFalse(tokens.hasMore());
	}

	@Test
	public void javaAndLegacyLambdaArrowsAreEquivalent() {
		Assert.assertEquals(9, execute("var sum = (a, b) -> a + b; return sum(4, 5);"));
		Assert.assertEquals(9, execute("var sum = (a, b) => a + b; return sum(4, 5);"));
		Assert.assertEquals(6, execute("var twice = value -> { return value * 2; }; return twice(3);"));
	}

	@Test
	public void boundReferenceSupportsZeroOneAndMultipleArguments() {
		String source = "var getter = service::getOrder;"
				+ "var finder = service::findOrder;"
				+ "var formatter = service::formatOrder;"
				+ "return getter() + '/' + finder('42') + '/' + formatter('SKU', 3);";
		Assert.assertEquals("ORDER-0/ORDER-42/SKUx3", execute(source));
	}

	@Test
	public void boundReferenceResolvesOverloadAtInvocationTime() {
		String source = "var convert = service::overloaded;"
				+ "return convert(7) + '/' + convert('7');";
		Assert.assertEquals("int-7/string-7", execute(source));
	}

	@Test
	public void classReferenceInvokesStaticMethod() {
		ScriptContext context = context();
		context.set("OrderService", OrderService.class);
		Assert.assertEquals("STATIC-88", Script.create(
				"var finder = OrderService::staticOrder; return finder('88');", null).execute(context));
	}

	@Test
	public void boundReferenceAdaptsToJavaFunctionalInterfaces() {
		String source = "return host.fromSupplier(service::getOrder) + '/'"
				+ "+ host.fromFunction(service::findOrder, '21') + '/'"
				+ "+ host.fromBiFunction(service::formatOrder, 'SKU', 2);";
		Assert.assertEquals("ORDER-0/ORDER-21/SKUx2", execute(source));
	}

	@Test
	public void boundReferenceCanBeInvokedRepeatedly() {
		Assert.assertEquals("ORDER-A/ORDER-B", execute(
				"var finder = service::findOrder; return finder('A') + '/' + finder('B');"));
	}

	@Test
	public void boundReferenceUsesExistingAccessPolicyAtInvocationTime() {
		ScriptAccessPolicy policy = ScriptAccessPolicy.builder()
				.allowClassPrefix(OrderService.class.getName())
				.denyMember(OrderService.class.getName(), "findOrder")
				.build();
		ScriptEngineConfig config = ScriptEngineConfig.builder().accessPolicy(policy).build();
		JvmScriptEngine engine = new JvmScriptEngine(new ScriptEngineFactory(config), config);
		try {
			Script.create("var finder = service::findOrder; return finder('A');", engine)
					.execute(context());
			Assert.fail("Expected access policy rejection");
		} catch (RuntimeException exception) {
			Assert.assertEquals(ScriptErrorCode.SCRIPT_SECURITY_ERROR, findScriptError(exception).getErrorCode());
		} finally {
			engine.close();
		}
	}

	@Test
	public void boundReferenceUsesExistingHostCallLimit() {
		ScriptContext context = context().setExecutionLimits(
				ScriptExecutionLimits.builder().maxHostCalls(1).build());
		try {
			Script.create("var finder = service::findOrder; finder('A'); return finder('B');", null)
					.execute(context);
			Assert.fail("Expected host call limit");
		} catch (ScriptExecutionException exception) {
			Assert.assertEquals(ScriptExecutionException.Reason.HOST_CALL_LIMIT, exception.getReason());
		}
		Assert.assertEquals(2, context.getHostCallCount());
	}

	@Test
	public void nullTargetAndMissingMethodHaveStableErrors() {
		assertErrorCode("var service = null; return service::getOrder;",
				ScriptErrorCode.SCRIPT_METHOD_RESOLUTION_ERROR);
		assertErrorCode("var missing = service::notFound; return missing();",
				ScriptErrorCode.SCRIPT_METHOD_RESOLUTION_ERROR);
	}

	@Test
	public void existingColonSyntaxRemainsAvailable() {
		String source = "var map = {name: 'order'};"
				+ "var result = true ? map.name : 'missing';"
				+ "var selected = 'bad';"
				+ "switch(result){ case 'order': selected = result; break; default:{ selected = 'bad'; } }"
				+ "return selected;";
		Assert.assertEquals("order", execute(source));
	}

	private static Object execute(String source) {
		return Script.create(source, null).execute(context());
	}

	private static ScriptContext context() {
		return new ScriptContext()
				.set("service", new OrderService())
				.set("host", new FunctionalHost());
	}

	private static void assertErrorCode(String source, ScriptErrorCode expected) {
		try {
			execute(source);
			Assert.fail("Expected ScriptRuntimeException");
		} catch (RuntimeException exception) {
			Assert.assertEquals(expected, findScriptError(exception).getErrorCode());
		}
	}

	private static ScriptRuntimeException findScriptError(Throwable throwable) {
		Throwable current = throwable;
		ScriptRuntimeException fallback = null;
		while (current != null) {
			if (current instanceof ScriptRuntimeException) {
				fallback = (ScriptRuntimeException) current;
				if (fallback.getErrorCode() != ScriptErrorCode.SCRIPT_RUNTIME_ERROR) {
					return fallback;
				}
			}
			current = current.getCause();
		}
		Assert.assertNotNull("Expected ScriptRuntimeException in cause chain", fallback);
		return fallback;
	}
}
