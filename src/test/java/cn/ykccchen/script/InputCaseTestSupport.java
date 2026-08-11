package cn.ykccchen.script;

import org.junit.Assert;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class InputCaseTestSupport extends BaseTest {

	protected void runScriptCases(String script, List<ScriptCase> cases) {
		for (ScriptCase testCase : cases) {
			Assert.assertEquals("Script case failed: " + script + " params=" + testCase.params,
					testCase.expected, execute(script, testCase.params));
		}
	}

	protected void runErrorCases(List<ErrorCase> cases) {
		for (ErrorCase errorCase : cases) {
			try {
				execute(errorCase.script, errorCase.params);
				Assert.fail("Expected runtime error for " + errorCase.script + ", params=" + errorCase.params);
			} catch (Exception ex) {
				String message = ex.getMessage();
				boolean matched = false;
				if (message != null) {
					for (String part : errorCase.messageParts) {
						if (message.contains(part)) {
							matched = true;
							break;
						}
					}
				}
				Assert.assertTrue("Expected error contains one of " + Arrays.toString(errorCase.messageParts) + ", actual: " + message, matched);
			}
		}
	}

	protected ScriptCase scriptCase(Object expected, Map<String, Object> params) {
		return new ScriptCase(expected, params);
	}

	protected ErrorCase errorCase(String script, Map<String, Object> params, String... messageParts) {
		return new ErrorCase(script, params, messageParts);
	}

	protected Map<String, Object> mapOf(Object... kvPairs) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < kvPairs.length; i += 2) {
			map.put((String) kvPairs[i], kvPairs[i + 1]);
		}
		return map;
	}

	protected static class ScriptCase {
		private final Object expected;
		private final Map<String, Object> params;

		private ScriptCase(Object expected, Map<String, Object> params) {
			this.expected = expected;
			this.params = params;
		}
	}

	protected static class ErrorCase {
		private final String script;
		private final Map<String, Object> params;
		private final String[] messageParts;

		private ErrorCase(String script, Map<String, Object> params, String[] messageParts) {
			this.script = script;
			this.params = params;
			this.messageParts = messageParts;
		}
	}
}
