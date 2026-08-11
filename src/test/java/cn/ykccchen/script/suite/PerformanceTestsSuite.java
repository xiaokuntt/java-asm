package cn.ykccchen.script.suite;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import cn.ykccchen.script.PerformanceTests;

@RunWith(Suite.class)
@Suite.SuiteClasses({
		PerformanceTests.class
})
public class PerformanceTestsSuite {
}
