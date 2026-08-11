package cn.ykccchen.script.reflection;

import cn.ykccchen.script.Registration;
import cn.ykccchen.script.ScriptContext;
import cn.ykccchen.script.convert.ClassImplicitConvert;
import cn.ykccchen.script.convert.FunctionalImplicitConvert;
import cn.ykccchen.script.functions.ClassExtension;
import cn.ykccchen.script.runtime.RuntimeContext;
import cn.ykccchen.script.exception.ScriptRuntimeException;

import java.beans.Transient;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class JavaReflection {
	private static final int LEGACY_RESOLUTION_CACHE_LIMIT = 256;
	private static final AtomicLong LEGACY_REGISTRY_EPOCH = new AtomicLong();
	private static final ClassValue<BoundedResolutionCache<String, Optional<Field>>> FIELD_CACHE =
			new ClassValue<BoundedResolutionCache<String, Optional<Field>>>() {
		@Override
		protected BoundedResolutionCache<String, Optional<Field>> computeValue(Class<?> type) {
			return new BoundedResolutionCache<>(LEGACY_RESOLUTION_CACHE_LIMIT);
		}
	};
	private static final List<ClassImplicitConvert> CONVERTS = new CopyOnWriteArrayList<>();
	private static final ClassValue<Map<String, List<JavaInvoker<Method>>>> EXTENSION_METHOD_CACHE = new ClassValue<Map<String, List<JavaInvoker<Method>>>>() {
		@Override
		protected Map<String, List<JavaInvoker<Method>>> computeValue(Class<?> type) {
			return new ConcurrentHashMap<>();
		}
	};
	private static final ClassValue<BoundedResolutionCache<MethodSignature, Optional<JavaInvoker<Method>>>> METHOD_CACHE =
			new ClassValue<BoundedResolutionCache<MethodSignature, Optional<JavaInvoker<Method>>>>() {
		@Override
		protected BoundedResolutionCache<MethodSignature, Optional<JavaInvoker<Method>>> computeValue(Class<?> type) {
			return new BoundedResolutionCache<>(LEGACY_RESOLUTION_CACHE_LIMIT);
		}
	};
	private static final Map<Class<?>, List<Class<?>>> EXTENSION_MAP = new ConcurrentHashMap<>();
	private static final List<JavaInvoker<Method>> FUNCTIONS = new CopyOnWriteArrayList<>();

	static {
		installDefaults();
	}

	@Deprecated
	public static Registration registerFunction(Object target) {
		Objects.requireNonNull(target, "target");
		List<JavaInvoker<Method>> registered = Stream.of(target.getClass().getMethods())
				.filter(method -> method.getAnnotation(cn.ykccchen.script.annotation.Function.class) != null)
				.map(MethodInvoker::new)
				.map(it -> {
					it.setDefaultTarget(target);
					return (JavaInvoker<Method>) it;
				})
				.collect(Collectors.toList());
		FUNCTIONS.addAll(registered);
		return Registration.of(() -> FUNCTIONS.removeAll(registered));
	}

	public static Map<Class<?>, List<Class<?>>> getExtensionMap() {
		Map<Class<?>, List<Class<?>>> snapshot = new HashMap<>();
		EXTENSION_MAP.forEach((type, extensions) ->
				snapshot.put(type, Collections.unmodifiableList(new ArrayList<>(extensions))));
		return Collections.unmodifiableMap(snapshot);
	}

	public static List<JavaInvoker<Method>> getFunctions() {
		return Collections.unmodifiableList(new ArrayList<>(FUNCTIONS));
	}

	/** Captures the deprecated process-wide reflection registry for test isolation. */
	public static GlobalRegistrySnapshot snapshotGlobalRegistry() {
		Map<Class<?>, List<Class<?>>> extensionTypes = new HashMap<>();
		Map<Class<?>, Map<String, List<JavaInvoker<Method>>>> extensionMethods = new HashMap<>();
		EXTENSION_MAP.forEach((type, extensions) -> {
			extensionTypes.put(type, new ArrayList<>(extensions));
			Map<String, List<JavaInvoker<Method>>> methods = new HashMap<>();
			EXTENSION_METHOD_CACHE.get(type).forEach((name, invokers) ->
					methods.put(name, new ArrayList<>(invokers)));
			extensionMethods.put(type, methods);
		});
		return new GlobalRegistrySnapshot(new ArrayList<>(CONVERTS),
				new ArrayList<>(FUNCTIONS), extensionTypes, extensionMethods);
	}

	public static synchronized void resetGlobalRegistry() {
		clearGlobalRegistry();
		installDefaults();
	}

	public static synchronized void restoreGlobalRegistry(GlobalRegistrySnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot");
		clearGlobalRegistry();
		CONVERTS.addAll(snapshot.converts);
		FUNCTIONS.addAll(snapshot.functions);
		snapshot.extensionTypes.forEach((type, extensions) ->
				EXTENSION_MAP.put(type, new CopyOnWriteArrayList<>(extensions)));
		snapshot.extensionMethods.forEach((type, methods) -> {
			Map<String, List<JavaInvoker<Method>>> cache = EXTENSION_METHOD_CACHE.get(type);
			methods.forEach((name, invokers) ->
					cache.put(name, new CopyOnWriteArrayList<>(invokers)));
		});
	}


	private static MethodInvoker findApply(Class<?> cls) {
		for (Method method : cls.getDeclaredMethods()) {
			if ("apply".equals(method.getName())) {
				return new MethodInvoker(method);
			}
		}
		return null;
	}

	private static int calcToObjectDistanceWithInterface(Class<?>[] interfaces, int distance, int score) {
		if (interfaces == null) {
			return distance;
		}
		return Arrays.stream(interfaces).mapToInt(i -> {
			int v = calcToObjectDistanceWithInterface(i.getInterfaces(), distance, score + 2);
			return v + distance + score;
		}).sum();
	}

	private static int calcToObjectDistance(Class<?> clazz) {
		return calcToObjectDistance(clazz, 0);
	}

	private static int calcToObjectDistance(Class<?> clazz, int distance) {
		if (clazz == null) {
			return distance + 3;
		}
		if (Object.class.equals(clazz)) {
			return distance;
		}
		int interfaceScore = calcToObjectDistanceWithInterface(clazz.getInterfaces(), distance + 2, 0);
		if (clazz.isInterface()) {
			return interfaceScore;
		}
		int classScore = calcToObjectDistance(clazz.getSuperclass(), distance + 3);
		return classScore + interfaceScore;
	}

	private static int matchTypes(JavaInvoker<?> invoker, Class<?>[] parameterTypes,
			Class<?>[] otherTypes, boolean matchCount, List<ClassImplicitConvert> converts) {
		if (matchCount && parameterTypes.length != otherTypes.length) {
			return -1;
		}
		int score = 0;
		for (int ii = 0, nn = parameterTypes.length; ii < nn; ii++) {
			Class<?> type = parameterTypes[ii];
			Class<?> otherType = otherTypes[ii];
			if(RuntimeContext.class.isAssignableFrom(otherType)){
				score += 1000;
			} else if (Null.class.equals(type)) {
				if (otherType.isPrimitive()) {
					score = -1;
					break;
				}
				score += 1000;
			} else if (!isPrimitiveAssignableFrom(type, otherType)) {
				score += 1000;
				if (!otherType.isAssignableFrom(type)) {
					score += 1000;
					if (!isCoercible(type, otherType)) {
						score += 2000;
						boolean found = false;
						for (ClassImplicitConvert convert : converts) {
							if (convert.support(type, otherType)) {
								invoker.addClassImplicitConvert(ii, convert);
								found = true;
								break;
							}
						}
						invoker.setImplicit(found);
						if (!found) {
							return -1;
						}
					}
				}
			}
		}
		return score;
	}

	public static JavaInvoker<Method> findMethodInvoker(List<JavaInvoker<Method>> methods, Class<?>[] parameterTypes) {
		return findInvoker(methods, parameterTypes);
	}

	static JavaInvoker<Method> findMethodInvoker(List<JavaInvoker<Method>> methods,
			Class<?>[] parameterTypes, List<ClassImplicitConvert> converts) {
		return findInvoker(methods, parameterTypes, converts);
	}

	public static JavaInvoker<Constructor> findConstructorInvoker(List<Constructor<?>> constructors, Class<?>[] parameterTypes) {
		return findInvoker(constructors.stream().map(ConstructorInvoker::new).collect(Collectors.toList()), parameterTypes);
	}

	public static <T extends Executable> JavaInvoker<T> findInvoker(List<JavaInvoker<T>> executables, Class<?>[] parameterTypes) {
		return findInvoker(executables, parameterTypes, CONVERTS);
	}

	static <T extends Executable> JavaInvoker<T> findInvoker(List<JavaInvoker<T>> executables,
			Class<?>[] parameterTypes, List<ClassImplicitConvert> converts) {
		JavaInvoker<T> foundInvoker = null;
		int foundScore = 0;
		List<JavaInvoker<T>> executableWithVarArgs = new ArrayList<>();
		for (JavaInvoker<T> invoker : executables) {
			// Check if the types match.
			Class<?>[] otherTypes = invoker.getParameterTypes();
			invoker = invoker.copy();
			int score = matchTypes(invoker, parameterTypes, otherTypes, true, converts);
			if (score > -1) {
				if (foundInvoker == null) {
					foundInvoker = invoker;
					foundScore = score;
				} else {
					if (score < foundScore) {
						foundScore = score;
						foundInvoker = invoker;
					}
				}
			} else if (invoker.isVarArgs()) {
				executableWithVarArgs.add(invoker);
			}
		}
		if (foundInvoker == null) {
			for (JavaInvoker<T> invoker : executableWithVarArgs) {
				Class<?>[] otherTypes = invoker.getParameterTypes();
				int score = -1;
				int fixedParaLength = otherTypes.length - 1;
				if (parameterTypes.length >= fixedParaLength) {
					Class<?>[] argTypes = new Class<?>[fixedParaLength];
					System.arraycopy(parameterTypes, 0, argTypes, 0, fixedParaLength);
					invoker = invoker.copy();
					score = matchTypes(invoker, argTypes, otherTypes, false, converts);
					if (score > -1) {
						Class<?> target = otherTypes[fixedParaLength].getComponentType();
						for (int i = fixedParaLength; i < parameterTypes.length; i++) {
							Class<?> type = parameterTypes[i];
							if(RuntimeContext.class.isAssignableFrom(type)){
								score++;
							} else if (Null.class.equals(type)) {
								if (!target.isPrimitive()) {
									score++;
								} else {
									score = -1;
									break;
								}
							} else if (!isPrimitiveAssignableFrom(type, target)) {
								score++;
								if (!target.isAssignableFrom(type)) {
									score++;
									if (!isCoercible(type, target)) {
										boolean found = false;
								for (ClassImplicitConvert convert : converts) {
											if (convert.support(type, target)) {
												invoker.addClassImplicitConvert(i, convert);
												found = true;
											}
										}
										invoker.setImplicit(found);
										if (!found) {
											score = -1;
											break;
										}
										score++;
									} else {
										score++;
									}
								}
							}
						}
					}
				}
				if (score > -1) {
					if (foundInvoker == null) {
						foundInvoker = invoker;
						foundScore = score;
					} else {
						if (score < foundScore) {
							foundScore = score;
							foundInvoker = invoker;
						}
					}
				}
			}
		}
		return foundInvoker;
	}

	/**
	 * Returns the method best matching the given signature, including type coercion, or null.
	 **/
	public static JavaInvoker<Method> findInvoker(Class<?> cls, String name, Class<?>[] parameterTypes) {
		return findInvoker(cls, name, parameterTypes, CONVERTS);
	}

	static JavaInvoker<Method> findInvoker(Class<?> cls, String name, Class<?>[] parameterTypes,
			List<ClassImplicitConvert> converts) {
		List<JavaInvoker<Method>> methodList = new ArrayList<>();
		Method[] methods = cls.getMethods();
		for (int i = 0, n = methods.length; i < n; i++) {
			Method method = methods[i];
			if (!method.getName().equals(name)) {
				continue;
			}
			if (method.getAnnotation(Transient.class) != null) {
				continue;
			}
			if (Modifier.isPublic(method.getModifiers())) {
				methodList.add(new MethodInvoker(method));
			}
		}
		return findMethodInvoker(methodList, parameterTypes, converts);
	}

	public static JavaInvoker<Method> findInvoker(Class<?> cls, String name) {
		return findInvoker(cls, name, new Class<?>[0]);
	}

	/**
	 * 是否可以自动装修拆箱
	 **/
	public static boolean isPrimitiveAssignableFrom(Class<?> from, Class<?> to) {
		if ((from == Boolean.class || from == boolean.class) && (to == boolean.class || to == Boolean.class)) {
			return true;
		}
		if ((from == Integer.class || from == int.class) && (to == int.class || to == Integer.class)) {
			return true;
		}
		if ((from == Float.class || from == float.class) && (to == float.class || to == Float.class)) {
			return true;
		}
		if ((from == Double.class || from == double.class) && (to == double.class || to == Double.class)) {
			return true;
		}
		if ((from == Byte.class || from == byte.class) && (to == byte.class || to == Byte.class)) {
			return true;
		}
		if ((from == Short.class || from == short.class) && (to == short.class || to == Short.class)) {
			return true;
		}
		if ((from == Long.class || from == long.class) && (to == long.class || to == Long.class)) {
			return true;
		}
		if ((from == Character.class || from == char.class) && (to == char.class || to == Character.class)) {
			return true;
		}
		return false;
	}

	/**
	 * 获取String类型的参数描述
	 */
	public static String[] getStringTypes(Object[] objects) {
		String[] parameterTypes = new String[objects == null ? 0 : objects.length];
		if (objects != null) {
			for (int i = 0, len = objects.length; i < len; i++) {
				Object value = objects[i];
				parameterTypes[i] = value == null ? "null" : value.getClass().getSimpleName();
			}
		}
		return parameterTypes;
	}

	/**
	 * 是否可以自动隐式转换
	 * https://docs.oracle.com/javase/specs/jls/se7/html/jls-5.html
	 **/
	private static boolean isCoercible(Class<?> from, Class<?> to) {
		if (from == Integer.class || from == int.class) {
			return to == float.class || to == Float.class || to == double.class || to == Double.class || to == long.class || to == Long.class;
		}

		if (from == Float.class || from == float.class) {
			return to == double.class || to == Double.class;
		}

		if (from == Double.class || from == double.class) {
			return false;
		}

		if (from == Character.class || from == char.class) {
			return to == int.class || to == Integer.class || to == float.class || to == Float.class || to == double.class || to == Double.class || to == long.class
					|| to == Long.class;
		}

		if (from == Byte.class || from == byte.class) {
			return to == int.class || to == Integer.class || to == float.class || to == Float.class || to == double.class || to == Double.class || to == long.class
					|| to == Long.class || to == short.class || to == Short.class;
		}

		if (from == Short.class || from == short.class) {
			return to == int.class || to == Integer.class || to == float.class || to == Float.class || to == double.class || to == Double.class || to == long.class
					|| to == Long.class;
		}

		if (from == Long.class || from == long.class) {
			return to == float.class || to == Float.class || to == double.class || to == Double.class;
		}

		if (from == int[].class || from == Integer[].class) {
			return to == Object[].class || to == float[].class || to == Float[].class || to == double[].class || to == Double[].class || to == long[].class || to == Long[].class;
		}

		return false;
	}

	/**
	 * 获取内部类
	 *
	 * @param obj  目标对象，可以是实例，可以是Class
	 * @param name 内部类名称
	 */
	public static Object getInnerClass(Object obj, String name) {
		Class cls = obj instanceof Class ? (Class) obj : obj.getClass();
		Class[] classes = cls.getDeclaredClasses();
		for (int i = 0, len = classes.length; i < len; i++) {
			Class clazz = classes[i];
			if (name.equalsIgnoreCase(clazz.getSimpleName())) {
				return clazz;
			}
		}
		return null;
	}

	/**
	 * 获取字段
	 *
	 * @param obj  目标对象可以是实例，可以是Class
	 * @param name 字段名称
	 * @return
	 */
	@SuppressWarnings("rawtypes")
	public static Field getField(Object obj, String name) {
		Class cls = obj instanceof Class ? (Class) obj : obj.getClass();
		BoundedResolutionCache<String, Optional<Field>> fields = FIELD_CACHE.get(cls);
		Optional<Field> cached = fields.get(name);
		if (cached != null) return cached.orElse(null);

		Field field = null;
		Class current = cls;
		while (current != null && current != Object.class) {
			try {
				field = current.getDeclaredField(name);
				if (field.getAnnotation(Transient.class) != null) {
					field = null;
				} else {
					try {
						field.setAccessible(true);
					} catch (RuntimeException exception) {
						throw new ScriptRuntimeException("Unable to access field '" + name
								+ "' on " + current.getName(), exception);
					}
					break;
				}
			} catch (NoSuchFieldException ignored) {
				// Continue with the superclass hierarchy.
			}
			current = current.getSuperclass();
		}
		return fields.putIfAbsent(name, Optional.ofNullable(field)).orElse(null);
	}

	/**
	 * 注册隐式转换器
	 */
	@Deprecated
	public static Registration registerImplicitConvert(ClassImplicitConvert classImplicitConvert) {
		ClassImplicitConvert convert = Objects.requireNonNull(classImplicitConvert, "classImplicitConvert");
		CONVERTS.add(convert);
		LEGACY_REGISTRY_EPOCH.incrementAndGet();
		return Registration.of(() -> {
			if (CONVERTS.remove(convert)) LEGACY_REGISTRY_EPOCH.incrementAndGet();
		});
	}


	/**
	 * 注册扩展方法
	 *
	 * @param target          目标类
	 * @param extensionObject 实现类
	 */
	@Deprecated
	public static Registration registerMethodExtension(Class<?> target, Object extensionObject) {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(extensionObject, "extensionObject");
		Class<?> clazz = extensionObject.getClass();
		EXTENSION_MAP.compute(target, (key, extensions) -> {
			List<Class<?>> result = extensions == null ? new CopyOnWriteArrayList<>() : extensions;
			result.add(clazz);
			return result;
		});
		Method[] methods = clazz.getDeclaredMethods();
		List<JavaInvoker<Method>> registered = new ArrayList<>();
		Map<String, List<JavaInvoker<Method>>> cachedMethodMap = EXTENSION_METHOD_CACHE.get(target);
		if (methods != null) {
			for (Method method : methods) {
				if (Modifier.isPublic(method.getModifiers()) && method.getParameterCount() > 0 && method.getAnnotation(Transient.class) == null) {
					List<JavaInvoker<Method>> cachedList = cachedMethodMap.computeIfAbsent(method.getName(), key -> new CopyOnWriteArrayList<>());
					JavaInvoker<Method> invoker = new MethodInvoker(method, extensionObject);
					cachedList.add(invoker);
					registered.add(invoker);
				}
			}
			Collection<List<JavaInvoker<Method>>> methodsValues = cachedMethodMap.values();
			for (List<JavaInvoker<Method>> methodList : methodsValues) {
				methodList.sort((m1, m2) -> {
					int sum1 = Arrays.stream(m1.getParameterTypes()).mapToInt(JavaReflection::calcToObjectDistance).sum();
					int sum2 = Arrays.stream(m2.getParameterTypes()).mapToInt(JavaReflection::calcToObjectDistance).sum();
					return sum2 - sum1;
				});
			}
		}
		LEGACY_REGISTRY_EPOCH.incrementAndGet();
		return Registration.of(() -> {
			for (JavaInvoker<Method> invoker : registered) {
				String methodName = invoker.getExecutable().getName();
				cachedMethodMap.computeIfPresent(methodName, (key, invokers) -> {
					invokers.remove(invoker);
					return invokers.isEmpty() ? null : invokers;
				});
			}
			EXTENSION_MAP.computeIfPresent(target, (key, extensions) -> {
				extensions.remove(clazz);
				return extensions.isEmpty() ? null : extensions;
			});
			LEGACY_REGISTRY_EPOCH.incrementAndGet();
		});
	}

	public static Object getFieldValue(Object obj, Field field) {
		try {
			return field.get(obj);
		} catch (IllegalAccessException | IllegalArgumentException exception) {
			throw new ScriptRuntimeException("Couldn't get value of field '" + field.getName()
					+ "' from object of type '" + obj.getClass().getSimpleName() + "'", exception);
		}
	}

	public static void setFieldValue(Object obj, Field field, Object value) {
		try {
			field.set(obj, value);
		} catch (IllegalAccessException | IllegalArgumentException exception) {
			throw new ScriptRuntimeException("Couldn't set value of field '" + field.getName()
					+ "' on object of type '" + obj.getClass().getSimpleName() + "'", exception);
		}
	}

	public static JavaInvoker<Method> getExtensionMethod(Object obj, String name, Object... arguments) {
		boolean isClass = obj instanceof Class;
		Class<?> cls = isClass ? Class.class : obj.getClass();
		if (cls.isArray()) {
			cls = Object[].class;
		}
		return getExtensionMethod(cls, name, arguments);
	}

	static Class<?>[] parameterTypes(Class<?> cls, Object... arguments) {
		int begin = cls == null ? 0 : 1;
		Class<?>[] parameterTypes = new Class[arguments.length + begin];
		if (begin > 0) {
			parameterTypes[0] = cls;
		}
		for (int i = 0; i < arguments.length; i++) {
			parameterTypes[i + begin] = arguments[i] == null ? Null.class : arguments[i].getClass();
		}
		return parameterTypes;
	}

	private static JavaInvoker<Method> getExtensionMethod(Class<?> cls, String name, Object... arguments) {
		if (cls == null) {
			cls = Object.class;
		}
		Map<String, List<JavaInvoker<Method>>> methodMap = EXTENSION_METHOD_CACHE.get(cls);
		List<JavaInvoker<Method>> methodList = methodMap.get(name);
		if (methodList != null) {
			return findMethodInvoker(methodList, parameterTypes(cls, arguments));
		}
		if (cls != Object.class) {
			Class<?>[] interfaces = cls.getInterfaces();
			for (Class<?> clazz : interfaces) {
				JavaInvoker<Method> invoker = getExtensionMethod(clazz, name, arguments);
				if (invoker != null) {
					return invoker;
				}
			}
			return getExtensionMethod(cls.getSuperclass(), name, arguments);
		}
		return null;
	}

	public static JavaInvoker<Method> getMethod(Object obj, String name, Object... arguments) {
		boolean isClass = obj instanceof Class;
		Class<?> cls = isClass ? (Class<?>) obj : (obj instanceof Function ? Function.class : obj.getClass());
		BoundedResolutionCache<MethodSignature, Optional<JavaInvoker<Method>>> methods = METHOD_CACHE.get(cls);

		Class<?>[] parameterTypes = parameterTypes(null, arguments);
		JavaReflection.MethodSignature signature = new MethodSignature(name, isClass, parameterTypes,
				LEGACY_REGISTRY_EPOCH.get());
		Optional<JavaInvoker<Method>> cached = methods.get(signature);
		if (cached != null) return cached.orElse(null);

		JavaInvoker<Method> invoker = name == null
				? findApply(cls) : findInvoker(cls, name, parameterTypes);
		if (invoker == null || invoker.isImplicit()) {
			JavaInvoker<Method> extensionInvoker = getExtensionMethod(obj, name, arguments);
			if (extensionInvoker != null) {
				extensionInvoker.setExtension(true);
				invoker = extensionInvoker;
			}
		}
		return methods.putIfAbsent(signature, Optional.ofNullable(invoker)).orElse(null);
	}

	static void clearLegacyResolutionCaches(Class<?> type) {
		Class<?> requiredType = Objects.requireNonNull(type, "type");
		METHOD_CACHE.remove(requiredType);
		FIELD_CACHE.remove(requiredType);
	}

	static int getLegacyMethodCacheSize(Class<?> type) {
		return METHOD_CACHE.get(Objects.requireNonNull(type, "type")).size();
	}

	static int getLegacyFieldCacheSize(Class<?> type) {
		return FIELD_CACHE.get(Objects.requireNonNull(type, "type")).size();
	}

	public static JavaInvoker<Method> getFunction(String name, Object... arguments) {
		List<JavaInvoker<Method>> methodList = FUNCTIONS.stream()
				.filter(it -> it.getExecutable().getName().equals(name))
				.collect(Collectors.toList());
		return findMethodInvoker(methodList, parameterTypes(null, arguments));
	}

	public static JavaInvoker<Method> getMethod(ScriptContext context, Object target,
			String name, Object... arguments) {
		ScriptReflectionRegistry registry = context == null ? null : context.getReflectionRegistry();
		return registry == null ? getMethod(target, name, arguments)
				: registry.getMethod(target, name, arguments);
	}

	public static JavaInvoker<Method> getFunction(ScriptContext context, String name,
			Object... arguments) {
		ScriptReflectionRegistry registry = context == null ? null : context.getReflectionRegistry();
		return registry == null ? getFunction(name, arguments)
				: registry.getFunction(name, arguments);
	}

	/**
	 * NULL值
	 */
	public static final class Null {

	}

	private static void installDefaults() {
		registerMethodExtension(Class.class, new ClassExtension());
		registerImplicitConvert(new FunctionalImplicitConvert());
	}

	private static void clearGlobalRegistry() {
		for (Class<?> type : new ArrayList<>(EXTENSION_MAP.keySet())) {
			EXTENSION_METHOD_CACHE.remove(type);
		}
		EXTENSION_MAP.clear();
		FUNCTIONS.clear();
		CONVERTS.clear();
		LEGACY_REGISTRY_EPOCH.incrementAndGet();
	}

	public static final class GlobalRegistrySnapshot {
		private final List<ClassImplicitConvert> converts;
		private final List<JavaInvoker<Method>> functions;
		private final Map<Class<?>, List<Class<?>>> extensionTypes;
		private final Map<Class<?>, Map<String, List<JavaInvoker<Method>>>> extensionMethods;

		private GlobalRegistrySnapshot(List<ClassImplicitConvert> converts,
				List<JavaInvoker<Method>> functions, Map<Class<?>, List<Class<?>>> extensionTypes,
				Map<Class<?>, Map<String, List<JavaInvoker<Method>>>> extensionMethods) {
			this.converts = converts;
			this.functions = functions;
			this.extensionTypes = extensionTypes;
			this.extensionMethods = extensionMethods;
		}

		public int getFunctionCount() {
			return functions.size();
		}
	}

	/**
	 * 方法签名
	 */
	private static class MethodSignature {
		private final String name;
		private final boolean classTarget;
		private final long registryEpoch;
		@SuppressWarnings("rawtypes")
		private final Class[] parameters;
		private final int hashCode;

		@SuppressWarnings("rawtypes")
		MethodSignature(String name, boolean classTarget, Class[] parameters, long registryEpoch) {
			this.name = name;
			this.classTarget = classTarget;
			this.registryEpoch = registryEpoch;
			this.parameters = parameters.clone();
			final int prime = 31;
			int hash = 1;
			hash = prime * hash + ((name == null) ? 0 : name.hashCode());
			hash = prime * hash + Boolean.hashCode(classTarget);
			hash = prime * hash + Long.hashCode(registryEpoch);
			hash = prime * hash + Arrays.hashCode(parameters);
			hashCode = hash;
		}

		@Override
		public int hashCode() {
			return hashCode;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (getClass() != obj.getClass()) {
				return false;
			}
			JavaReflection.MethodSignature other = (JavaReflection.MethodSignature) obj;
			if (classTarget != other.classTarget || registryEpoch != other.registryEpoch) {
				return false;
			}
			if (name == null) {
				if (other.name != null) {
					return false;
				}
			} else if (!name.equals(other.name)) {
				return false;
			}
			if (!Arrays.equals(parameters, other.parameters)) {
				return false;
			}
			return true;
		}
	}

	private static final class BoundedResolutionCache<K, V> {
		private final int maximumSize;
		private final ConcurrentMap<K, V> values = new ConcurrentHashMap<>();
		private final ConcurrentLinkedQueue<K> insertionOrder = new ConcurrentLinkedQueue<>();

		private BoundedResolutionCache(int maximumSize) {
			this.maximumSize = maximumSize;
		}

		private V get(K key) {
			return values.get(key);
		}

		private V putIfAbsent(K key, V value) {
			V existing = values.putIfAbsent(key, value);
			if (existing != null) return existing;
			insertionOrder.offer(key);
			while (values.size() > maximumSize) {
				K eldest = insertionOrder.poll();
				if (eldest == null) break;
				values.remove(eldest);
			}
			return value;
		}

		private int size() {
			return values.size();
		}
	}
}
