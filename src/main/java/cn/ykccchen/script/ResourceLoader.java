package cn.ykccchen.script;

import cn.ykccchen.script.exception.ResourceNotFoundException;
import cn.ykccchen.script.exception.ScriptSecurityException;
import cn.ykccchen.script.exception.ScriptRuntimeException;
import cn.ykccchen.script.functions.DynamicModuleImport;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 资源加载器
 */
public class ResourceLoader {

	/**
	 * 保存自动导入的包路径
	 */
	private static final ConcurrentMap<String, AtomicInteger> PACKAGES = new ConcurrentHashMap<>();
	/**
	 * 函数加载器
	 */
	private static final List<BiFunction<ScriptContext, String, Object>> FUNCTION_LOADERS = new CopyOnWriteArrayList<>();
	/**
	 * JSR223 脚本函数加载器
	 */
	private static final List<Function<String, BiFunction<Map<String, Object>, String, Object>>> SCRIPT_LANGUAGE_LOADERS = new CopyOnWriteArrayList<>();
	/**
	 * 保存已注册的模块
	 */
	private static final ConcurrentMap<String, Object> MODULES = new ConcurrentHashMap<>();
	/**
	 * 默认的类加载器
	 */
	private static final Function<String, Object> DEFAULT_CLASS_LOADER = (className) -> {
		try {
			return Class.forName(className);
		} catch (Exception e) {
			return null;
		}
	};
	private static volatile Function<String, Object> classLoader = DEFAULT_CLASS_LOADER;
	private static volatile ScriptAccessPolicy accessPolicy = ScriptAccessPolicy.allowAll();

	static {
		// 默认导入 java.util.* 、java.lang.*
		addPackage("java.util.*");
		addPackage("java.lang.*");
	}

