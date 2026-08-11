package cn.ykccchen.script;

import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import cn.ykccchen.script.category.GrammarCategory;
import cn.ykccchen.script.runtime.ExitValue;

@Category(GrammarCategory.class)
public class ScriptBoundaryTests extends BaseTest {

	@Test
	public void lambdaCaptureReflectsLatestVariableValue() {
		Assert.assertEquals(21, execute("boundary/lambda_capture.ms"));
	}

	@Test
	public void doWhileExecutesAtLeastOnce() {
		Assert.assertEquals(1, execute("boundary/do_while_once.ms"));
	}

	@Test
	public void optionalChainOnNullReturnsNullSafely() {
		Assert.assertEquals(Boolean.TRUE, execute("boundary/optional_chain_null.ms"));
	}

	@Test
	public void forLoopSupportsMultiplePostExpressions() {
		Assert.assertEquals("[3, 6]", execute("boundary/for_multi_post.ms"));
	}

	@Test
	public void exitWithoutValuesProducesEmptyExitValue() {
		Object result = execute("boundary/exit_empty.ms");
		Assert.assertTrue(result instanceof ExitValue);
		Assert.assertEquals(0, ((ExitValue) result).getLength());
	}

	@Test
	public void numericLiteralBoundariesAreParsedCorrectly() {
		Assert.assertEquals("[16, 3, 1000000, 12]", execute("boundary/numeric_literals.ms"));
	}

	@Test
	public void templateStringSupportsExpressionInterpolation() {
		Assert.assertEquals("sum=3", execute("boundary/template_expression.ms"));
	}

	@Test
	public void mapLiteralSupportsShorthandAndExplicitEntries() {
		Assert.assertEquals("{a=1, b=2, c=3}", execute("boundary/map_shorthand.ms"));
	}

	@Test
	public void castExpressionConvertsNumericType() {
		Assert.assertEquals(12, execute("boundary/cast_expression.ms"));
	}

	@Test
	public void arrayDestructuringHandlesMissingElementsAsNull() {
		Assert.assertEquals("[10, null]", execute("boundary/array_destructuring_edge.ms"));
	}

	@Test
	public void compileFailsWhenUsingKeywordAsVariableName() {
		try {
			Script.create("var for = 1; return for;", null);
			Assert.fail("Expected compile error");
		} catch (Exception ex) {
			Assert.assertTrue(ex.getMessage().contains("关键字"));
		}
	}

	@Test
	public void compileFailsWhenLiteralAppearsAsStandaloneStatement() {
		try {
			Script.create("1; return 2;", null);
			Assert.fail("Expected compile error");
		} catch (Exception ex) {
			Assert.assertTrue(ex.getMessage().contains("literal"));
		}
	}
}
