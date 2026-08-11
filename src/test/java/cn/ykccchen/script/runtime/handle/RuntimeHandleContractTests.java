package cn.ykccchen.script.runtime.handle;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

import cn.ykccchen.script.Script;
import cn.ykccchen.script.ScriptContext;
import cn.ykccchen.script.functions.DynamicAttribute;
import cn.ykccchen.script.functions.DynamicMethod;
import cn.ykccchen.script.exception.ScriptErrorCode;
import cn.ykccchen.script.exception.ScriptRuntimeException;
import cn.ykccchen.script.runtime.RuntimeContext;
import cn.ykccchen.script.runtime.Variables;
import cn.ykccchen.script.runtime.function.ScriptLambdaFunction;

/**
 * Contract tests for the type-specialized operator implementations used by the
 * invokedynamic call sites. Every reflected invocation has an independently
 * calculated expected value; this keeps new overloads in the same test matrix.
 */
public class RuntimeHandleContractTests {

	public static final class AccessBean {
		public int visible = 3;
		private String name;

		public String getName() { return name; }
		public void setName(String name) { this.name = name; }
	}

	public static final class OuterBean {
		public static final class InnerBean { }
	}

	private static final Set<String> ARITHMETIC_OPERATORS = new HashSet<>(Arrays.asList(
		"plus", "minus", "mul", "divide", "divideAndRemainder"));

	private static final Set<String> COMPARISON_OPERATORS = new HashSet<>(Arrays.asList(
		"less", "less_equals", "greater", "greater_equals"));

	private static final Set<String> BIT_OPERATORS = new HashSet<>(Arrays.asList(
		"left_shift", "right_shift", "right_shift2", "and", "or", "xor"));

	@Test
	public void shouldHonorEveryArithmeticOverload() throws Exception {
		int checked = 0;
		for (Method method : ArithmeticHandle.class.getMethods()) {
			if (!isBinaryOperator(method, ARITHMETIC_OPERATORS)) {
				continue;
			}
			Object left = value(method.getParameterTypes()[0], 12);
			Object right = value(method.getParameterTypes()[1], 3);
			Object actual = invoke(method, left, right);
			String label = signature(method);
			if (method.getName().equals("plus") &&
				(method.getParameterTypes()[0] == String.class || method.getParameterTypes()[1] == String.class)) {
				Assert.assertEquals(label, String.valueOf(left) + right, actual);
			} else {
				assertNumberEquals(label, arithmeticExpected(method.getName()), actual);
			}
			checked++;
		}
		Assert.assertEquals("Arithmetic overload matrix must remain complete", 336, checked);
	}

	@Test
	public void shouldHonorEveryComparisonOverload() throws Exception {
		int checked = 0;
		for (Method method : OperatorHandle.class.getMethods()) {
			if (!isBinaryOperator(method, COMPARISON_OPERATORS)) {
				continue;
			}
			Object actual = invoke(method,
				value(method.getParameterTypes()[0], 2),
				value(method.getParameterTypes()[1], 3));
			boolean expected = method.getName().startsWith("less");
			Assert.assertEquals(signature(method), expected, actual);
			checked++;
		}
		Assert.assertEquals("Comparison overload matrix must remain complete", 256, checked);
	}

	@Test
	public void shouldHonorEveryBitwiseOverload() throws Exception {
		int checked = 0;
		for (Method method : BitHandle.class.getMethods()) {
			if (!isBinaryOperator(method, BIT_OPERATORS) ||
				method.getParameterTypes()[0] == Boolean.class) {
				continue;
			}
			Object actual = invoke(method,
				value(method.getParameterTypes()[0], 12),
				value(method.getParameterTypes()[1], 3));
			assertNumberEquals(signature(method), bitExpected(method.getName()), actual);
			checked++;
		}
		Assert.assertEquals("Bitwise overload matrix must remain complete", 150, checked);
	}

	@Test
	public void shouldSupportBooleanAndUnaryBitwiseOperators() {
		Assert.assertEquals(Boolean.FALSE, BitHandle.and(true, false));
		Assert.assertEquals(Boolean.TRUE, BitHandle.or(true, false));
		Assert.assertEquals(Boolean.TRUE, BitHandle.xor(true, false));
		Assert.assertNull(BitHandle.not(null));
		Assert.assertEquals(-13, BitHandle.not((byte) 12));
		Assert.assertEquals(-13, BitHandle.not((short) 12));
		Assert.assertEquals(-13, BitHandle.not(12));
		Assert.assertEquals(-13L, BitHandle.not(12L));
		Assert.assertEquals(-13L, BitHandle.not(new BigDecimal("12")));
		try {
			BitHandle.not("12");
			Assert.fail("Unsupported unary bitwise operand must be rejected");
		} catch (ScriptRuntimeException expected) {
			Assert.assertEquals(ScriptErrorCode.SCRIPT_OPERATOR_ERROR, expected.getErrorCode());
			Assert.assertTrue(expected.getMessage().contains("java.lang.String"));
		}
	}

