package cn.ykccchen.script;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class ScriptDiagnosticsTests {

	@Test
	public void validScriptHasNoDiagnostics() {
		ScriptDiagnostics result = Script.validate("var total = 1 + 2; return total;", "rules/total.ms");

		Assert.assertTrue(result.isValid());
		Assert.assertFalse(result.hasErrors());
		Assert.assertEquals("rules/total.ms", result.getSourceName());
		Assert.assertTrue(result.getDiagnostics().isEmpty());
	}

	@Test
	public void invalidScriptReturnsStructuredLocation() {
		ScriptDiagnostics result = Script.validate("var total = ;\nreturn total;", "rules/invalid.ms");

		Assert.assertFalse(result.isValid());
		Assert.assertEquals(1, result.getErrors().size());
		ScriptDiagnostic diagnostic = result.getErrors().get(0);
		Assert.assertEquals("SCRIPT_PARSE_ERROR", diagnostic.getCode());
		Assert.assertEquals(ScriptDiagnostic.Severity.ERROR, diagnostic.getSeverity());
		Assert.assertEquals("rules/invalid.ms", diagnostic.getSourceName());
		Assert.assertTrue(diagnostic.hasLocation());
		Assert.assertEquals(1, diagnostic.getStartLine());
		Assert.assertTrue(diagnostic.getStartColumn() > 0);
		Assert.assertNotNull(diagnostic.getSnippet());
		Assert.assertFalse(diagnostic.getMessage().isEmpty());
	}

	@Test
	public void diagnosticsAreImmutable() {
		ScriptDiagnostics result = Script.validate("return 1 +;", "immutable.ms");
		List<ScriptDiagnostic> diagnostics = result.getDiagnostics();

		try {
			diagnostics.clear();
			Assert.fail("diagnostics must be immutable");
		} catch (UnsupportedOperationException expected) {
			// expected
		}
	}

	@Test
	public void compileFailureIsReportedWithoutThrowing() {
		ScriptDiagnostics result = Script.validate("return ++1;", "rules/compile.ms");

		Assert.assertFalse(result.isValid());
		Assert.assertEquals(1, result.getErrors().size());
		Assert.assertEquals("SCRIPT_COMPILE_ERROR", result.getErrors().get(0).getCode());
	}

	@Test
	public void nullSourceIsRejected() {
		try {
			Script.validate(null, "null.ms");
			Assert.fail("source is required");
		} catch (NullPointerException expected) {
			Assert.assertEquals("source", expected.getMessage());
		}
	}
}