	/**
	 * 获取已注册的模块信息，此方法主要用于代码提示
	 */
	public static Map<String, ScriptClass> getModules() {
		return MODULES.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> {
			ScriptClass scriptClass;
			if (entry.getValue() instanceof Class) {
				Class<?> clazz = (Class<?>) entry.getValue();
				scriptClass = JvmScriptEngine.getScriptClassFromClass(clazz);
			} else if (entry.getValue() instanceof DynamicModuleImport) {
				DynamicModuleImport dmi = (DynamicModuleImport) entry.getValue();
				scriptClass = JvmScriptEngine.getScriptClassFromClass(dmi.getTargetClass());
			} else {
				scriptClass = JvmScriptEngine.getScriptClassFromClass(entry.getValue().getClass());
			}
			scriptClass.setModule(true);
			return scriptClass;
		}));
	}

	/** Captures legacy global state so compatibility tests can restore it safely. */
	public static GlobalRegistrySnapshot snapshotGlobalRegistry() {
		Map<String, Integer> packages = new HashMap<>();
		PACKAGES.forEach((name, count) -> packages.put(name, count.get()));
		return new GlobalRegistrySnapshot(packages, new ArrayList<>(FUNCTION_LOADERS),
				new ArrayList<>(SCRIPT_LANGUAGE_LOADERS), new HashMap<>(MODULES),
				classLoader, accessPolicy);
	}

	/** Resets only the deprecated process-wide registry; engine-scoped configs are unaffected. */
	public static synchronized void resetGlobalRegistry() {
		PACKAGES.clear();
		FUNCTION_LOADERS.clear();
		SCRIPT_LANGUAGE_LOADERS.clear();
		MODULES.clear();
		classLoader = DEFAULT_CLASS_LOADER;
		accessPolicy = ScriptAccessPolicy.allowAll();
		addPackage("java.util.*");
		addPackage("java.lang.*");
	}

	public static synchronized void restoreGlobalRegistry(GlobalRegistrySnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot");
		PACKAGES.clear();
		snapshot.packages.forEach((name, count) -> PACKAGES.put(name, new AtomicInteger(count)));
		FUNCTION_LOADERS.clear();
		FUNCTION_LOADERS.addAll(snapshot.functionLoaders);
		SCRIPT_LANGUAGE_LOADERS.clear();
		SCRIPT_LANGUAGE_LOADERS.addAll(snapshot.scriptLanguageLoaders);
		MODULES.clear();
		MODULES.putAll(snapshot.modules);
		classLoader = snapshot.classLoader;
		accessPolicy = snapshot.accessPolicy;
	}

	/**
	 * 添加函数加载器
	 */
	@Deprecated
	public static Registration addFunctionLoader(BiFunction<ScriptContext, String, Object> functionLoader) {
		BiFunction<ScriptContext, String, Object> loader = Objects.requireNonNull(functionLoader, "functionLoader");
		FUNCTION_LOADERS.add(loader);
		return Registration.of(() -> FUNCTION_LOADERS.remove(loader));
	}

	/**
	 * 设置类加载器
	 */
	@Deprecated
	public static void setClassLoader(Function<String, Object> classLoader) {
		ResourceLoader.classLoader = Objects.requireNonNull(classLoader, "classLoader");
	}

	@Deprecated
	public static void setAccessPolicy(ScriptAccessPolicy accessPolicy) {
		ResourceLoader.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
	}

	public static ScriptAccessPolicy getAccessPolicy() {
		return accessPolicy;
	}

	public static void resetAccessPolicy() {
		accessPolicy = ScriptAccessPolicy.allowAll();
	}

	public static void checkClassAccess(String className) {
		checkClassAccess(null, className);
	}

	public static void checkClassAccess(ScriptContext context, String className) {
		ScriptEngineConfig config = getConfig(context);
		if (config != null) {
			config.checkClassAccess(className);
		} else if (!accessPolicy.allowClass(className)) {
			throw new ScriptSecurityException("禁止访问Java类：" + className);
		}
	}

	public static void checkMemberAccess(Object target, String memberName) {
		checkMemberAccess(null, target, memberName);
	}

	public static void checkMemberAccess(ScriptContext context, Object target, String memberName) {
		Class<?> owner = target instanceof Class ? (Class<?>) target : target.getClass();
		if (context != null) {
			context.recordHostAccess();
		}
		ScriptEngineConfig config = getConfig(context);
		if (config != null) {
			config.checkMemberAccess(target, memberName);
			return;
		}
		if (!accessPolicy.allowClass(owner.getName()) || !accessPolicy.allowMember(owner, memberName)) {
			throw new ScriptSecurityException("禁止访问Java成员：" + owner.getName() + "." + memberName);
		}
	}

	public static void checkResolvedMemberAccess(ScriptContext context, Class<?> owner, String memberName) {
		ScriptEngineConfig config = getConfig(context);
		if (config != null) {
			config.checkResolvedMemberAccess(owner, memberName);
		} else if (!accessPolicy.allowClass(owner.getName())
				|| !accessPolicy.allowMember(owner, memberName)) {
			throw new ScriptSecurityException("禁止访问Java成员：" + owner.getName() + "." + memberName);
		}
	}

	public static void recordHostCall(ScriptContext context, Class<?> owner, String memberName) {
		if (context != null) context.beforeHostCall(owner, memberName);
	}

	/**
	 * 添加模块
	 *
	 * @param moduleName 模块名称
	 * @param target     模块，可以是对象实例，也可以是Class类型的，此时只能使用类中的静态方法
	 */
	@Deprecated
	public static Registration addModule(String moduleName, Object target) {
		String name = Objects.requireNonNull(moduleName, "moduleName");
		Object module = Objects.requireNonNull(target, "target");
		MODULES.put(name, module);
		return Registration.of(() -> MODULES.remove(name, module));
	}

	/**
	 * 加载模块
	 *
	 * @param moduleName 模块名称
	 */
	public static Object loadModule(ScriptContext context, String moduleName) {
		ScriptEngineConfig config = getConfig(context);
		return config == null ? loadModule(moduleName) : config.loadModule(moduleName);
	}

	/**
	 * 加载模块
	 *
	 * @param moduleName 模块名称
	 */
	public static Object loadModule(String moduleName) {
		Object module = MODULES.get(moduleName);
		if (module == null){
			throw new ResourceNotFoundException("找不到模块：" + moduleName);
		}
		return module;
	}

	/**
	 * 加载类
	 *
	 * @param className 类全限定名
	 */
	public static Object loadClass(ScriptContext context, String className) {
		ScriptEngineConfig config = getConfig(context);
		if (config != null) {
			return config.loadClass(className);
		}
		checkClassAccess(context, className);
		Object value = classLoader.apply(className);
		if (value == null) {
			throw new ResourceNotFoundException("找不到类：" + className);
		}
		return value;
	}

	/**
	 * 通过类全名获取类
	 *
	 * @param className 类全限定名
	 */
	public static Class<?> forName(String className) throws ClassNotFoundException{
		return forName(null, className);
	}

	public static Class<?> forName(ScriptContext context, String className) throws ClassNotFoundException{
		ScriptEngineConfig config = getConfig(context);
		if (config != null) {
			return config.forName(className);
		}
		checkClassAccess(context, className);
		Object obj = classLoader.apply(className);
		if (obj == null) {
			throw new ClassNotFoundException(className);
		}
		return obj instanceof Class ? (Class<?>)obj : obj.getClass();
	}

	/**
	 * 获取可用的模块列表
	 */
	public static Set<String> getModuleNames() {
		return Collections.unmodifiableSet(new HashSet<>(MODULES.keySet()));
	}

	/**
	 * 添加自动导包
	 *
	 * @param prefix 包前缀，如java.lang.*， 不支持 java.lang.**.*
	 */
	@Deprecated
	public static Registration addPackage(String prefix) {
		String normalized = Objects.requireNonNull(prefix, "prefix").replace("*", "");
		PACKAGES.computeIfAbsent(normalized, key -> new AtomicInteger()).incrementAndGet();
		return Registration.of(() -> PACKAGES.computeIfPresent(normalized, (key, count) ->
				count.decrementAndGet() == 0 ? null : count));
	}

	/**
	 * 加载类
	 *
	 * @param simpleName 类缩写，如HashMap、ArrayList
	 */
	public static Class<?> findClass(String simpleName) {
		return findClass(null, simpleName);
	}

	public static Class<?> findClass(ScriptContext context, String simpleName) {
		ScriptEngineConfig config = getConfig(context);
		if (config != null) {
			return config.findClass(simpleName);
		}
		for (String prefix : PACKAGES.keySet()) {
			String className = prefix + simpleName;
			if (!accessPolicy.allowClass(className)) {
				continue;
			}
			try {
				return forName(context, className);
			} catch (ClassNotFoundException ignored) {
			}
		}
		return null;
	}

	/**
	 * 添加JSR223 脚本函数加载器
	 */
	@Deprecated
	public static Registration addScriptLanguageLoader(Function<String, BiFunction<Map<String, Object>, String, Object>> loader) {
		Function<String, BiFunction<Map<String, Object>, String, Object>> languageLoader = Objects.requireNonNull(loader, "loader");
		SCRIPT_LANGUAGE_LOADERS.add(languageLoader);
		return Registration.of(() -> SCRIPT_LANGUAGE_LOADERS.remove(languageLoader));
	}

	/**
	 * 加载脚本函数加载器
	 *
	 * @param name 脚本名称
	 */
	public static BiFunction<Map<String, Object>, String, Object> loadScriptLanguage(String name) {
		return loadScriptLanguage(null, name);
	}

	public static BiFunction<Map<String, Object>, String, Object> loadScriptLanguage(ScriptContext context, String name) {
		ScriptEngineConfig config = getConfig(context);
		if (config != null) {
			return config.loadScriptLanguage(name);
		}
		for (Function<String, BiFunction<Map<String, Object>, String, Object>> languageLoader : SCRIPT_LANGUAGE_LOADERS) {
			try {
				BiFunction<Map<String, Object>, String, Object> function = languageLoader.apply(name);
				if (function != null) {
					return function;
				}
			} catch (Exception e) {
				throw new ScriptRuntimeException("加载脚本语言失败：" + name, e);
			}
		}
		throw new ResourceNotFoundException("找不到语言：" + name);
	}

	/**
	 * 加载函数加载器
	 *
	 * @param name 函数名称
	 */
	public static Object loadFunction(ScriptContext context, String name) {
		ScriptEngineConfig config = getConfig(context);
		if (config != null) {
			return config.loadFunction(context, name);
		}
		for (BiFunction<ScriptContext, String, Object> loader : FUNCTION_LOADERS) {
			try {
				Object value = loader.apply(context, name);
				if (value != null) {
					return value;
				}
			} catch (Exception e) {
				throw new ScriptRuntimeException("加载函数失败：" + name, e);
			}
		}
		throw new ResourceNotFoundException("找不到函数：" + name);
	}

	private static ScriptEngineConfig getConfig(ScriptContext context) {
		return context == null ? null : context.getEngineConfig();
	}

	public static final class GlobalRegistrySnapshot {
		private final Map<String, Integer> packages;
		private final List<BiFunction<ScriptContext, String, Object>> functionLoaders;
		private final List<Function<String, BiFunction<Map<String, Object>, String, Object>>> scriptLanguageLoaders;
		private final Map<String, Object> modules;
		private final Function<String, Object> classLoader;
		private final ScriptAccessPolicy accessPolicy;

		private GlobalRegistrySnapshot(Map<String, Integer> packages,
				List<BiFunction<ScriptContext, String, Object>> functionLoaders,
				List<Function<String, BiFunction<Map<String, Object>, String, Object>>> scriptLanguageLoaders,
				Map<String, Object> modules, Function<String, Object> classLoader,
				ScriptAccessPolicy accessPolicy) {
			this.packages = packages;
			this.functionLoaders = functionLoaders;
			this.scriptLanguageLoaders = scriptLanguageLoaders;
			this.modules = modules;
			this.classLoader = classLoader;
			this.accessPolicy = accessPolicy;
		}

		public Set<String> getPackages() {
			return Collections.unmodifiableSet(packages.keySet());
		}

		public Set<String> getModuleNames() {
			return Collections.unmodifiableSet(modules.keySet());
		}
	}
}
