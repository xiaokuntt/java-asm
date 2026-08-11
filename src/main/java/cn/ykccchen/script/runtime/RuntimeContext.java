package cn.ykccchen.script.runtime;

import cn.ykccchen.script.ScriptContext;

import java.util.Map;

public class RuntimeContext {

	private final ScriptContext context;

	private Variables variables;

	public RuntimeContext(ScriptContext context, Variables variables) {
		this.context = context;
		this.variables = variables;
	}

	public Variables getVariables() {
		return variables;
	}

	public Map<String, Object> getVarMap() {
		return variables.getVariables(context);
	}

	public ScriptContext getScriptContext() {
		return context;
	}

	public Object eval(String script){
		return this.context.eval(this, script);
	}
}