	@Test
	public void shouldNegateEverySupportedNumberType() throws Exception {
		int checked = 0;
		for (Method method : ArithmeticHandle.class.getMethods()) {
			if (!method.getName().equals("neg") || method.getParameterCount() != 2) {
				continue;
			}
			Object actual = invoke(method, value(method.getParameterTypes()[0], 12), null);
			assertNumberEquals(signature(method), -12, actual);
			checked++;
		}
		Assert.assertEquals("Unary minus type matrix must remain complete", 8, checked);
	}

	@Test
	public void shouldApplyFallbackComparisonAndEqualityRules() {
		Assert.assertEquals(Boolean.FALSE, OperatorHandle.less_fallback(null, 1));
		Assert.assertEquals(Boolean.TRUE, OperatorHandle.less_fallback("a", "b"));
		Assert.assertEquals(Boolean.FALSE, OperatorHandle.less_equals_fallback(1, null));
		Assert.assertEquals(Boolean.TRUE, OperatorHandle.less_equals_fallback("a", "a"));
		Assert.assertEquals(Boolean.FALSE, OperatorHandle.greater_fallback(null, 1));
		Assert.assertEquals(Boolean.TRUE, OperatorHandle.greater_fallback("b", "a"));
		Assert.assertEquals(Boolean.FALSE, OperatorHandle.greater_equals_fallback(1, null));
		Assert.assertEquals(Boolean.TRUE, OperatorHandle.greater_equals_fallback("b", "b"));

		Assert.assertEquals(Boolean.TRUE, OperatorHandle.equals_fallback(1, 1L));
		Assert.assertEquals(Boolean.FALSE, OperatorHandle.equals_fallback(null, 1));
		Assert.assertEquals(Boolean.FALSE, OperatorHandle.equals_fallback("1", 1));
		Assert.assertEquals(Boolean.TRUE, OperatorHandle.accurate_equals_fallback("x", "x"));
		Assert.assertEquals(Boolean.TRUE, OperatorHandle.not_equals_fallback(1, 2));
		Assert.assertEquals(Boolean.TRUE, OperatorHandle.not_accurate_equals_fallback(1, 1L));
		Assert.assertTrue(OperatorHandle.isTrue("value"));
		Assert.assertTrue(OperatorHandle.isFalse(null));

		assertIllegalOperator(() -> OperatorHandle.less_fallback(1, "2"));
		assertIllegalOperator(() -> OperatorHandle.less_equals_fallback(1, "2"));
		assertIllegalOperator(() -> OperatorHandle.greater_fallback(1, "2"));
		assertIllegalOperator(() -> OperatorHandle.greater_equals_fallback(1, "2"));
	}

	@Test
	public void shouldAccessEveryArrayAndCollectionKind() {
		Assert.assertEquals(2, OperatorHandle.map_or_array_access(new int[]{1, 2}, 1));
		Assert.assertEquals((byte) 2, OperatorHandle.map_or_array_access(new byte[]{1, 2}, 1));
		Assert.assertEquals((short) 2, OperatorHandle.map_or_array_access(new short[]{1, 2}, 1));
		Assert.assertEquals(2F, OperatorHandle.map_or_array_access(new float[]{1, 2}, 1));
		Assert.assertEquals(2D, OperatorHandle.map_or_array_access(new double[]{1, 2}, 1));
		Assert.assertEquals(2L, OperatorHandle.map_or_array_access(new long[]{1, 2}, 1));
		Assert.assertEquals('b', OperatorHandle.map_or_array_access(new char[]{'a', 'b'}, 1));
		Assert.assertEquals(true, OperatorHandle.map_or_array_access(new boolean[]{false, true}, 1));
		Assert.assertEquals("b", OperatorHandle.map_or_array_access(new String[]{"a", "b"}, 1));
		Assert.assertEquals('b', OperatorHandle.map_or_array_access("ab", 1));
		Assert.assertNull(OperatorHandle.map_or_array_access(new int[]{1}, 2));

		Map<String, Integer> map = new HashMap<>();
		map.put("key", 7);
		Assert.assertEquals(7, OperatorHandle.map_or_array_access_fallback(map, "key"));
		Assert.assertEquals("b", OperatorHandle.map_or_array_access_fallback(Arrays.asList("a", "b"), 1));
		Assert.assertNull(OperatorHandle.map_or_array_access_fallback(Collections.singletonList("a"), 2));
		Assert.assertEquals(2, OperatorHandle.map_or_array_access_fallback(new int[]{1, 2}, 1));
		Assert.assertNull(OperatorHandle.map_or_array_access_fallback(new int[]{1}, 2));
		Assert.assertNull(OperatorHandle.map_or_array_access_fallback(null, "key"));
		DynamicAttribute<Object, Object> attributes = new DynamicAttribute<Object, Object>() {
			@Override
			public Object getDynamicAttribute(String key) {
				return "dynamic-" + key;
			}
		};
		Assert.assertEquals("dynamic-name", OperatorHandle.map_or_array_access_fallback(attributes, "name"));
		assertIllegalOperator(() -> OperatorHandle.map_or_array_access_fallback(new Object(), "key"));
	}

