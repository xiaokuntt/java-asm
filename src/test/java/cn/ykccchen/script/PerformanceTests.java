package cn.ykccchen.script;

import org.junit.Test;
import org.junit.experimental.categories.Category;
import cn.ykccchen.script.category.PerformanceCategory;

@Category(PerformanceCategory.class)
public class PerformanceTests extends BaseTest {

	@Test
	public void sum() {
		System.out.println(execute("performance/sum.ms"));
	}

	@Test
	public void classLoaderAndMetaspaceChurn() {
		Script.clearCompileCache();
		Script.resetClassLoadingStats();
		long metaspaceBefore = Script.getClassLoadingStats().getMetaspaceUsedBytes();
		for (int i = 0; i < 1000; i++) {
			Script.create("return " + i + ";", null).compile();
		}
		ScriptClassLoadingStats stats = Script.getClassLoadingStats();
		org.junit.Assert.assertEquals(8, stats.getCreatedLoaderCount());
		org.junit.Assert.assertEquals(1000, stats.getDefinedClassCount());
		org.junit.Assert.assertTrue(stats.getDefinedBytecodeBytes() > 0);
		System.out.println("script classloaders=" + stats.getCreatedLoaderCount()
				+ ", liveEstimate=" + stats.getEstimatedLiveLoaderCount()
				+ ", metaspaceDelta="
				+ (metaspaceBefore < 0 ? -1 : stats.getMetaspaceUsedBytes() - metaspaceBefore));
	}
}
