package cn.ykccchen.script;


import cn.ykccchen.script.compile.CompileCache;
import cn.ykccchen.script.compile.CompileCacheStats;
import cn.ykccchen.script.compile.ScriptCompileException;
import cn.ykccchen.script.compile.ScriptCompiler;
import cn.ykccchen.script.exception.ExitException;
import cn.ykccchen.script.exception.ScriptEvaluationException;
import cn.ykccchen.script.exception.ScriptExecutionException;
import cn.ykccchen.script.functions.DynamicModuleImport;
import cn.ykccchen.script.parsing.Parser;
import cn.ykccchen.script.parsing.Span;
import cn.ykccchen.script.parsing.VarIndex;
import cn.ykccchen.script.parsing.ast.Expression;
import cn.ykccchen.script.parsing.ast.Node;
import cn.ykccchen.script.parsing.ast.statement.Import;
import cn.ykccchen.script.parsing.ast.statement.Return;
import cn.ykccchen.script.parsing.ast.statement.VariableAccess;
import cn.ykccchen.script.runtime.ScriptClassLoaderManager;
import cn.ykccchen.script.runtime.ScriptClassLoader;
import cn.ykccchen.script.runtime.ScriptRuntime;
import cn.ykccchen.script.runtime.ScriptVariableAccessRuntime;

