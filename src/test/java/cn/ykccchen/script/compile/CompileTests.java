package cn.ykccchen.script.compile;

import org.junit.Test;
import org.junit.experimental.categories.Category;
import cn.ykccchen.script.BaseTest;
import cn.ykccchen.script.category.CompileCategory;

@Category(CompileCategory.class)
public class CompileTests extends BaseTest {

	@Test
	public void defineVar() {
		System.out.println(execute("compile/var.ms"));
	}

	@Test
	public void operator() {
		System.out.println(execute("compile/operator.ms"));
	}

	@Test
	public void compile_combine_assign() {
		System.out.println(execute("compile/compile_combine_assign.ms"));
	}

}
