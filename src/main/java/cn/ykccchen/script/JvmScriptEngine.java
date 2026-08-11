package cn.ykccchen.script;

import cn.ykccchen.script.ScriptClass.ScriptAttribute;
import cn.ykccchen.script.ScriptClass.ScriptMethod;
import cn.ykccchen.script.compile.CompileCache;
import cn.ykccchen.script.compile.CompileCacheStats;
import cn.ykccchen.script.reflection.JavaReflection;
import cn.ykccchen.script.runtime.ScriptClassLoaderManager;
import cn.ykccchen.script.runtime.ScriptRuntime;

import javax.script.*;
import java.beans.Transient;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class JvmScriptEngine extends AbstractScriptEngine
		implements javax.script.ScriptEngine, Compilable, AutoCloseable {

	private static final Map<String, Object> DEFAULT_IMPORTS = new ConcurrentHashMap<>();

	private static volatile Map<String, ScriptClass> classMap = null;
	private static final AtomicLong ENGINE_SEQUENCE = new AtomicLong();

	static {
		DEFAULT_IMPORTS.put("Async", cn.ykccchen.script.functions.LanguageAsyncFunctions.class);
	}

	private final ScriptEngineFactory scriptEngineFactory;
	private final ScriptEngineConfig config;
	private final CompileCache compileCache;
	private final ScriptClassLoaderManager classLoaderManager;
	private final EngineFutureBridge ownedFutureBridge;
	private final ExecutorService languageBlockingExecutor;

	public JvmScriptEngine(ScriptEngineFactory scriptEngineFactory) {
		this(scriptEngineFactory, null);
	}

	public JvmScriptEngine(ScriptEngineFactory scriptEngineFactory, ScriptEngineConfig config) {
		this.scriptEngineFactory = scriptEngineFactory;
		this.config = config;
		this.compileCache = config == null ? null : new CompileCache(config.getCompileCacheSize());
		this.classLoaderManager = new ScriptClassLoaderManager(
				config == null ? 128 : config.getClassLoaderGenerationSize());
		ExecutorService configuredBridge = config == null ? null : config.getLanguageBlockingExecutor();
		if (configuredBridge == null) {
			int threads = config == null ? Math.min(32,
					Math.max(4, Runtime.getRuntime().availableProcessors() * 2))
					: config.getLanguageBlockingThreads();
			int queueCapacity = config == null ? 1024 : config.getLanguageBlockingQueueCapacity();
			this.ownedFutureBridge = new EngineFutureBridge(threads, queueCapacity,
					"script-engine-future-bridge-" + ENGINE_SEQUENCE.incrementAndGet());
			this.languageBlockingExecutor = ownedFutureBridge.executor();
		} else {
			this.ownedFutureBridge = null;
			this.languageBlockingExecutor = configuredBridge;
		}
	}

	public ScriptEngineConfig getConfig() {
		return config;
	}

	public boolean usesGlobalConfiguration() {
		return config == null;
	}

	public ExecutorService getLanguageBlockingExecutor() {
		return languageBlockingExecutor;
	}

	/** Returns Engine-local bridge statistics; external executors may expose partial values. */
	public ScriptAsyncRuntimeStats getFutureBridgeStats() {
		return ownedFutureBridge == null
				? EngineFutureBridge.snapshot(languageBlockingExecutor, -1)
				: ownedFutureBridge.stats();
	}

	public boolean isFutureBridgeShutdown() {
		return languageBlockingExecutor.isShutdown();
	}

	@Override
	public void close() {
		if (ownedFutureBridge != null) ownedFutureBridge.close();
	}

	CompileCache getCompileCache() {
		return compileCache;
	}

	public CompileCacheStats getCompileCacheStats() {
		return usesGlobalConfiguration() ? Script.getCompileCacheStats() : compileCache.stats();
	}

	public void clearCompileCache() {
		if (usesGlobalConfiguration()) {
			Script.clearCompileCache();
		} else {
			compileCache.clear();
		}
		classLoaderManager.rotate();
	}

	public int invalidateCompileCache(String source) {
		int invalidated = usesGlobalConfiguration()
				? Script.invalidateCompileCache(source)
				: Script.invalidateCompileCache(compileCache, source);
		if (invalidated > 0) {
			classLoaderManager.rotate();
		}
		return invalidated;
	}

	Class<ScriptRuntime> defineGeneratedClass(ClassLoader parent, String name, byte[] bytecode)
			throws ClassNotFoundException {
		return classLoaderManager.define(parent, name, bytecode);
	}

	public long getClassLoaderGeneration() {
		return classLoaderManager.getGeneration();
	}

	public int getClassLoaderGenerationSize() { return classLoaderManager.getGenerationSize(); }

	public int getCurrentGenerationDefinitionCount() {
		return classLoaderManager.getCurrentGenerationDefinitionCount();
	}

	public static GlobalRegistrySnapshot snapshotGlobalRegistry() {
		Map<String, ScriptClass> classes = classMap == null ? null : new HashMap<>(classMap);
		return new GlobalRegistrySnapshot(new HashMap<>(DEFAULT_IMPORTS), classes);
	}

	public static synchronized void resetGlobalRegistry() {
		DEFAULT_IMPORTS.clear();
		DEFAULT_IMPORTS.put("Async", cn.ykccchen.script.functions.LanguageAsyncFunctions.class);
		classMap = null;
	}

	public static synchronized void restoreGlobalRegistry(GlobalRegistrySnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot");
		DEFAULT_IMPORTS.clear();
		DEFAULT_IMPORTS.putAll(snapshot.defaultImports);
		classMap = snapshot.scriptClasses == null ? null : new ConcurrentHashMap<>(snapshot.scriptClasses);
	}

	@Deprecated
	public static synchronized void addScriptClass(Class<?> clazz) {
		Map<String, ScriptClass> registry = getMutableScriptClassMap();
		getScriptClass(clazz).forEach(scriptClass -> registry.put(scriptClass.getClassName(), scriptClass));
	}

	private synchronized static Map<String, ScriptClass> getMutableScriptClassMap() {
		if (classMap == null) {
			classMap = new ConcurrentHashMap<>();
			Arrays.asList(String.class, Object.class, Date.class, Integer.class, Double.class, Float.class, Long.class, List.class, Short.class, Byte.class, Boolean.class, BigDecimal.class)
					.forEach(clazz -> getScriptClass(clazz).forEach(scriptClass -> classMap.put(scriptClass.getClassName(), scriptClass)));
		}
		return classMap;
	}

	public static Map<String, ScriptClass> getScriptClassMap() {
		return Collections.unmodifiableMap(new HashMap<>(getMutableScriptClassMap()));
	}

	public static List<ScriptMethod> getFunctions() {
		return JavaReflection.getFunctions().stream().map(it -> new ScriptMethod(it.getExecutable())).collect(Collectors.toList());
	}

	public static Map<String, ScriptClass> getExtensionScriptClass() {
		Map<Class<?>, List<Class<?>>> extensionMap = JavaReflection.getExtensionMap();
		Map<String, ScriptClass> classMap = new HashMap<>();
		for (Map.Entry<Class<?>, List<Class<?>>> entry : extensionMap.entrySet()) {
			ScriptClass clazz = classMap.get(entry.getKey().getName());
			if (clazz == null) {
				clazz = new ScriptClass();
				classMap.put(entry.getKey().getName(), clazz);
			}
			for (Class<?> extensionClass : entry.getValue()) {
				for (ScriptMethod method : getMethod(extensionClass)) {
					clazz.addMethod(method);
				}
			}
		}
		return classMap;
	}

	public static ScriptClass getScriptClassFromClass(Class<?> clazz) {
		Class<?> superClass = clazz.getSuperclass();
		ScriptClass scriptClass = new ScriptClass();
		scriptClass.setClassName(clazz.getName());
		scriptClass.setSuperClass(superClass != null ? superClass.getName() : null);
		appendMethod(clazz, scriptClass);
		return scriptClass;
	}

	public static List<ScriptClass> getScriptClass(Class<?> clazz) {
		List<ScriptClass> classList = new ArrayList<>();
		Class<?> superClass;
		do {
			superClass = clazz.getSuperclass();
			ScriptClass scriptClass = new ScriptClass();
			scriptClass.setClassName(clazz.getName());
			scriptClass.setSuperClass(superClass != null ? superClass.getName() : null);
			if (clazz.isEnum()) {
				scriptClass.setEnums(clazz.getEnumConstants());
			} else {
				appendAttributes(clazz, scriptClass);
			}
			appendMethod(clazz, scriptClass);
			classList.add(scriptClass);
			Class<?>[] interfaces = clazz.getInterfaces();
			List<String> interfaceList = new ArrayList<>();
			for (Class<?> interfaceClazz : interfaces) {
				classList.addAll(getScriptClass(interfaceClazz));
				interfaceList.add(interfaceClazz.getName());
			}
			scriptClass.setInterfaces(interfaceList);
			clazz = superClass;
		} while (superClass != null && superClass != Object.class && superClass != Class.class);
		return classList;
	}

	private static void appendMethod(Class<?> clazz, ScriptClass scriptClass) {
		getMethod(clazz).forEach(method -> {
			scriptClass.addMethod(method);
			String methodName = method.getName();
			if (method.getParameters().isEmpty() && ((methodName.startsWith("get") && methodName.length() > 3) || (methodName.startsWith("is") && methodName.length() > 2))) {
				String attributeName = method.getName().substring(methodName.startsWith("get") ? 3 : 2);
				attributeName = attributeName.substring(0, 1).toLowerCase() + attributeName.substring(1);
				if (!"class".equalsIgnoreCase(attributeName)) {
					scriptClass.addAttribute(new ScriptAttribute(method.getReturnType(), attributeName));
				}
			}
		});
	}

	public static Set<ScriptClass> getScriptClass(String className) {
		try {
			return new LinkedHashSet<>(getScriptClass(ResourceLoader.forName(className)));
		} catch (ClassNotFoundException e) {
			return Collections.emptySet();
		}
	}

	private static List<ScriptMethod> getMethod(Class<?> clazz) {
		List<ScriptMethod> methods = new ArrayList<>();
		try {
			Method[] declaredMethods = clazz.getDeclaredMethods();
			for (Method declaredMethod : declaredMethods) {
				if (!Modifier.isVolatile(declaredMethod.getModifiers())) {
					if (Modifier.isPublic(declaredMethod.getModifiers()) && declaredMethod.getAnnotation(Transient.class) == null) {
						if (Modifier.isPublic(declaredMethod.getModifiers())) {
							methods.add(new ScriptMethod(declaredMethod));
						}
					}
				}
			}
		} catch (Exception ignored) {
		}
		return methods;
	}

	private static void appendAttributes(Class<?> clazz, ScriptClass target) {
		try {
			Field[] fields = clazz.getDeclaredFields();
			for (Field field : fields) {
				if (Modifier.isPublic(field.getModifiers()) && field.getAnnotation(Transient.class) == null) {
					target.addAttribute(new ScriptAttribute(field.getType().getName(), field.getName()));
				}
			}
		} catch (Exception ignored) {
		}
	}

	@Deprecated
	public static void addDefaultImport(String name, Object target) {
		DEFAULT_IMPORTS.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(target, "target"));
	}

	@Deprecated
	public static void removeDefaultImport(String name) {
		DEFAULT_IMPORTS.remove(name);
	}

	public static Map<String, Object> getDefaultImports() {
		return Collections.unmodifiableMap(new HashMap<>(DEFAULT_IMPORTS));
	}

	public static Object execute(Script script, ScriptContext context) {
		SimpleScriptContext simpleScriptContext = new SimpleScriptContext();
		simpleScriptContext.setAttribute(Script.CONTEXT_ROOT, context, javax.script.ScriptContext.ENGINE_SCOPE);
		return script.eval(simpleScriptContext);
	}

	@Override
	public Object eval(String script, javax.script.ScriptContext context) throws ScriptException {
		return compile(script).eval(context);
	}

	@Override
	public Object eval(Reader reader, javax.script.ScriptContext context) throws ScriptException {
		return compile(reader).eval(context);
	}

	@Override
	public Bindings createBindings() {
		return new SimpleBindings();
	}

	@Override
	public javax.script.ScriptEngineFactory getFactory() {
		return scriptEngineFactory;
	}

	@Override
	public CompiledScript compile(String script) {
		return Script.create(script, this);
	}

	@Override
	public CompiledScript compile(Reader script) throws ScriptException {
		return compile(readString(script));
	}

	private String readString(Reader reader) throws ScriptException {
		StringBuilder builder = new StringBuilder();
		char[] buf = new char[1024];
		int len;
		try {
			while ((len = reader.read(buf, 0, buf.length)) != -1) {
				builder.append(buf, 0, len);
			}
		} catch (IOException e) {
			throw new ScriptException(e);
		}
		return builder.toString();
	}

	public static final class GlobalRegistrySnapshot {
		private final Map<String, Object> defaultImports;
		private final Map<String, ScriptClass> scriptClasses;

		private GlobalRegistrySnapshot(Map<String, Object> defaultImports,
				Map<String, ScriptClass> scriptClasses) {
			this.defaultImports = defaultImports;
			this.scriptClasses = scriptClasses;
		}

		public Map<String, Object> getDefaultImports() {
			return Collections.unmodifiableMap(defaultImports);
		}
	}
}