import javax.script.Bindings;
import javax.script.CompiledScript;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class Script extends CompiledScript {

	public static final String CONTEXT_ROOT = "ROOT";

	/**
	 * 所有语句
	 */
	private final List<Node> nodes;

	private final javax.script.ScriptEngine scriptEngine;
	private final boolean debug;

	/**
	 * 存放所有变量定义
	 */
	private final Set<VarIndex> varIndices;

	/**
	 * 是否已经编译过
	 */
	private final AtomicBoolean compile = new AtomicBoolean(false);

	/**
	 * 如果是简单的取值操作，则跳过编译
	 */
	private ScriptVariableAccessRuntime accessRuntime;

	/**
	 * 构造函数
	 */
	private Constructor<ScriptRuntime> constructor;


	private List<Span> spans;

	private String[] varNames;

	private static volatile CompileCache compileCache = new CompileCache(500);
	private static final ScriptClassLoaderManager CLASS_LOADER_MANAGER = new ScriptClassLoaderManager(128);
	private static final AtomicLong EXECUTION_SEQUENCE = new AtomicLong();

	private static final class CacheKey {
		private final boolean expression;
		private final boolean debug;
		private final String source;
		private final javax.script.ScriptEngine scriptEngine;

		private CacheKey(boolean expression, boolean debug, String source, javax.script.ScriptEngine scriptEngine) {
			this.expression = expression;
			this.debug = debug;
			this.source = Objects.requireNonNull(source, "source");
			this.scriptEngine = scriptEngine;
		}

		@Override
		public boolean equals(Object object) {
			if (this == object) {
				return true;
			}
			if (!(object instanceof CacheKey)) {
				return false;
			}
			CacheKey other = (CacheKey) object;
			return expression == other.expression
					&& debug == other.debug
					&& source.equals(other.source)
					&& scriptEngine == other.scriptEngine;
		}

		@Override
		public int hashCode() {
			int result = 31 * source.hashCode() + Boolean.hashCode(expression);
			result = 31 * result + Boolean.hashCode(debug);
			return 31 * result + System.identityHashCode(scriptEngine);
		}
	}

	private Script(List<Node> nodes, Set<VarIndex> varIndices, javax.script.ScriptEngine scriptEngine, boolean debug) {
		this.nodes = nodes;
		this.varIndices = varIndices;
		this.scriptEngine = scriptEngine;
		this.debug = debug;
	}

	public static void setCompileCache(int capacity) {
		compileCache = new CompileCache(capacity);
		CLASS_LOADER_MANAGER.rotate();
	}

	public static CompileCacheStats getCompileCacheStats() {
		return compileCache.stats();
	}

	public static ScriptClassLoadingStats getClassLoadingStats() {
		return ScriptClassLoader.stats();
	}

	public static void resetClassLoadingStats() {
		ScriptClassLoader.resetStats();
	}

	public static void clearCompileCache() {
		compileCache.clear();
		CLASS_LOADER_MANAGER.rotate();
	}

	public static int invalidateCompileCache(String source) {
		int invalidated = invalidateCompileCache(compileCache, source);
		if (invalidated > 0) {
			CLASS_LOADER_MANAGER.rotate();
		}
		return invalidated;
	}

	static int invalidateCompileCache(CompileCache cache, String source) {
		Objects.requireNonNull(source, "source");
		return cache.invalidateMatching(key -> key instanceof CacheKey
				&& source.equals(((CacheKey) key).source));
	}

	/**
	 * Parses and compiles a script without executing it.
	 *
	 * @param source script source
	 * @return immutable validation result
	 */
	public static ScriptDiagnostics validate(String source) {
		return validate(source, null);
	}

	/**
	 * Parses and compiles a script without executing it.
	 *
	 * @param source script source
	 * @param sourceName optional logical source name
	 * @return immutable validation result
	 */
	public static ScriptDiagnostics validate(String source, String sourceName) {
		Objects.requireNonNull(source, "source");
		boolean parsed = false;
		try {
			Parser parser = new Parser();
			List<Node> nodes = parser.parse(source);
			parsed = true;
			new Script(nodes, parser.getVarIndices(), null, false).compile();
			return ScriptDiagnostics.valid(sourceName);
		} catch (VirtualMachineError | ThreadDeath fatal) {
			throw fatal;
		} catch (Throwable throwable) {
			ScriptEvaluationException evaluationException = findEvaluationException(throwable);
			String message = evaluationException == null
					? diagnosticMessage(throwable)
					: evaluationException.getSimpleMessage();
			Span span = evaluationException == null ? null : evaluationException.getLocation();
			String code = evaluationException != null
					&& evaluationException.getErrorCode() != cn.ykccchen.script.exception.ScriptErrorCode.SCRIPT_RUNTIME_ERROR
					? evaluationException.getErrorCode().name()
					: parsed ? "SCRIPT_COMPILE_ERROR" : "SCRIPT_PARSE_ERROR";
			return ScriptDiagnostics.of(sourceName,
					ScriptDiagnostic.error(code, message, sourceName, span, throwable));
		}
	}

	private static ScriptEvaluationException findEvaluationException(Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			if (current instanceof ScriptEvaluationException) {
				return (ScriptEvaluationException) current;
			}
			current = current.getCause();
		}
		return null;
	}

	private static String diagnosticMessage(Throwable throwable) {
		Throwable current = throwable;
		while (current.getCause() != null && (current.getMessage() == null || current.getMessage().isEmpty())) {
			current = current.getCause();
		}
		String message = current.getMessage();
		return message == null || message.isEmpty() ? current.getClass().getSimpleName() : message;
	}

	/**
	 * 创建Script
	 */
	public static Script create(String source, javax.script.ScriptEngine scriptEngine) {
		return create(false, false, source, scriptEngine);
	}

	public static Script createDebug(String source, javax.script.ScriptEngine scriptEngine) {
		return create(false, true, source, scriptEngine);
	}

	/**
	 * 创建Script
	 */
	public static Script create(boolean expression, String source, javax.script.ScriptEngine scriptEngine) {
		return create(expression, false, source, scriptEngine);
	}

	private static Script create(boolean expression, boolean debug, String source, javax.script.ScriptEngine scriptEngine) {
		CacheKey key = new CacheKey(expression, debug, source, scriptEngine);
		CompileCache cache = compileCache;
		if (scriptEngine instanceof JvmScriptEngine) {
			JvmScriptEngine engine = (JvmScriptEngine) scriptEngine;
			if (!engine.usesGlobalConfiguration()) {
				cache = engine.getCompileCache();
			}
		}
		return cache.get(key, () -> {
			Parser parser = new Parser();
			List<Node> nodes = parser.parse(expression ? "return " + source : source);
			Set<VarIndex> varIndices = parser.getVarIndices();
			return new Script(nodes, varIndices, scriptEngine, debug);
		});
	}

	public Object execute(ScriptContext context) {
		ScriptContext requiredContext = Objects.requireNonNull(context, "context");
		List<ScriptExecutionListener> listeners = getExecutionListeners(requiredContext);
		if (listeners.isEmpty()) {
			return executeInternal(requiredContext);
		}
		ScriptExecutionEvent started = ScriptExecutionEvent.started(
				EXECUTION_SEQUENCE.incrementAndGet(), requiredContext.getAsyncTaskId(),
				requiredContext.getScriptName(), requiredContext.getCorrelationId(), System.currentTimeMillis());
		long startedNanos = System.nanoTime();
		notifyBefore(listeners, started);
		Throwable failure = null;
		try {
			return executeInternal(requiredContext);
		} catch (Throwable throwable) {
			failure = throwable;
			throw throwable;
		} finally {
			ScriptExecutionEvent.Status status = getExecutionStatus(failure);
			ScriptExecutionEvent completed = ScriptExecutionEvent.completed(
					started, System.nanoTime() - startedNanos, status, failure,
					requiredContext.getHostAccessCount(), requiredContext.getHostCallCount());
			notifyAfter(listeners, completed);
		}
	}

	private Object executeInternal(ScriptContext context) {
		ScriptRuntime runtime = null;
		List<String> scopedDefaultImports = new ArrayList<>();
		boolean engineControlsConfig = scriptEngine instanceof JvmScriptEngine;
		ScriptEngineConfig previousConfig = context.getEngineConfig();
		ExecutorService previousEngineBridge = context.getEngineLanguageBlockingExecutor();
		if (engineControlsConfig) {
			JvmScriptEngine engine = (JvmScriptEngine) scriptEngine;
			context.setEngineConfig(engine.getConfig());
			context.setEngineLanguageBlockingExecutor(engine.getLanguageBlockingExecutor());
		}
		ScriptExecutionState previousExecutionState = context.getExecutionState();
		boolean ownsExecutionState = previousExecutionState == null;
		boolean ownsExecution = !context.isRunning();
		if (ownsExecutionState) {
			ScriptEngineConfig config = context.getEngineConfig();
			ScriptExecutionLimits limits = config == null
					? context.getExecutionLimits()
					: config.getExecutionLimits();
			if ((context.getAsyncTaskId() != 0 || context.hasTerminationRequest()) && limits.isUnlimited()) {
				limits = ScriptExecutionLimits.cancellable();
			}
			if (!limits.isUnlimited()) {
				context.setExecutionState(new ScriptExecutionState(limits));
			}
		}
		// Publish the running flag only after the cancellation state is ready.
		// Otherwise another thread can observe running=true, call cancel(), and
		// lose that signal before executionState is installed.
		if (ownsExecution) {
			context.beginExecution();
		}
		try {
			context.checkpoint();
			ScriptEngineConfig config = context.getEngineConfig();
			Map<String, Object> defaultImports = config == null
					? JvmScriptEngine.getDefaultImports()
					: config.getDefaultImports();
			defaultImports.forEach((name, value) -> {
				if (!context.contains(name)) {
					if (value instanceof DynamicModuleImport) {
						context.set(name, ((DynamicModuleImport) value).getDynamicModule(context));
						} else {
							context.set(name, value);
						}
						if (config != null) {
							scopedDefaultImports.add(name);
						}
					}
			});
			runtime = compile();
			context.checkpoint();
			Object result = runtime.execute(context);
			// Returning a child handle transfers failure observation to the caller;
			// the structured scope must not treat that result as a detached failure.
			if (result instanceof ScriptAsyncResult) {
				((ScriptAsyncResult) result).markFailureObserved();
			}
			if (ownsExecution) {
				context.awaitLanguageAsyncTasks();
			}
			context.checkpoint();
			return result;
		} catch (ExitException e) {
			return e.getExitValue();
		} catch (ScriptCompileException e) {
			throw e;
		} catch (ScriptExecutionException e) {
			throw e;
		} catch (VirtualMachineError | ThreadDeath fatal) {
			throw fatal;
		} catch (Throwable t) {
			ParseError.transfer(runtime, t, context.getScriptName());
		} finally {
			if (ownsExecution) {
				context.cancelAndAwaitLanguageAsyncTasks();
			}
			scopedDefaultImports.forEach(context::remove);
			if (engineControlsConfig) {
				context.setEngineConfig(previousConfig);
				context.setEngineLanguageBlockingExecutor(previousEngineBridge);
			}
			if (ownsExecutionState) {
				if (ownsExecution) {
					context.endExecution();
				}
				context.setExecutionState(previousExecutionState);
			} else if (ownsExecution) {
				context.endExecution();
			}
		}
		return null;
	}

	private List<ScriptExecutionListener> getExecutionListeners(ScriptContext context) {
		LinkedHashSet<ScriptExecutionListener> listeners = new LinkedHashSet<>();
		ScriptEngineConfig config = scriptEngine instanceof JvmScriptEngine
				? ((JvmScriptEngine) scriptEngine).getConfig()
				: context.getEngineConfig();
		if (config != null) {
			listeners.addAll(config.getExecutionListeners());
		}
		listeners.addAll(context.getExecutionListeners());
		return new ArrayList<>(listeners);
	}

	private static ScriptExecutionEvent.Status getExecutionStatus(Throwable failure) {
		if (failure == null) {
			return ScriptExecutionEvent.Status.SUCCEEDED;
		}
		if (failure instanceof ScriptExecutionException) {
			ScriptExecutionException.Reason reason = ((ScriptExecutionException) failure).getReason();
			switch (reason) {
				case CANCELLED:
					return ScriptExecutionEvent.Status.CANCELLED;
				case TIMED_OUT:
					return ScriptExecutionEvent.Status.TIMED_OUT;
				case CHECKPOINT_LIMIT:
					return ScriptExecutionEvent.Status.CHECKPOINT_LIMIT;
				case HOST_CALL_LIMIT:
					return ScriptExecutionEvent.Status.HOST_CALL_LIMIT;
				default:
					break;
			}
		}
		return ScriptExecutionEvent.Status.FAILED;
	}

	private static void notifyBefore(List<ScriptExecutionListener> listeners, ScriptExecutionEvent event) {
		for (ScriptExecutionListener listener : listeners) {
			try {
				listener.beforeExecution(event);
			} catch (RuntimeException ignored) {
				// Monitoring must not affect script execution.
			}
		}
	}

	private static void notifyAfter(List<ScriptExecutionListener> listeners, ScriptExecutionEvent event) {
		for (ScriptExecutionListener listener : listeners) {
			try {
				listener.afterExecution(event);
			} catch (RuntimeException ignored) {
				// Monitoring must not affect script execution.
			}
		}
	}

	/**
	 * 编译
	 */
	public ScriptRuntime compile() throws ScriptCompileException {
		if (this.accessRuntime != null) {
			return this.accessRuntime;
		}
		if (!debug && nodes.size() == 1 && nodes.get(0) instanceof Return) {
			Return returnNode = (Return) nodes.get(0);
			if (returnNode.getReturnValue() instanceof VariableAccess) {
				return this.accessRuntime = new ScriptVariableAccessRuntime(((VariableAccess) returnNode.getReturnValue()).getVarIndex().getName());
			}
		}
		if (!compile.get()) {
			synchronized (compile) {
				if (!compile.get()) {
					compile0();
					compile.set(true);
				}
			}
		}
		return buildRuntime();
	}

	private void compile0() {
		try {
			ScriptCompiler compiler = new ScriptCompiler(this.varIndices, this.debug);
			nodes.forEach(node -> node.visitMethod(compiler));
			// 如果只是一个表达式
			if (nodes.size() == 1 && nodes.get(0) instanceof Expression) {
				Node node = nodes.get(0);
				compiler.loadVars();
				compiler.compile(new Return(node.getSpan(), node));
			} else {
				// 根据是否有 import "xxx.xx.xx.*" 来分组
				Map<Boolean, List<Node>> nodeMap = nodes.stream().collect(Collectors.partitioningBy(it -> it instanceof Import && ((Import) it).isImportPackage()));
				// 编译需要的方法
				compiler.compile(nodeMap.get(Boolean.TRUE));    // 先编译 import "xxx.xxx.x.*"
				// 加载变量信息
				compiler.loadVars();
				// 编译其它语句
				compiler.compile(nodeMap.get(Boolean.FALSE));
			}
			ClassLoader parent = Thread.currentThread().getContextClassLoader();
			if (parent == null) {
				parent = Script.class.getClassLoader();
			}
			byte[] bytecode = compiler.bytecode();
			Class<ScriptRuntime> clazz = scriptEngine instanceof JvmScriptEngine
					? ((JvmScriptEngine) scriptEngine).defineGeneratedClass(parent, compiler.getClassName(), bytecode)
					: CLASS_LOADER_MANAGER.define(parent, compiler.getClassName(), bytecode);
			this.constructor = clazz.getConstructor();
			// 设置变量名字
			this.varNames = varIndices.stream().map(VarIndex::getName).toArray(String[]::new);
			// 设置所有Span
			this.spans = compiler.getSpans();
		} catch (ScriptEvaluationException mse) {
			throw new ScriptCompileException(mse.getSimpleMessage(), mse);
		} catch (ScriptCompileException e) {
			throw e;
		} catch (Exception e) {
			throw new ScriptCompileException(e);
		}
	}

	private ScriptRuntime buildRuntime() {
		try {
			ScriptRuntime runtime = constructor.newInstance();
			// 设置变量名字，为Runtime拷贝副本
			runtime.setVarNames(Arrays.copyOf(this.varNames, this.varNames.length));
			// 设置所有Span，为Runtime拷贝副本
			runtime.setSpans(new ArrayList<>(this.spans));
			return runtime;
		} catch (Exception e) {
			throw new ScriptCompileException(e);
		}
	}


	@Override
	public Object eval(javax.script.ScriptContext context) {
		Bindings bindings = context.getBindings(javax.script.ScriptContext.ENGINE_SCOPE);
		if (bindings.containsKey(CONTEXT_ROOT)) {
			Object root = bindings.get(CONTEXT_ROOT);
			if (root instanceof ScriptContext) {
				ScriptContext rootContext = (ScriptContext) root;
				return execute(rootContext);
			} else {
				throw new ScriptEvaluationException("参数不正确！");
			}
		}
		ScriptContext scriptContext = new ScriptContext();
		scriptContext.putMapIntoContext(context.getBindings(javax.script.ScriptContext.GLOBAL_SCOPE));
		scriptContext.putMapIntoContext(context.getBindings(javax.script.ScriptContext.ENGINE_SCOPE));
		return execute(scriptContext);
	}

	@Override
	public javax.script.ScriptEngine getEngine() {
		return scriptEngine;
	}
}
