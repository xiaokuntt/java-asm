package cn.ykccchen.script;

import cn.ykccchen.script.exception.ResourceNotFoundException;
import cn.ykccchen.script.exception.ScriptRuntimeException;
import cn.ykccchen.script.convert.ClassImplicitConvert;
import cn.ykccchen.script.convert.FunctionalImplicitConvert;
import cn.ykccchen.script.functions.ClassExtension;
import cn.ykccchen.script.reflection.ScriptReflectionRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Immutable resources and access rules owned by one script engine.
 */
public final class ScriptEngineConfig {

	private final Map<String, Object> defaultImports;
	private final Map<String, Object> modules;
	private final Set<String> packages;
	private final List<BiFunction<ScriptContext, String, Object>> functionLoaders;
	private final List<Function<String, BiFunction<Map<String, Object>, String, Object>>> scriptLanguageLoaders;
	private final Function<String, Object> classLoader;
	private final ScriptAccessPolicy accessPolicy;
	private final ScriptExecutionLimits executionLimits;
	private final List<ScriptExecutionListener> executionListeners;
	private final ExecutorService languageAsyncExecutor;
	private final ExecutorService languageBlockingExecutor;
	private final int languageBlockingThreads;
	private final int languageBlockingQueueCapacity;
	private final ScheduledExecutorService languageAsyncScheduler;
	private final ScriptAsyncPolicy languageAsyncPolicy;
	private final List<ScriptTaskListener> languageAsyncListeners;
	private final int compileCacheSize;
	private final int classLoaderGenerationSize;
	private final ScriptReflectionRegistry reflectionRegistry;
	private final int reflectionCacheSize;

	private ScriptEngineConfig(Builder builder) {
		this.defaultImports = immutableMap(builder.defaultImports);
		this.modules = immutableMap(builder.modules);
		this.packages = Collections.unmodifiableSet(new LinkedHashSet<>(builder.packages));
		this.functionLoaders = Collections.unmodifiableList(new ArrayList<>(builder.functionLoaders));
		this.scriptLanguageLoaders = Collections.unmodifiableList(new ArrayList<>(builder.scriptLanguageLoaders));
		this.classLoader = builder.classLoader;
		this.accessPolicy = builder.accessPolicy;
		this.executionLimits = builder.executionLimits;
		this.executionListeners = Collections.unmodifiableList(new ArrayList<>(builder.executionListeners));
		this.languageAsyncExecutor = builder.languageAsyncExecutor;
		this.languageBlockingExecutor = builder.languageBlockingExecutor;
		this.languageBlockingThreads = builder.languageBlockingThreads;
		this.languageBlockingQueueCapacity = builder.languageBlockingQueueCapacity;
		this.languageAsyncScheduler = builder.languageAsyncScheduler;
		this.languageAsyncPolicy = builder.languageAsyncPolicy;
		this.languageAsyncListeners = Collections.unmodifiableList(new ArrayList<>(builder.languageAsyncListeners));
		this.compileCacheSize = builder.compileCacheSize;
		this.classLoaderGenerationSize = builder.classLoaderGenerationSize;
		this.reflectionCacheSize = builder.reflectionCacheSize;
		this.reflectionRegistry = new ScriptReflectionRegistry(builder.functionTargets,
				builder.extensionTargets, builder.implicitConverts, reflectionCacheSize);
	}

