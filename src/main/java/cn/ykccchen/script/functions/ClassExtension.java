package cn.ykccchen.script.functions;

import cn.ykccchen.script.annotation.Comment;
import cn.ykccchen.script.convert.FunctionalImplicitConvert;
import cn.ykccchen.script.exception.ScriptErrorCode;
import cn.ykccchen.script.exception.ScriptRuntimeException;
import cn.ykccchen.script.reflection.JavaInvoker;
import cn.ykccchen.script.reflection.JavaReflection;
import cn.ykccchen.script.runtime.RuntimeContext;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

public class ClassExtension {

	public static Object newInstance(Class<?> clazz) throws ReflectiveOperationException {
		return clazz.getDeclaredConstructor().newInstance();
	}

	public static Object newInstance(Class<?> clazz, RuntimeContext runtimeContext,
									 @Comment(name = "values", value = "构造参数") Object... values) throws Throwable {
		if (values == null || values.length == 0) {
			return newInstance(clazz);
		}
		Class<?>[] parametersTypes = new Class<?>[values.length];
		for (int i = 0; i < values.length; i++) {
			Object value = values[i];
			parametersTypes[i] = value == null ? JavaReflection.Null.class : value.getClass();
		}
		List<Constructor<?>> constructors = Arrays.asList(clazz.getConstructors());
		JavaInvoker<Constructor> invoker = JavaReflection.findConstructorInvoker(constructors, parametersTypes);
		if (invoker == null) {
			// Support new FunctionalInterface((args) -> ...) syntax
			if (clazz.isInterface() && values.length == 1) {
				Object value = values[0];
				if (clazz.isInstance(value)) {
					return value;
				}
				if (value != null && clazz.getAnnotation(FunctionalInterface.class) != null) {
					FunctionalImplicitConvert converter = new FunctionalImplicitConvert();
					if (converter.support(value.getClass(), clazz)) {
						return converter.convert(runtimeContext.getVariables(), value, clazz);
					}
				}
			}
			throw new ScriptRuntimeException(ScriptErrorCode.SCRIPT_METHOD_RESOLUTION_ERROR,
					String.format("can not find constructor for [%s] with types: [%s]",
							clazz, Arrays.toString(parametersTypes)));
		}
		return invoker.invoke0(null, runtimeContext, values);
	}

	public static Object newInstance(Object target, RuntimeContext runtimeContext,
									 @Comment(name = "values", value = "构造参数") Object... values) throws Throwable {
		if (target == null) {
			throw new ScriptRuntimeException(ScriptErrorCode.SCRIPT_NULL_ACCESS,
					"NULL不能被new");
		}
		if (target instanceof Class) {
			return newInstance((Class<?>) target, runtimeContext, values);
		}
		return newInstance(target.getClass(), runtimeContext, values);
	}

	/**
	 * @since 1.6.2
	 */
	@Comment("获取Java类全名")
	public static String getName(Class<?> clazz) {
		return clazz.getName();
	}
	/**
	 * @since 1.6.2
	 */
	@Comment("获取Java类名")
	public static String getSimpleName(Class<?> clazz) {
		return clazz.getSimpleName();
	}
	/**
	 * @since 1.6.2
	 */
	@Comment("获取Java类规范全名")
	public static String getCanonicalName(Class<?> clazz) {
		return clazz.getCanonicalName();
	}

}
