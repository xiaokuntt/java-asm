package cn.ykccchen.script;

import org.junit.Test;
import org.junit.experimental.categories.Category;
import cn.ykccchen.script.category.IntegrationCategory;
import cn.ykccchen.script.input.DynamicMainFunctions;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

@Category(IntegrationCategory.class)
public class ScriptInputParameterMatrixTests extends InputCaseTestSupport {

	@Test
	public void numericInputMatrix() {
		runScriptCases("input/params_primitives.ms", Arrays.asList(
				scriptCase(5, mapOf("a", 2, "b", 3)),
				scriptCase(0, mapOf("a", -1, "b", 1)),
				scriptCase(0, mapOf("a", 0, "b", 0)),
				scriptCase(3_000_000, mapOf("a", 1_000_000, "b", 2_000_000)),
				scriptCase(-120, mapOf("a", -100, "b", -20)),
				scriptCase(Integer.MAX_VALUE, mapOf("a", Integer.MAX_VALUE, "b", 0)),
				scriptCase(Integer.MIN_VALUE, mapOf("a", Integer.MIN_VALUE, "b", 0)),
				scriptCase(-1, mapOf("a", 999, "b", -1000)),
				scriptCase(100, mapOf("a", 42, "b", 58)),
				scriptCase(4, mapOf("a", 7, "b", -3))
		));
	}

	@Test
	public void collectionAndMapBoundaryMatrix() {
		runScriptCases("input/params_collection_checks.ms", Arrays.asList(
				scriptCase("items=0,payload=0", mapOf("items", Collections.emptyList(), "payload", Collections.emptyMap())),
				scriptCase("items=3,payload=-1", mapOf("items", Arrays.asList(1, 2, 3))),
				scriptCase("items=-1,payload=1", mapOf("payload", Collections.singletonMap("k", "v")))
		));
	}

	@Test
	public void deepOptionalChainMatrix() {
		runScriptCases("input/params_optional_deep.ms", Arrays.asList(
				scriptCase("none", mapOf("user", null)),
				scriptCase("none", mapOf("user", mapOf("profile", null))),
				scriptCase("shanghai", mapOf("user", mapOf("profile", mapOf("address", mapOf("city", "shanghai")))))
		));
	}

	@Test
	public void emptyAndNonEmptyOrdersMatrix() {
		Map<String, Object> order100 = mapOf("id", "O-100");
		Map<String, Object> order200 = mapOf("id", "O-200");
		runScriptCases("input/params_first_order_id.ms", Arrays.asList(
				scriptCase("empty", mapOf("orders", Collections.emptyList())),
				scriptCase("empty", mapOf("orders", null)),
				scriptCase("O-100", mapOf("orders", Collections.singletonList(order100))),
				scriptCase("O-200", mapOf("orders", Arrays.asList(order200, order100)))
		));
	}

	@Test
	public void scoreGradeBoundaryMatrix() {
		runScriptCases("input/params_score_grade.ms", Arrays.asList(
				scriptCase("A", mapOf("score", 100)),
				scriptCase("A", mapOf("score", 90)),
				scriptCase("B", mapOf("score", 89)),
				scriptCase("B", mapOf("score", 60)),
				scriptCase("C", mapOf("score", 59)),
				scriptCase("C", mapOf("score", 0)),
				scriptCase("C", mapOf("score", -1))
		));
	}

	@Test
	public void listSumMatrix() {
		runScriptCases("input/params_list_sum.ms", Arrays.asList(
				scriptCase(6, mapOf("nums", Arrays.asList(1, 2, 3))),
				scriptCase(100, mapOf("nums", Collections.singletonList(100))),
				scriptCase(0, mapOf("nums", Collections.emptyList())),
				scriptCase(-6, mapOf("nums", Arrays.asList(-1, -2, -3))),
				scriptCase(10, mapOf("nums", Arrays.asList(10, -10, 5, -2, 7)))
		));
	}

