package cn.ykccchen.script.suite;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import cn.ykccchen.script.ExtensionTests;
import cn.ykccchen.script.GrammarTests;
import cn.ykccchen.script.IssuesTests;
import cn.ykccchen.script.LanguageTests;
import cn.ykccchen.script.PerformanceTests;
import cn.ykccchen.script.ScriptApiTests;
import cn.ykccchen.script.ScriptBoundaryTests;
import cn.ykccchen.script.ScriptInputParameterMatrixTests;
import cn.ykccchen.script.ScriptInputParameterTests;
import cn.ykccchen.script.StreamTests;
import cn.ykccchen.script.TryCatchFinallyReturnTest;
import cn.ykccchen.script.compile.CompileTests;
import cn.ykccchen.script.functions.BeanTests;
import cn.ykccchen.script.functions.MethodCallTests;
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
		TryCatchFinallyReturnTest.class,
		AddTests.class,
		MinusTests.class,
		MulTests.class,
		DivTests.class,
		ExitTests.class,
		LinqTests.class,
		ThrowTests.class,
		MethodCallTests.class,
		BeanTests.class,
		CompileTests.class,
		LanguageTests.class,
		ScriptApiTests.class,
		ScriptInputParameterMatrixTests.class,
		ScriptInputParameterTests.class,
		ScriptBoundaryTests.class,
		ExtensionTests.class,
		StreamTests.class,
		IssuesTests.class,
		PerformanceTests.class
})
public class AllScriptTestsSuite {
}
