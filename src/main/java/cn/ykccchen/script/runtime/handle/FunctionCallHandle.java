package cn.ykccchen.script.runtime.handle;

import cn.ykccchen.script.ResourceLoader;
import cn.ykccchen.script.exception.ScriptRuntimeException;
import cn.ykccchen.script.exception.ScriptErrorCode;
import cn.ykccchen.script.functions.ClassExtension;
import cn.ykccchen.script.functions.DynamicAttribute;
import cn.ykccchen.script.functions.DynamicMethod;
import cn.ykccchen.script.reflection.JavaInvoker;
import cn.ykccchen.script.reflection.JavaReflection;
import cn.ykccchen.script.reflection.MethodInvoker;
import cn.ykccchen.script.runtime.RuntimeContext;
import cn.ykccchen.script.runtime.function.ScriptLambdaFunction;
import cn.ykccchen.script.runtime.lang.ArrayValueIterator;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;

import static java.lang.invoke.MethodType.methodType;

public class FunctionCallHandle {

	private static final MethodHandle FALLBACK;

	private static final MethodHandle INVOKE_METHOD;

	private static final MethodHandle INVOKE_FUNCTION;

	private static final MethodHandle METHOD_REFERENCE;

	private static final MethodHandle MEMBER_ACCESS;

	private static final MethodHandle INVOKE_NEW_INSTANCE;

	private static final MethodHandle SET_VARIABLE_VALUE;

	private static final Object[] EMPTY_ARGS = new Object[0];

	static {
		try {
			MethodHandles.Lookup lookup = MethodHandles.lookup();
			FALLBACK = lookup.findStatic(FunctionCallHandle.class, "fallback", methodType(Object.class, MethodCallSite.class, Object[].class));
			INVOKE_METHOD = lookup.findStatic(FunctionCallHandle.class, "invoke_method", methodType(Object.class, RuntimeContext.class, String.class, boolean.class, Object[].class, Object.class));
			INVOKE_FUNCTION = lookup.findStatic(FunctionCallHandle.class, "invoke_function", methodType(Object.class, RuntimeContext.class, String.class, Object[].class, Object.class));
			METHOD_REFERENCE = lookup.findStatic(FunctionCallHandle.class, "method_reference", methodType(Object.class, RuntimeContext.class, Object.class, String.class));
			MEMBER_ACCESS = lookup.findStatic(FunctionCallHandle.class, "member_access", methodType(Object.class, RuntimeContext.class, Object.class, String.class, boolean.class));
			INVOKE_NEW_INSTANCE = lookup.findStatic(FunctionCallHandle.class, "invoke_new_instance", methodType(Object.class, RuntimeContext.class, Object.class, Object[].class));
			SET_VARIABLE_VALUE = lookup.findStatic(FunctionCallHandle.class, "set_variable_value", methodType(Object.class, RuntimeContext.class, Object.class, Object.class, Object.class));
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new Error("FunctionCallHandle初始化失败", e);
		}
	}