	@Test
	public void typeMismatchInputShouldThrowClearError() {
		runErrorCases(Arrays.asList(
				errorCase("input/params_orders_size.ms", mapOf("orders", 123), "size"),
				errorCase("input/params_required_user_name.ms", mapOf("user", null), "对象为空", "target is null"),
				errorCase("input/params_required_user_name.ms", mapOf("user", 123), "name", "target is null")
		));
	}

	@Test
	public void dynamicMainInvocationMatrix() {
		DynamicMainFunctions functions = new DynamicMainFunctions();
		String source = String.valueOf(execute("input/params_dynamic_main_test2.ms"));
		assertDynamicInvoke(functions, source, "test2", "a", "b", "ab");
		assertDynamicInvoke(functions, source, "test2", "hello", "world", "helloworld");
		assertDynamicInvoke(functions, source, "test2", "1", "2", "12");

		String overloadSource = String.valueOf(execute("input/params_dynamic_main_overload.ms"));
		assertDynamicInvoke(functions, overloadSource, "test2", "a", "b", "two-ab");
		assertDynamicInvoke(functions, overloadSource, "test2", "hello", "world", "two-helloworld");

		String listSource = String.valueOf(execute("input/params_dynamic_main_list.ms"));
		assertDynamicInvoke(functions, listSource, "testList", Arrays.asList("x", "y"), "x:2");
		assertDynamicInvoke(functions, listSource, "testList", Collections.singletonList("first"), "first:1");

		String customClassSource = String.valueOf(execute("input/params_dynamic_custom_class.ms"));
		assertDynamicInvoke(functions, customClassSource, "test2", "a", "b", "custom-ab");
		assertDynamicInvokeWithClass(functions, customClassSource, "ScriptEntry", "test2", "x", "y", "custom-xy");
	}

	@Test
	public void dynamicMainErrorMatrix() {
		DynamicMainFunctions functions = new DynamicMainFunctions();
		String missingMethodSource = String.valueOf(execute("input/params_dynamic_main_missing_method.ms"));
		assertDynamicInvokeError(functions, missingMethodSource, "notExists", "x", "y", "NoSuchMethodException", "notExists");

		String compileErrorSource = String.valueOf(execute("input/params_dynamic_main_compile_error.ms"));
		assertDynamicInvokeError(functions, compileErrorSource, "test2", "x", "y", "Compile dynamic class failed");
	}

	private void assertDynamicInvoke(DynamicMainFunctions functions, String source, String method, Object arg1, Object arg2, String expected) {
		org.junit.Assert.assertEquals(expected, functions.dynamic_main_invoke(source, method, arg1, arg2));
	}

	private void assertDynamicInvoke(DynamicMainFunctions functions, String source, String method, Object arg1, String expected) {
		org.junit.Assert.assertEquals(expected, functions.dynamic_main_invoke(source, method, arg1));
	}

	private void assertDynamicInvokeWithClass(DynamicMainFunctions functions, String source, String className, String method, Object arg1, Object arg2, String expected) {
		org.junit.Assert.assertEquals(expected, functions.dynamic_main_invoke_with_class(source, className, method, arg1, arg2));
	}

	private void assertDynamicInvokeError(DynamicMainFunctions functions, String source, String method, String a, String b, String... expectedParts) {
		try {
			functions.dynamic_main_invoke(source, method, a, b);
			org.junit.Assert.fail("Expected dynamic invoke to fail, method=" + method);
		} catch (RuntimeException ex) {
			String message = ex.getMessage();
			boolean matched = false;
			if (message != null) {
				for (String part : expectedParts) {
					if (message.contains(part)) {
						matched = true;
						break;
					}
				}
			}
			org.junit.Assert.assertTrue("Expected error contains one of " + Arrays.toString(expectedParts) + ", actual: " + message, matched);
		}
	}
}
