package cn.ykccchen.script.suite;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import cn.ykccchen.script.functions.BeanTests;
import cn.ykccchen.script.functions.MethodCallTests;

@RunWith(Suite.class)
@Suite.SuiteClasses({
		MethodCallTests.class,
		BeanTests.class
})
public class FunctionTestsSuite {
}
