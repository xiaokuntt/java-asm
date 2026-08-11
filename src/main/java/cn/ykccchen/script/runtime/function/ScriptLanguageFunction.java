package cn.ykccchen.script.runtime.function;

import cn.ykccchen.script.ResourceLoader;
import cn.ykccchen.script.ScriptContext;
import cn.ykccchen.script.runtime.Variables;

import java.util.Map;
import java.util.function.BiFunction;

public class ScriptLanguageFunction implements ScriptLambdaFunction {

	private final BiFunction<Map<String, Object>, String, Object> function;

	private final String content;

	private final ScriptContext context;

	public ScriptLanguageFunction(ScriptContext context, String language, String content) {
		this.context = context;
		this.function = ResourceLoader.loadScriptLanguage(context, language);
		this.content = content;
	}

	@Override
	public Object apply(Variables variables, Object[] args) {
		Map<String, Object> vars = variables.getVariables(this.context);
		return function.apply(vars, this.content);
	}
}