	@Test
	public void shouldRouteRealScriptOperatorsThroughDynamicCallSites() {
		Script script = Script.create("return [left << right, left >> right, left >>> right, " +
			"left & right, left | right, left ^ right, left < right];", null);
		ScriptContext context = new ScriptContext();
		context.set("left", 12).set("right", 3);
		Assert.assertEquals(Arrays.asList(96, 1, 1, 0, 15, 15, false), script.execute(context));
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	public void functionHandleShouldCoverDynamicAccessIterationAndAssignment() throws Throwable {
		ScriptContext scriptContext = new ScriptContext();
		RuntimeContext runtime = new RuntimeContext(scriptContext, new Variables(0));
		Assert.assertNull(FunctionCallHandle.fallback(
				new MethodCallSite(java.lang.invoke.MethodHandles.lookup(), "missing",
						java.lang.invoke.MethodType.methodType(Object.class), FunctionCallHandle.class),
				new Object[0]));

		Function<Object, Object> function = value -> value == null ? "zero" : value;
		Assert.assertEquals("zero", FunctionCallHandle.invoke_function(
				runtime, "apply", new Object[0], function));
		Assert.assertEquals(7, FunctionCallHandle.invoke_method(
				runtime, "apply", false, new Object[]{7}, function));
		ScriptLambdaFunction lambda = (variables, arguments) -> arguments == null ? null : arguments.length;
		assertScriptRuntime(() -> FunctionCallHandle.invoke_function(
				runtime, "missing", new Object[]{lambda}, null));
		Assert.assertEquals(2, FunctionCallHandle.invoke_function(
				runtime, "apply", new Object[]{1, 2}, lambda));
		Assert.assertEquals(1, FunctionCallHandle.invoke_method(
				runtime, "apply", false, new Object[]{1}, lambda));
		Assert.assertNull(FunctionCallHandle.invoke_method(
				runtime, "missing", true, new Object[0], null));

		DynamicMethod dynamic = (name, parameters) -> name + parameters.size();
		Assert.assertEquals("work2", FunctionCallHandle.invoke_method(
				runtime, "work", false, new Object[]{1, 2}, dynamic));

		AccessBean bean = new AccessBean();
		Assert.assertEquals(3, FunctionCallHandle.member_access(runtime, bean, "visible", false));
		Assert.assertNull(FunctionCallHandle.member_access(runtime, Collections.emptyList(), "name", false));
		Assert.assertNull(FunctionCallHandle.member_access(runtime, null, "name", true));
		Assert.assertEquals(OuterBean.InnerBean.class,
				FunctionCallHandle.member_access(runtime, OuterBean.class, "InnerBean", false));
		assertScriptRuntime(() -> FunctionCallHandle.member_access(runtime, bean, "missing", false));

		Assert.assertTrue(FunctionCallHandle.newValueIterator(Arrays.asList(1, 2)).hasNext());
		Iterator<Integer> iterator = Arrays.asList(1, 2).iterator();
		Assert.assertSame(iterator, FunctionCallHandle.newValueIterator(iterator));
		Assert.assertTrue(FunctionCallHandle.newValueIterator(Collections.singletonMap("a", 1)).hasNext());
		Assert.assertTrue(FunctionCallHandle.newValueIterator(new int[]{1}).hasNext());
		assertScriptRuntime(() -> FunctionCallHandle.newValueIterator(new Object()));

		Map<Object, Object> map = new HashMap<>();
		Assert.assertEquals(4, FunctionCallHandle.set_variable_value(runtime, map, "x", 4));
		Assert.assertEquals(4, map.get("x"));
		List<Object> list = new ArrayList<>(Arrays.asList(1, 2));
		FunctionCallHandle.set_variable_value(runtime, list, 1, 5);
		Assert.assertEquals(5, list.get(1));
		assertScriptRuntime(() -> FunctionCallHandle.set_variable_value(runtime, list, "x", 1));
		assertScriptRuntime(() -> FunctionCallHandle.set_variable_value(runtime,
				new HashSet<>(Arrays.asList(1, 2)), 0, 5));
		int[] array = {1, 2};
		FunctionCallHandle.set_variable_value(runtime, array, 0, 6);
		Assert.assertEquals(6, array[0]);
		assertScriptRuntime(() -> FunctionCallHandle.set_variable_value(runtime, array, "x", 1));
		FunctionCallHandle.set_variable_value(runtime, bean, "name", "updated");
		Assert.assertEquals("updated", bean.getName());
		Assert.assertEquals("updated", FunctionCallHandle.member_access(runtime, bean, "name", false));
		assertScriptRuntime(() -> FunctionCallHandle.set_variable_value(runtime, bean, "missing", 1));

		DynamicAttribute<Object, Object> attributes = new DynamicAttribute<Object, Object>() {
			private final Map<String, Object> values = new HashMap<>();
			@Override public Object getDynamicAttribute(String key) { return values.get(key); }
			@Override public Object setDynamicAttribute(String key, Object value) { return values.put(key, value); }
		};
		FunctionCallHandle.set_variable_value(runtime, attributes, "dynamic", 8);
		Assert.assertEquals(8, FunctionCallHandle.member_access(runtime, attributes, "dynamic", false));
	}

	private static void assertScriptRuntime(ThrowingOperation operation) {
		try {
			operation.run();
			Assert.fail("Expected ScriptRuntimeException");
		} catch (ScriptRuntimeException expected) {
			// expected
		} catch (Throwable failure) {
			throw new AssertionError(failure);
		}
	}

	private interface ThrowingOperation {
		void run() throws Throwable;
	}

	private static void assertIllegalOperator(Runnable operation) {
		try {
			operation.run();
			Assert.fail("Unsupported operator operands must be rejected");
		} catch (ScriptRuntimeException expected) {
			Assert.assertEquals(ScriptErrorCode.SCRIPT_OPERATOR_ERROR, expected.getErrorCode());
			Assert.assertTrue(expected.getMessage().contains("不支持"));
		}
	}

	private static boolean isBinaryOperator(Method method, Set<String> names) {
		return Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())
			&& method.getParameterCount() == 2 && names.contains(method.getName());
	}

