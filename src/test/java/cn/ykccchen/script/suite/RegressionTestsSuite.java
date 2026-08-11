package cn.ykccchen.script.suite;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import cn.ykccchen.script.IssuesTests;

@RunWith(Suite.class)
@Suite.SuiteClasses({
		IssuesTests.class
})
public class RegressionTestsSuite {
}
