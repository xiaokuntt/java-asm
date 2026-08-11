package cn.ykccchen.script.runtime;

import cn.ykccchen.script.ScriptContext;

public class ScriptVariableAccessRuntime extends ScriptRuntime {

	private final String varName;

	public ScriptVariableAccessRuntime(String varName) {
		this.varName = varName;
	}

	@Override
	public Object execute(ScriptContext context) {
		return context.getEnvironmentValue(varName);
	}
}
