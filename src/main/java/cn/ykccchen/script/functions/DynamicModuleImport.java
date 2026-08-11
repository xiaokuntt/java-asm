package cn.ykccchen.script.functions;

import cn.ykccchen.script.ScriptContext;

import java.util.function.Function;

public class DynamicModuleImport {

	private final Class<?> targetClass;

	private final Function<ScriptContext, Object> finder;

	public DynamicModuleImport(Class<?> targetClass, Function<ScriptContext, Object> finder) {
		this.targetClass = targetClass;
		this.finder = finder;
	}

	public Object getDynamicModule(ScriptContext context){
		return finder.apply(context);
	}

	public Class<?> getTargetClass() {
		return targetClass;
	}
}
