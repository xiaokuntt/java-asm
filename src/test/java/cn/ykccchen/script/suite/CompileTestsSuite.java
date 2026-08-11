package cn.ykccchen.script.suite;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import cn.ykccchen.script.compile.CompileTests;

@RunWith(Suite.class)
@Suite.SuiteClasses({
		CompileTests.class
})
public class CompileTestsSuite {
}
