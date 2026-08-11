package cn.ykccchen.script.suite;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import cn.ykccchen.script.GrammarTests;
import cn.ykccchen.script.ScriptBoundaryTests;
import cn.ykccchen.script.TryCatchFinallyReturnTest;
import cn.ykccchen.script.grammer.AddTests;
import cn.ykccchen.script.grammer.DivTests;
import cn.ykccchen.script.grammer.ExitTests;
import cn.ykccchen.script.grammer.LinqTests;
import cn.ykccchen.script.grammer.MinusTests;
import cn.ykccchen.script.grammer.MulTests;
import cn.ykccchen.script.grammer.ThrowTests;

@RunWith(Suite.class)
@Suite.SuiteClasses({
		GrammarTests.class,
		ScriptBoundaryTests.class,
		TryCatchFinallyReturnTest.class,
		AddTests.class,
		MinusTests.class,
		MulTests.class,
		DivTests.class,
		ExitTests.class,
		LinqTests.class,
		ThrowTests.class
})
public class GrammarTestsSuite {
}
