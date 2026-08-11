package cn.ykccchen.script.functions;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import cn.ykccchen.script.BaseTest;
import cn.ykccchen.script.category.FunctionCategory;

@Ignore("asBean extension removed in current runtime baseline")
@Category(FunctionCategory.class)
public class BeanTests extends BaseTest {

	@Test
	public void map2bean() {
		System.out.println(execute("functions/map2bean.ms"));
	}

}
