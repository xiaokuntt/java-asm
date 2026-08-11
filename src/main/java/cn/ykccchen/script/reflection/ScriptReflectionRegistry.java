package cn.ykccchen.script.reflection;

import cn.ykccchen.script.convert.ClassImplicitConvert;

import java.beans.Transient;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.WeakHashMap;

/** Immutable reflection extensions owned by one script engine. */
public final class ScriptReflectionRegistry {

	private final List<ClassImplicitConvert> converts;
	private final List<JavaInvoker<Method>> functions;
	private final Map<String, List<JavaInvoker<Method>>> functionsByName;
	private final Map<Class<?>, Map<String, List<JavaInvoker<Method>>>> extensions;
	private final int cacheLimit;
	private final BoundedResolutionCache<Signature, Optional<JavaInvoker<Method>>> functionCache;
	private final Map<Class<?>, Boolean> observedTypes =
			Collections.synchronizedMap(new WeakHashMap<Class<?>, Boolean>());
	private final LongAdder hits = new LongAdder();
	private final LongAdder misses = new LongAdder();
	private final LongAdder evictions = new LongAdder();
	private final ClassValue<BoundedResolutionCache<Signature, Optional<JavaInvoker<Method>>>> methodCache =
			new ClassValue<BoundedResolutionCache<Signature, Optional<JavaInvoker<Method>>>>() {
				@Override
				protected BoundedResolutionCache<Signature, Optional<JavaInvoker<Method>>> computeValue(Class<?> type) {
					observedTypes.put(type, Boolean.TRUE);
					return new BoundedResolutionCache<>(cacheLimit, evictions);
				}
			};

	public ScriptReflectionRegistry(List<Object> functionTargets,
			Map<Class<?>, List<Object>> extensionTargets, List<ClassImplicitConvert> converts) {
		this(functionTargets, extensionTargets, converts, 256);
	}

	public ScriptReflectionRegistry(List<Object> functionTargets,
			Map<Class<?>, List<Object>> extensionTargets, List<ClassImplicitConvert> converts,
			int cacheLimit) {
		if (cacheLimit <= 0) throw new IllegalArgumentException("cacheLimit must be greater than zero");
		this.cacheLimit = cacheLimit;
		this.functionCache = new BoundedResolutionCache<>(cacheLimit, evictions);
		this.converts = Collections.unmodifiableList(new ArrayList<>(converts));
		this.functions = Collections.unmodifiableList(buildFunctions(functionTargets));
		this.functionsByName = indexFunctions(functions);
		this.extensions = buildExtensions(extensionTargets);
	}

	public JavaInvoker<Method> getFunction(String name, Object... arguments) {
		Class<?>[] parameterTypes = JavaReflection.parameterTypes(null, arguments);
		Signature signature = new Signature(name, false, parameterTypes);
		Optional<JavaInvoker<Method>> cached = functionCache.get(signature);
		if (cached != null) {
			hits.increment();
			return cached.orElse(null);
		}
		misses.increment();
		cached = Optional.ofNullable(JavaReflection.findMethodInvoker(
				functionsByName.getOrDefault(name, Collections.<JavaInvoker<Method>>emptyList()),
				parameterTypes, converts));
		cached = functionCache.putIfAbsent(signature, cached);
		return cached.orElse(null);
	}

	public JavaInvoker<Method> getMethod(Object target, String name, Object... arguments) {
		Objects.requireNonNull(target, "target");
		Class<?> type = target instanceof Class ? (Class<?>) target
				: target instanceof java.util.function.Function ? java.util.function.Function.class
				: target.getClass();
		Class<?>[] parameterTypes = JavaReflection.parameterTypes(null, arguments);
		boolean classTarget = target instanceof Class;
		Signature signature = new Signature(name, classTarget, parameterTypes);
		BoundedResolutionCache<Signature, Optional<JavaInvoker<Method>>> cache = methodCache.get(type);
		Optional<JavaInvoker<Method>> cached = cache.get(signature);
		if (cached != null) {
			hits.increment();
			return cached.orElse(null);
		}
		misses.increment();
		cached = resolveMethod(type, classTarget, name, arguments, parameterTypes);
		cached = cache.putIfAbsent(signature, cached);
		return cached.orElse(null);
	}

	private Optional<JavaInvoker<Method>> resolveMethod(Class<?> type, boolean classTarget,
			String name, Object[] arguments, Class<?>[] parameterTypes) {
			JavaInvoker<Method> direct = JavaReflection.findInvoker(type, name, parameterTypes, converts);
			Class<?> extensionType = classTarget ? Class.class : normalizeArray(type);
			JavaInvoker<Method> extension = getExtensionMethod(extensionType, name, arguments);
			if (extension != null && (direct == null || direct.isImplicit())) {
				extension.setExtension(true);
				return Optional.of(extension);
			}
			return Optional.ofNullable(direct);
	}

	public ScriptReflectionStats stats() {
		return new ScriptReflectionStats(hits.sum(), misses.sum(), evictions.sum(),
				functionCache.size(), totalMethodCacheSize(), cacheLimit);
	}

	public void clearCaches() {
		functionCache.clear();
		synchronized (observedTypes) {
			for (Class<?> type : new ArrayList<>(observedTypes.keySet())) methodCache.remove(type);
			observedTypes.clear();
		}
	}

	private int totalMethodCacheSize() {
		int size = 0;
		synchronized (observedTypes) {
			for (Class<?> type : observedTypes.keySet()) size += methodCache.get(type).size();
		}
		return size;
	}