	public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type, int flag) {
		MethodCallSite callSite = new MethodCallSite(caller, name, type, FunctionCallHandle.class);
		MethodHandle fallback = null;
		switch (name){
			case "invoke_method" : fallback = INVOKE_METHOD; break;
			case "invoke_function" : fallback = INVOKE_FUNCTION; break;
			case "method_reference" : fallback = METHOD_REFERENCE; break;
			case "member_access" : fallback = MEMBER_ACCESS; break;
			case "invoke_new_instance" : fallback = INVOKE_NEW_INSTANCE; break;
			case "set_variable_value" : fallback = SET_VARIABLE_VALUE; break;
		}
		if(fallback != null){
			fallback = fallback.asType(type);
			callSite.setTarget(fallback);
			callSite.fallback = fallback;
		} else {
			fallback = FALLBACK
					.bindTo(callSite)
					.asCollector(Object[].class, type.parameterCount())
					.asType(type);
			callSite.setTarget(fallback);
			callSite.fallback = fallback;
		}
		return callSite;
	}

	public static Object fallback(MethodCallSite callSite, Object[] args) throws Throwable {
		JavaInvoker<Method> method = JavaReflection.getMethod(FunctionCallHandle.class, callSite.methodName, args);
		if (method != null) {
			return method.invoke0(null, null, args);
		}
		return null;
	}

	public static Object invoke_function(RuntimeContext runtimeContext, String name, Object[] args, Object target) throws Throwable {
		JavaInvoker<Method> method = null;

		if(args != null){
			for (int i = 0, len = args.length; i < len; i++) {
				if(args[i] instanceof ScriptLambdaFunction){
					ScriptLambdaFunction function = (ScriptLambdaFunction) args[i];
					args[i] = (Function<Object[], Object>) objects -> ScriptFunctionInvoker.invoke(runtimeContext, function, objects);
				}
			}
		}
		if (target == null) {
			method = JavaReflection.getFunction(runtimeContext.getScriptContext(), name, args);
		} else if (target instanceof ScriptLambdaFunction) {
			return ScriptFunctionInvoker.invoke(runtimeContext, (ScriptLambdaFunction) target, args);
		} else if (target instanceof Function) {
			ResourceLoader.checkMemberAccess(runtimeContext.getScriptContext(), Function.class, "apply");
			ResourceLoader.recordHostCall(runtimeContext.getScriptContext(), Function.class, "apply");
			return ScriptFunctionInvoker.invoke(runtimeContext, (Function) target, toLambdaArg(args));
		}
		if (method != null) {
			recordResolvedCall(runtimeContext, method);
			return method.invoke0(target, runtimeContext, args);
		}
		throw new ScriptRuntimeException(ScriptErrorCode.SCRIPT_METHOD_RESOLUTION_ERROR,
				String.format("找不到函数%s(%s)", name, String.join(",", JavaReflection.getStringTypes(args))));
	}

	/**
	 * Creates a bound method reference. Method resolution intentionally happens when the
	 * returned function is invoked so overload selection can use the actual arguments.
	 */
	public static Object method_reference(RuntimeContext runtimeContext, Object target, String name) {
		if (target == null) {
			throw new ScriptRuntimeException(ScriptErrorCode.SCRIPT_METHOD_RESOLUTION_ERROR,
					"对象为空, 无法创建方法引用: " + name);
		}
		return (ScriptLambdaFunction) (variables, args) -> {
			try {
				return invoke_method(runtimeContext, name, false,
						args == null ? EMPTY_ARGS : args, target);
			} catch (RuntimeException | Error throwable) {
				throw throwable;
			} catch (Throwable throwable) {
				throw new ScriptRuntimeException(throwable);
			}
		};
	}

	public static Object invoke_method(RuntimeContext runtimeContext, String name, boolean optional, Object[] args, Object target) throws Throwable {
		if (target == null && optional) {
			return null;
		}
		JavaInvoker<Method> method;

		if (target == null) {
			throw new ScriptRuntimeException(ScriptErrorCode.SCRIPT_METHOD_RESOLUTION_ERROR,
					"对象为空, 无法执行的方法: " + name);
		}
		if (target instanceof ScriptLambdaFunction) {
			return ScriptFunctionInvoker.invoke(runtimeContext, (ScriptLambdaFunction) target, args);
		} else if (target instanceof Function) {
			ResourceLoader.checkMemberAccess(runtimeContext.getScriptContext(), Function.class, "apply");
			ResourceLoader.recordHostCall(runtimeContext.getScriptContext(), Function.class, "apply");
			return ScriptFunctionInvoker.invoke(runtimeContext, (Function) target, toLambdaArg(args));
		} else {
			ResourceLoader.checkMemberAccess(runtimeContext.getScriptContext(), target, name);
			method = JavaReflection.getMethod(runtimeContext.getScriptContext(), target, name, args);
		}
		if (method != null) {
			recordResolvedCall(runtimeContext, method);
			return method.invoke0(target, runtimeContext, args);
		}
		if (target instanceof DynamicMethod) {
			ResourceLoader.recordHostCall(runtimeContext.getScriptContext(), DynamicMethod.class, "execute");
			MethodInvoker invoker = new MethodInvoker(DynamicMethod.class.getDeclaredMethod("execute", String.class, List.class));
			Object[] newArgumentValues = new Object[]{name, Arrays.asList(args)};
			return invoker.invoke0(target, runtimeContext, newArgumentValues);
		}
		try {
			Object function = member_access(runtimeContext, target, name, optional);
			if(function != null){
				return invoke_function(runtimeContext, name, args, function);
			}
		} catch (Exception ignored) {
		}
		throw new ScriptRuntimeException(ScriptErrorCode.SCRIPT_METHOD_RESOLUTION_ERROR,
				String.format("在%s中找不到方法%s(%s)", target.getClass().getName(), name,
						String.join(",", JavaReflection.getStringTypes(args))));
	}

	public static Object member_access(RuntimeContext runtimeContext, Object target, String name, boolean optional) {
		if (target == null) {
			if (optional) {
				return null;
			}
			throw new ScriptRuntimeException(ScriptErrorCode.SCRIPT_NULL_ACCESS,
					"target is null");
		}
		ResourceLoader.checkMemberAccess(runtimeContext.getScriptContext(), target, name);
		if ("class".equals(name)) {
			return target instanceof Class ? target : target.getClass();
		} else if (target instanceof Map) {
			ResourceLoader.recordHostCall(runtimeContext.getScriptContext(), target.getClass(), name);
			return ((Map) target).get(name);
		} else if(target instanceof DynamicAttribute){
			ResourceLoader.recordHostCall(runtimeContext.getScriptContext(), DynamicAttribute.class, "getDynamicAttribute");
			return ((DynamicAttribute<?, ?>) target).getDynamicAttribute(name);
		}
		String methodName;
		Object innerClass = JavaReflection.getInnerClass(target, name);
		if (innerClass != null) {
			ResourceLoader.recordHostCall(runtimeContext.getScriptContext(), target instanceof Class
					? (Class<?>) target : target.getClass(), name);
			return innerClass;
		}
		if (name.length() > 1) {
			methodName = name.substring(0, 1).toUpperCase() + name.substring(1);
		} else {
			methodName = name.toUpperCase();
		}
		String getterName = "get" + methodName;
		JavaInvoker<Method> invoker = JavaReflection.getMethod(
				runtimeContext.getScriptContext(), target, getterName, EMPTY_ARGS);
		try {
			if (invoker != null) {
				ResourceLoader.checkMemberAccess(runtimeContext.getScriptContext(), target, getterName);
				recordResolvedCall(runtimeContext, invoker);
				return invoker.invoke0(target, runtimeContext, EMPTY_ARGS);
			} else {
				getterName = "is" + methodName;
				invoker = JavaReflection.getMethod(
						runtimeContext.getScriptContext(), target, getterName, EMPTY_ARGS);
			}
		if (invoker != null) {
			ResourceLoader.checkMemberAccess(runtimeContext.getScriptContext(), target, getterName);
			recordResolvedCall(runtimeContext, invoker);
				return invoker.invoke0(target, runtimeContext, EMPTY_ARGS);
			}
		} catch (Throwable throwable) {
			throw new ScriptRuntimeException(throwable);
		}
		Field field = JavaReflection.getField(target, name);
		if (field != null) {
			ResourceLoader.checkResolvedMemberAccess(runtimeContext.getScriptContext(),
					field.getDeclaringClass(), field.getName());
			ResourceLoader.recordHostCall(runtimeContext.getScriptContext(),
					field.getDeclaringClass(), field.getName());
			return JavaReflection.getFieldValue(target, field);
		}
		if (target instanceof List) {
			List<?> list = (List<?>) target;
			if (!list.isEmpty()) {
				return member_access(runtimeContext, list.get(0), name, optional);
			}
			return null;
		}
		if(optional) {
			return null;
		}
		throw new ScriptRuntimeException(String.format("在%s中找不到属性%s或者方法get%s、方法is%s,内部类%s", target, name, methodName, methodName, name));
	}


	public static Object invoke_new_instance(RuntimeContext runtimeContext, Object target, Object[] args) throws Throwable {
		if (target != null) {
			ResourceLoader.checkMemberAccess(runtimeContext.getScriptContext(), target, "<init>");
		}
		if (target != null) {
			Class<?> owner = target instanceof Class ? (Class<?>) target : target.getClass();
			ResourceLoader.recordHostCall(runtimeContext.getScriptContext(), owner, "<init>");
		}
		return ClassExtension.newInstance(target, runtimeContext, args);
	}

	public static Object newArrayList( Object[] args) {
		List<Object> list = new ArrayList<>(args.length);
		list.addAll(Arrays.asList(args));
		return list;
	}

	public static Iterator<?> newValueIterator(Object target) {
		if (target instanceof Iterable) {
			return ((Iterable<?>) target).iterator();
		} else if (target instanceof Iterator) {
			return (Iterator<?>) target;
		} else if (target instanceof Map) {
			return ((Map) target).values().iterator();
		} else if (target.getClass().isArray()) {
			return new ArrayValueIterator(target);
		} else {
			throw new ScriptRuntimeException("不支持循环" + target.getClass());
		}
	}


	/**
	 * 将脚本调用的 args 数组转换为传给 Function.apply() 的单一参数：
	 * 0 args → null（零参 lambda 忽略），1 arg → args[0]（单参传值），2+ args → args（多参传数组）
	 */
	private static Object toLambdaArg(Object[] args) {
		if (args == null || args.length == 0) return null;
		if (args.length == 1) return args[0];
		return args;
	}

	public static Object newLinkedHashMap(Object[] args) {
		Map<Object, Object> map = new LinkedHashMap<>();
		if (args != null) {
			for (int i = 0, len = args.length; i < len; ) {
				Object key = args[i++];
				map.put(key, args[i++]);
			}
		}
		return map;
	}

	public static Object set_variable_value(RuntimeContext runtimeContext, Object target, Object name, Object value) throws Throwable {
		if (target == null) {
			throw new ScriptRuntimeException(ScriptErrorCode.SCRIPT_NULL_ACCESS,
					"target is null");
		}
		if (name == null) {
			throw new ScriptRuntimeException(ScriptErrorCode.SCRIPT_NULL_ACCESS,
					"key is null");
		}
		ResourceLoader.checkMemberAccess(runtimeContext.getScriptContext(), target, name.toString());
		if (target instanceof Map) {
			ResourceLoader.recordHostCall(runtimeContext.getScriptContext(), target.getClass(), name.toString());
			((Map) target).put(name, value);
		} else if(target instanceof DynamicAttribute){
			ResourceLoader.recordHostCall(runtimeContext.getScriptContext(), DynamicAttribute.class, "setDynamicAttribute");
			((DynamicAttribute) target).setDynamicAttribute(Objects.toString(name, null), value);
		} else if (target instanceof List) {
			ResourceLoader.recordHostCall(runtimeContext.getScriptContext(), target.getClass(), name.toString());
			if (name instanceof Number) {
				((List) target).set(((Number) name).intValue(), value);
			} else {
				throw new ScriptRuntimeException(ScriptErrorCode.SCRIPT_ASSIGNMENT_ERROR,
						"集合下标必须是数字");
			}
		} else if (target instanceof Collection) {
			throw new ScriptRuntimeException(ScriptErrorCode.SCRIPT_ASSIGNMENT_ERROR,
					"该集合类型不支持下标赋值");
		} else if (target.getClass().isArray()){
			ResourceLoader.recordHostCall(runtimeContext.getScriptContext(), target.getClass(), name.toString());
			if (name instanceof Number) {
					Array.set(target, ((Number) name).intValue(), value);
				} else {
					throw new ScriptRuntimeException(ScriptErrorCode.SCRIPT_ASSIGNMENT_ERROR,
							"数组下标必须是数字");
			}
		} else {
			String text = name.toString();
			String methodName;
			if (text.length() > 1) {
				methodName = text.substring(0, 1).toUpperCase() + text.substring(1);
			} else {
				methodName = text.toUpperCase();
			}
			JavaInvoker<Method> setter = JavaReflection.getMethod(
					runtimeContext.getScriptContext(), target, "set" + methodName, value);
			if (setter != null) {
				ResourceLoader.checkMemberAccess(runtimeContext.getScriptContext(), target, "set" + methodName);
				recordResolvedCall(runtimeContext, setter);
				setter.invoke0(target, runtimeContext, new Object[]{value});
			} else {
				Field field = JavaReflection.getField(target, text);
				if (field == null) {
					throw new ScriptRuntimeException(String.format("在%s中找不到属性%s或者方法set%s", target.getClass(), name, methodName));
				}
				ResourceLoader.checkResolvedMemberAccess(runtimeContext.getScriptContext(),
						field.getDeclaringClass(), field.getName());
				ResourceLoader.recordHostCall(runtimeContext.getScriptContext(),
						field.getDeclaringClass(), field.getName());
				JavaReflection.setFieldValue(target, field, value);
			}
		}
		return value;
	}

	private static void recordResolvedCall(RuntimeContext runtimeContext, JavaInvoker<Method> invoker) {
		Method method = invoker.getExecutable();
		if (!invoker.isExtension()) {
			ResourceLoader.checkResolvedMemberAccess(runtimeContext.getScriptContext(),
					method.getDeclaringClass(), method.getName());
		}
		ResourceLoader.recordHostCall(runtimeContext.getScriptContext(),
				method.getDeclaringClass(), method.getName());
	}




}
