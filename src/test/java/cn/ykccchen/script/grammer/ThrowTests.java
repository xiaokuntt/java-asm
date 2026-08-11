package cn.ykccchen.script.grammer;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.experimental.categories.Category;
import cn.ykccchen.script.BaseTest;
import cn.ykccchen.script.category.GrammarCategory;
import cn.ykccchen.script.exception.ScriptEvaluationException;
import cn.ykccchen.script.exception.ScriptRuntimeException;

@Category(GrammarCategory.class)
public class ThrowTests extends BaseTest {
	@Rule
	public ExpectedException thrown = ExpectedException.none();

	@Test
	public void throw_1() {
		try {
			execute("grammar/throw_1.ms");
		} catch (ScriptEvaluationException e) {
			Throwable cause = e.getCause();
			Assert.assertEquals(ScriptRuntimeException.class, cause.getClass());
			Assert.assertEquals("ex", cause.getMessage());
		}
	}

	@Test
	public void throw_2() {
		try {
			execute("grammar/throw_2.ms");
		} catch (ScriptEvaluationException e) {
			Throwable cause = e.getCause();
			Assert.assertEquals(ArithmeticException.class, cause.getClass());
		}
	}
}
