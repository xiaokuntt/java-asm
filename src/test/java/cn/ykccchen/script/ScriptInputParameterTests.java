package cn.ykccchen.script;

import org.junit.Test;
import org.junit.experimental.categories.Category;
import cn.ykccchen.script.category.IntegrationCategory;
import cn.ykccchen.script.input.DynamicMainFunctions;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Category(IntegrationCategory.class)
public class ScriptInputParameterTests extends InputCaseTestSupport {

	@Test
	public void supportsPrimitiveInputParameters() {
		runScriptCases("input/params_primitives.ms", Arrays.asList(
				scriptCase(5, mapOf("a", 2, "b", 3))
		));
	}

	@Test
	public void supportsNestedMapInputParameters() {
		runScriptCases("input/params_nested_map.ms", Arrays.asList(
				scriptCase("alice-7", mapOf("user", mapOf("name", "alice", "profile", mapOf("level", 7))))
		));
	}

	@Test
	public void supportsListInputParameters() {
		List<Map<String, Object>> orders = Arrays.asList(
				mapOf("id", "A1"),
				mapOf("id", "B2")
		);
		runScriptCases("input/params_list.ms", Arrays.asList(
				scriptCase("B2:2", mapOf("orders", orders))
		));
	}

	@Test
	public void supportsOptionalChainForMissingInputFields() {
		runScriptCases("input/params_optional.ms", Arrays.asList(
				scriptCase(Boolean.TRUE, mapOf("user", null))
		));
	}

	@Test
	public void supportsNullFallbackFromInput() {
		runScriptCases("input/params_null_fallback.ms", Arrays.asList(
				scriptCase("guest", mapOf("name", null))
		));
	}

	@Test
	public void supportsCallingMainClassMethodsWithOnlyAAndB() {
		DynamicMainFunctions functions = new DynamicMainFunctions();
		String source = String.valueOf(execute("input/params_dynamic_main_test1.ms"));
		Object test1Result = functions.dynamic_main_invoke(source, "test1", "hello", "world");
		org.junit.Assert.assertNull(test1Result);
		Object test2Result = functions.dynamic_main_invoke(source, "test2", "hello", "world");
		org.junit.Assert.assertEquals("helloworld", test2Result);
	}

	@Test
	public void mainClassMethodRejectsInvalidArity() {
		DynamicMainFunctions functions = new DynamicMainFunctions();
		String source = String.valueOf(execute("input/params_dynamic_main_invalid_arity.ms"));
		try {
			functions.dynamic_main_invoke(source, "test2", "onlyOne");
			org.junit.Assert.fail("Expected invalid arity to fail");
		} catch (RuntimeException ex) {
			String message = ex.getMessage();
			org.junit.Assert.assertTrue(message != null && message.contains("NoSuchMethodException"));
		}
	}
}
