package cn.ykccchen.script.convert;

import cn.ykccchen.script.runtime.Variables;
import cn.ykccchen.script.runtime.function.ScriptLambdaFunction;

import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.function.Function;

/**
 * 脚本内部lambda到Java函数式的转换
 */
public class FunctionalImplicitConvert implements ClassImplicitConvert {

	private final ClassLoader classLoader = FunctionalImplicitConvert.class.getClassLoader();

	@Override
	public boolean support(Class<?> from, Class<?> to) {
		if (to.getAnnotation(FunctionalInterface.class) == null) {
			return false;
		}
		if (ScriptLambdaFunction.class.isAssignableFrom(from)) {
			return true;
		}
		// Function<Object,Object> 可隐式转换为任何 @FunctionalInterface（但不转换为已兼容的类型）
		return Function.class.isAssignableFrom(from) && !to.isAssignableFrom(from);
	}

	@Override
	public Object convert(Variables variables, Object source, Class<?> target) {
		if (source instanceof Function) {
			@SuppressWarnings("unchecked")
			Function<Object, Object> function = (Function<Object, Object>) source;
			// 通过 Proxy 将 Function<Object,Object> 适配为目标函数式接口
			return Proxy.newProxyInstance(classLoader, new Class[]{target}, (proxy, method, args) -> {
				if (Modifier.isAbstract(method.getModifiers())) {
					Object arg = (args == null || args.length == 0) ? null
							: args.length == 1 ? args[0]
							: args;
					return function.apply(arg);
				}
				if ("toString".equalsIgnoreCase(method.getName())) {
					return "Proxy(" + source + "," + target + ")";
				} else if ("hashCode".equals(method.getName()) || "equals".equals(method.getName())) {
					return method.invoke(source, args);
				}
				return null;
			});
		}
		ScriptLambdaFunction function = (ScriptLambdaFunction) source;
		if (target == Function.class) {
			return (Function<Object, Object>) args -> {
				Object[] param;
				if (args == null) {
					param = new Object[0];
				} else{
					Class<?> aClass = args.getClass();
					if(aClass.isArray() && aClass.getComponentType() == Object.class){
						param = (Object[]) args;
					} else {
						param = new Object[]{args};
					}
				}
				return function.apply(variables, param);
			};
		}
		return Proxy.newProxyInstance(classLoader, new Class[]{target}, (proxy, method, args) -> {
			if (Modifier.isAbstract(method.getModifiers())) {
				return function.apply(variables, args);
			}
			if ("toString".equalsIgnoreCase(method.getName())) {
				return "Proxy(" + source + "," + target + ")";
			}else if ("hashCode".equals(method.getName()) || "equals".equals(method.getName())) {
				return method.invoke(source,args);
			}
			return null;
		});
	}
}