	private static Object invoke(Method method, Object... arguments) throws Exception {
		try {
			return method.invoke(null, arguments);
		} catch (InvocationTargetException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof Exception) {
				throw (Exception) cause;
			}
			throw exception;
		}
	}

	private static Object value(Class<?> type, int number) {
		if (type == Byte.class) return (byte) number;
		if (type == Short.class) return (short) number;
		if (type == Integer.class || type == Object.class) return number;
		if (type == Float.class) return (float) number;
		if (type == Double.class) return (double) number;
		if (type == Long.class) return (long) number;
		if (type == BigDecimal.class) return new BigDecimal(number);
		if (type == BigInteger.class) return BigInteger.valueOf(number);
		if (type == String.class) return String.valueOf(number);
		throw new AssertionError("No test value for " + type.getName());
	}

	private static int arithmeticExpected(String operator) {
		if (operator.equals("plus")) return 15;
		if (operator.equals("minus")) return 9;
		if (operator.equals("mul")) return 36;
		if (operator.equals("divide")) return 4;
		if (operator.equals("divideAndRemainder")) return 0;
		throw new AssertionError("Unknown arithmetic operator " + operator);
	}

	private static int bitExpected(String operator) {
		if (operator.equals("left_shift")) return 96;
		if (operator.equals("right_shift") || operator.equals("right_shift2")) return 1;
		if (operator.equals("and")) return 0;
		if (operator.equals("or") || operator.equals("xor")) return 15;
		throw new AssertionError("Unknown bitwise operator " + operator);
	}

	private static void assertNumberEquals(String label, int expected, Object actual) {
		Assert.assertTrue(label + " returned a non-number: " + actual, actual instanceof Number);
		BigDecimal normalized = new BigDecimal(actual.toString());
		Assert.assertEquals(label, 0, BigDecimal.valueOf(expected).compareTo(normalized));
	}

	private static String signature(Method method) {
		return method.getName() + Arrays.toString(method.getParameterTypes());
	}
}