	private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
		return Collections.unmodifiableMap(new LinkedHashMap<>(source));
	}

	public static Builder builder() {
		return new Builder();
	}

	public Map<String, Object> getDefaultImports() {
		return defaultImports;
	}

	public Set<String> getModuleNames() {
		return modules.keySet();
	}

	public Set<String> getPackages() {
		return packages;
	}

	public ScriptAccessPolicy getAccessPolicy() {
		return accessPolicy;
	}

	public ScriptExecutionLimits getExecutionLimits() {
		return executionLimits;
	}

	public int getCompileCacheSize() {
		return compileCacheSize;
	}

	public int getClassLoaderGenerationSize() {
		return classLoaderGenerationSize;
	}

	public ScriptReflectionRegistry getReflectionRegistry() {
		return reflectionRegistry;
	}

	public int getReflectionCacheSize() { return reflectionCacheSize; }

	public List<ScriptExecutionListener> getExecutionListeners() {
		return executionListeners;
	}

	public ExecutorService getLanguageAsyncExecutor() {
		return languageAsyncExecutor;
	}

	public ExecutorService getLanguageBlockingExecutor() {
		return languageBlockingExecutor;
	}

	public int getLanguageBlockingThreads() {
		return languageBlockingThreads;
	}

	public int getLanguageBlockingQueueCapacity() {
		return languageBlockingQueueCapacity;
	}

	public ScheduledExecutorService getLanguageAsyncScheduler() {
		return languageAsyncScheduler;
	}

	public ScriptAsyncPolicy getLanguageAsyncPolicy() {
		return languageAsyncPolicy;
	}

	public List<ScriptTaskListener> getLanguageAsyncListeners() {
		return languageAsyncListeners;
	}

	Object loadModule(String moduleName) {
		Object module = modules.get(moduleName);
		if (module == null) {
			throw new ResourceNotFoundException("找不到模块：" + moduleName);
		}
		return module;
	}

	Object loadClass(String className) {
		checkClassAccess(className);
		Object value = classLoader.apply(className);
		if (value == null) {
			throw new ResourceNotFoundException("找不到类：" + className);
		}
		return value;
	}

	Class<?> forName(String className) throws ClassNotFoundException {
		checkClassAccess(className);
		Object value = classLoader.apply(className);
		if (value == null) {
			throw new ClassNotFoundException(className);
		}
		return value instanceof Class ? (Class<?>) value : value.getClass();
	}

	Class<?> findClass(String simpleName) {
		for (String prefix : packages) {
			String className = prefix + simpleName;
			if (!accessPolicy.allowClass(className)) {
				continue;
			}
			try {
				return forName(className);
			} catch (ClassNotFoundException ignored) {
				// Try the next configured package.
			}
		}
		return null;
	}

	Object loadFunction(ScriptContext context, String name) {
		for (BiFunction<ScriptContext, String, Object> loader : functionLoaders) {
			try {
				Object value = loader.apply(context, name);
				if (value != null) {
					return value;
				}
			} catch (Exception exception) {
				throw new ScriptRuntimeException("加载函数失败：" + name, exception);
			}
		}
		throw new ResourceNotFoundException("找不到函数：" + name);
	}

	BiFunction<Map<String, Object>, String, Object> loadScriptLanguage(String name) {
		for (Function<String, BiFunction<Map<String, Object>, String, Object>> loader : scriptLanguageLoaders) {
			try {
				BiFunction<Map<String, Object>, String, Object> function = loader.apply(name);
				if (function != null) {
					return function;
				}
			} catch (Exception exception) {
				throw new ScriptRuntimeException("加载脚本语言失败：" + name, exception);
			}
		}
		throw new ResourceNotFoundException("找不到语言：" + name);
	}

	void checkClassAccess(String className) {
		if (!accessPolicy.allowClass(className)) {
			throw new cn.ykccchen.script.exception.ScriptSecurityException("禁止访问Java类：" + className);
		}
	}

	void checkMemberAccess(Object target, String memberName) {
		Class<?> owner = target instanceof Class ? (Class<?>) target : target.getClass();
		if (!accessPolicy.allowClass(owner.getName()) || !accessPolicy.allowMember(owner, memberName)) {
			throw new cn.ykccchen.script.exception.ScriptSecurityException(
					"禁止访问Java成员：" + owner.getName() + "." + memberName);
		}
	}

	void checkResolvedMemberAccess(Class<?> owner, String memberName) {
		if (!accessPolicy.allowClass(owner.getName()) || !accessPolicy.allowMember(owner, memberName)) {
			throw new cn.ykccchen.script.exception.ScriptSecurityException(
					"禁止访问Java成员：" + owner.getName() + "." + memberName);
		}
	}

	public static final class Builder {

		private final Map<String, Object> defaultImports = new LinkedHashMap<>();
		private final Map<String, Object> modules = new LinkedHashMap<>();
		private final Set<String> packages = new LinkedHashSet<>();
		private final List<BiFunction<ScriptContext, String, Object>> functionLoaders = new ArrayList<>();
		private final List<Function<String, BiFunction<Map<String, Object>, String, Object>>> scriptLanguageLoaders = new ArrayList<>();
		private Function<String, Object> classLoader = Builder::loadDefaultClass;
		private ScriptAccessPolicy accessPolicy = ScriptAccessPolicy.allowAll();
		private ScriptExecutionLimits executionLimits = ScriptExecutionLimits.unlimited();
		private final List<ScriptExecutionListener> executionListeners = new ArrayList<>();
		private ExecutorService languageAsyncExecutor;
		private ExecutorService languageBlockingExecutor;
		private int languageBlockingThreads = Math.min(32,
				Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
		private int languageBlockingQueueCapacity = 1024;
		private boolean languageBlockingPoolConfigured;
		private ScheduledExecutorService languageAsyncScheduler;
		private ScriptAsyncPolicy languageAsyncPolicy = ScriptAsyncPolicy.defaults();
		private final List<ScriptTaskListener> languageAsyncListeners = new ArrayList<>();
		private int compileCacheSize = 500;
		private int classLoaderGenerationSize = 128;
		private final List<Object> functionTargets = new ArrayList<>();
		private final Map<Class<?>, List<Object>> extensionTargets = new LinkedHashMap<>();
		private final List<ClassImplicitConvert> implicitConverts = new ArrayList<>();
		private int reflectionCacheSize = 256;

		private Builder() {
			addPackage("java.util.*");
			addPackage("java.lang.*");
			addDefaultImport("Async", cn.ykccchen.script.functions.LanguageAsyncFunctions.class);
			addMethodExtension(Class.class, new ClassExtension());
			addImplicitConvert(new FunctionalImplicitConvert());
		}

		public Builder addDefaultImport(String name, Object target) {
			defaultImports.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(target, "target"));
			return this;
		}

		public Builder addModule(String name, Object target) {
			modules.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(target, "target"));
			return this;
		}

		public Builder addPackage(String prefix) {
			packages.add(Objects.requireNonNull(prefix, "prefix").replace("*", ""));
			return this;
		}

		public Builder classLoader(Function<String, Object> classLoader) {
			this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
			return this;
		}

		public Builder accessPolicy(ScriptAccessPolicy accessPolicy) {
			this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
			return this;
		}

		public Builder executionLimits(ScriptExecutionLimits executionLimits) {
			this.executionLimits = Objects.requireNonNull(executionLimits, "executionLimits");
			return this;
		}

		public Builder addExecutionListener(ScriptExecutionListener listener) {
			executionListeners.add(Objects.requireNonNull(listener, "listener"));
			return this;
		}

		public Builder languageAsyncExecutor(ExecutorService executor) {
			this.languageAsyncExecutor = Objects.requireNonNull(executor, "executor");
			return this;
		}

		/** Executor reserved for adapting blocking {@link java.util.concurrent.Future} values. */
		public Builder languageBlockingExecutor(ExecutorService executor) {
			this.languageBlockingExecutor = Objects.requireNonNull(executor, "executor");
			return this;
		}

		/** Configures the worker count of each Engine-owned blocking Future bridge. */
		public Builder languageBlockingThreads(int threads) {
			if (threads <= 0) throw new IllegalArgumentException("threads must be greater than zero");
			this.languageBlockingThreads = threads;
			this.languageBlockingPoolConfigured = true;
			return this;
		}

		/** Configures the queue capacity of each Engine-owned blocking Future bridge. */
		public Builder languageBlockingQueueCapacity(int queueCapacity) {
			if (queueCapacity <= 0) {
				throw new IllegalArgumentException("queueCapacity must be greater than zero");
			}
			this.languageBlockingQueueCapacity = queueCapacity;
			this.languageBlockingPoolConfigured = true;
			return this;
		}

		public Builder languageAsyncScheduler(ScheduledExecutorService scheduler) {
			this.languageAsyncScheduler = Objects.requireNonNull(scheduler, "scheduler");
			return this;
		}

		public Builder languageAsyncPolicy(ScriptAsyncPolicy policy) {
			this.languageAsyncPolicy = Objects.requireNonNull(policy, "policy");
			return this;
		}

		public Builder addLanguageAsyncListener(ScriptTaskListener listener) {
			languageAsyncListeners.add(Objects.requireNonNull(listener, "listener"));
			return this;
		}

		public Builder compileCacheSize(int compileCacheSize) {
			if (compileCacheSize <= 0) {
				throw new IllegalArgumentException("compileCacheSize must be greater than zero");
			}
			this.compileCacheSize = compileCacheSize;
			return this;
		}

		public Builder classLoaderGenerationSize(int classLoaderGenerationSize) {
			if (classLoaderGenerationSize <= 0) {
				throw new IllegalArgumentException("classLoaderGenerationSize must be greater than zero");
			}
			this.classLoaderGenerationSize = classLoaderGenerationSize;
			return this;
		}

		public Builder addFunctionLoader(BiFunction<ScriptContext, String, Object> loader) {
			functionLoaders.add(Objects.requireNonNull(loader, "loader"));
			return this;
		}

		public Builder addScriptLanguageLoader(
				Function<String, BiFunction<Map<String, Object>, String, Object>> loader) {
			scriptLanguageLoaders.add(Objects.requireNonNull(loader, "loader"));
			return this;
		}

		public Builder addFunction(Object target) {
			functionTargets.add(Objects.requireNonNull(target, "target"));
			return this;
		}

		public Builder addMethodExtension(Class<?> targetType, Object extension) {
			extensionTargets.computeIfAbsent(Objects.requireNonNull(targetType, "targetType"),
					ignored -> new ArrayList<>()).add(Objects.requireNonNull(extension, "extension"));
			return this;
		}

		public Builder addImplicitConvert(ClassImplicitConvert convert) {
			implicitConverts.add(Objects.requireNonNull(convert, "convert"));
			return this;
		}

		public Builder reflectionCacheSize(int maximumEntriesPerTargetClass) {
			if (maximumEntriesPerTargetClass <= 0) {
				throw new IllegalArgumentException("maximumEntriesPerTargetClass must be greater than zero");
			}
			this.reflectionCacheSize = maximumEntriesPerTargetClass;
			return this;
		}

		public ScriptEngineConfig build() {
			if (languageBlockingExecutor != null && languageBlockingPoolConfigured) {
				throw new IllegalStateException(
						"external languageBlockingExecutor cannot be combined with Engine bridge sizing");
			}
			return new ScriptEngineConfig(this);
		}

		private static Object loadDefaultClass(String className) {
			try {
				ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
				return contextLoader == null
						? Class.forName(className)
						: Class.forName(className, true, contextLoader);
			} catch (Exception ignored) {
				return null;
			}
		}
	}
}
