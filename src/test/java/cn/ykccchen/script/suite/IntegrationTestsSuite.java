package cn.ykccchen.script.suite;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import cn.ykccchen.script.ExtensionTests;
import cn.ykccchen.script.LanguageTests;
import cn.ykccchen.script.ScriptApiTests;
import cn.ykccchen.script.ScriptInputParameterTests;
import cn.ykccchen.script.ScriptInputParameterMatrixTests;
import cn.ykccchen.script.StreamTests;

@RunWith(Suite.class)
@Suite.SuiteClasses({
		LanguageTests.class,
		ScriptApiTests.class,
		ScriptInputParameterTests.class,
		ScriptInputParameterMatrixTests.class,
		ExtensionTests.class,
		StreamTests.class
})
public class IntegrationTestsSuite {
}
