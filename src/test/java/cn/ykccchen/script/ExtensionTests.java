package cn.ykccchen.script;

import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import cn.ykccchen.script.category.IntegrationCategory;

@Category(IntegrationCategory.class)
public class ExtensionTests extends BaseTest {

	@Test
	public void clazzNameTest(){
		Assert.assertEquals("[java.util.Map$Entry, Entry, java.util.Map.Entry]", execute("extension/clazz.ms"));
	}

}
