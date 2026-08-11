package cn.ykccchen.script;

import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import cn.ykccchen.script.category.IntegrationCategory;

@Category(IntegrationCategory.class)
public class StreamTests extends BaseTest {

	@Test
	public void eachTest(){
		Assert.assertEquals("[{a=yes}]", execute("stream/each.ms"));
	}

	@Test
	public void distinctTest(){
		Assert.assertEquals("[1, 2, 3, 5, 6]",execute("stream/distinct.ms"));
	}


	@Test
	public void jdk8DistinctTest(){
		Assert.assertEquals("[1, 2, 3, 5, 6]",execute("stream/jdk8-distinct.ms"));
	}
}