	public int getCachedMethodCount(Class<?> type) {
		return methodCache.get(Objects.requireNonNull(type, "type")).size();
	}

	public int getCachedFunctionCount() {
		return functionCache.size();
	}

	public List<ClassImplicitConvert> getImplicitConverts() {
		return converts;
	}

	private JavaInvoker<Method> getExtensionMethod(Class<?> type, String name, Object[] arguments) {
		if (type == null) {
			type = Object.class;
		}
		Map<String, List<JavaInvoker<Method>>> methods = extensions.get(type);
		if (methods != null) {
			List<JavaInvoker<Method>> candidates = methods.get(name);
			if (candidates != null) {
				JavaInvoker<Method> found = JavaReflection.findMethodInvoker(candidates,
						JavaReflection.parameterTypes(type, arguments), converts);
				if (found != null) {
					return found;
				}
			}
		}
		for (Class<?> interfaceType : type.getInterfaces()) {
			JavaInvoker<Method> found = getExtensionMethod(interfaceType, name, arguments);
			if (found != null) {
				return found;
			}
		}
		return type == Object.class ? null : getExtensionMethod(type.getSuperclass(), name, arguments);
	}

	private static List<JavaInvoker<Method>> buildFunctions(List<Object> targets) {
		List<JavaInvoker<Method>> result = new ArrayList<>();
		for (Object target : targets) {
			for (Method method : target.getClass().getMethods()) {
				if (method.getAnnotation(cn.ykccchen.script.annotation.Function.class) != null) {
					result.add(new MethodInvoker(method, target));
				}
			}
		}
		return result;
	}

	private static Map<String, List<JavaInvoker<Method>>> indexFunctions(List<JavaInvoker<Method>> functions) {
		Map<String, List<JavaInvoker<Method>>> indexed = new LinkedHashMap<>();
		for (JavaInvoker<Method> function : functions) {
			indexed.computeIfAbsent(function.getExecutable().getName(), ignored -> new ArrayList<>())
					.add(function);
		}
		Map<String, List<JavaInvoker<Method>>> immutable = new LinkedHashMap<>();
		indexed.forEach((name, values) -> immutable.put(name,
				Collections.unmodifiableList(new ArrayList<>(values))));
		return Collections.unmodifiableMap(immutable);
	}

	private static Map<Class<?>, Map<String, List<JavaInvoker<Method>>>> buildExtensions(
			Map<Class<?>, List<Object>> targets) {
		Map<Class<?>, Map<String, List<JavaInvoker<Method>>>> result = new LinkedHashMap<>();
		for (Map.Entry<Class<?>, List<Object>> entry : targets.entrySet()) {
			Map<String, List<JavaInvoker<Method>>> methods = new LinkedHashMap<>();
			for (Object target : entry.getValue()) {
				for (Method method : target.getClass().getDeclaredMethods()) {
					if (Modifier.isPublic(method.getModifiers()) && method.getParameterCount() > 0
							&& method.getAnnotation(Transient.class) == null) {
						methods.computeIfAbsent(method.getName(), ignored -> new ArrayList<>())
								.add(new MethodInvoker(method, target));
					}
				}
			}
			Map<String, List<JavaInvoker<Method>>> immutableMethods = new LinkedHashMap<>();
			methods.forEach((name, invokers) -> immutableMethods.put(name,
					Collections.unmodifiableList(new ArrayList<>(invokers))));
			result.put(entry.getKey(), Collections.unmodifiableMap(immutableMethods));
		}
		return Collections.unmodifiableMap(result);
	}

	private static Class<?> normalizeArray(Class<?> type) {
		return type.isArray() ? Object[].class : type;
	}

	private static final class Signature {
		private final String name;
		private final boolean classTarget;
		private final Class<?>[] parameterTypes;
		private final int hashCode;

		private Signature(String name, boolean classTarget, Class<?>[] parameterTypes) {
			this.name = name;
			this.classTarget = classTarget;
			this.parameterTypes = parameterTypes.clone();
			this.hashCode = 31 * (31 * Objects.hashCode(name) + Boolean.hashCode(classTarget))
					+ Arrays.hashCode(parameterTypes);
		}

		@Override public int hashCode() { return hashCode; }

		@Override
		public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof Signature)) return false;
			Signature other = (Signature) object;
			return classTarget == other.classTarget && Objects.equals(name, other.name)
					&& Arrays.equals(parameterTypes, other.parameterTypes);
		}
	}

	private static final class BoundedResolutionCache<K, V> {
		private final int maximumSize;
		private final LongAdder evictions;
		private final ConcurrentMap<K, V> values = new ConcurrentHashMap<>();
		private final ConcurrentLinkedQueue<K> insertionOrder = new ConcurrentLinkedQueue<>();

		private BoundedResolutionCache(int maximumSize, LongAdder evictions) {
			this.maximumSize = maximumSize;
			this.evictions = evictions;
		}

		private V get(K key) { return values.get(key); }

		private V putIfAbsent(K key, V value) {
			V existing = values.putIfAbsent(key, value);
			if (existing != null) return existing;
			insertionOrder.offer(key);
			while (values.size() > maximumSize) {
				K eldest = insertionOrder.poll();
				if (eldest == null) break;
				if (values.remove(eldest) != null) evictions.increment();
			}
			return value;
		}

		private int size() { return values.size(); }
		private void clear() { values.clear(); insertionOrder.clear(); }
	}
}
