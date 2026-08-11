package cn.ykccchen.script.runtime.function;

import cn.ykccchen.script.runtime.Variables;

@FunctionalInterface
public interface ScriptLambdaFunction {

	Object apply(Variables variables, Object[] args);
}
