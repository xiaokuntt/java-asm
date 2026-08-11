package cn.ykccchen.script;

import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import cn.ykccchen.script.category.IntegrationCategory;
import cn.ykccchen.script.exception.ResourceNotFoundException;
import cn.ykccchen.script.exception.ScriptExecutionException;
import cn.ykccchen.script.compile.CompileCache;
import cn.ykccchen.script.reflection.JavaInvoker;
import cn.ykccchen.script.reflection.JavaReflection;

import javax.script.ScriptException;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

@Category(IntegrationCategory.class)
public class ScriptApiTests {
	private static class FatalVmError extends VirtualMachineError {
		private static final long serialVersionUID = 1L;
	}

	public static class SecretBean {
		private String secret = "secret";

		public String getSecret() {
			return secret;
		}

		public void setSecret(String secret) {
			this.secret = secret;
		}
	}

	@Test
	public void executeInlineScriptWithContextVariables() {
		Script script = Script.create("return a + b;", null);
		ScriptContext context = new ScriptContext();
		context.set("a", 2);
		context.set("b", 3);
		Assert.assertEquals(5, script.execute(context));
	}

	@Test
	public void createUsesCompileCacheForSameSource() {
		Script.setCompileCache(32);
		Script left = Script.create("return 1;", null);
		Script right = Script.create("return 1;", null);
		Assert.assertSame(left, right);
	}

	@Test
	public void compileCacheSeparatesExpressionModeAndEngineIdentity() {
		Script.setCompileCache(32);
		JvmScriptEngine firstEngine = new JvmScriptEngine(new ScriptEngineFactory());
		JvmScriptEngine secondEngine = new JvmScriptEngine(new ScriptEngineFactory());

		Script script = Script.create(false, "value", firstEngine);
		Script expression = Script.create(true, "value", firstEngine);
		Script otherEngine = Script.create(false, "value", secondEngine);

		Assert.assertNotSame(script, expression);
		Assert.assertNotSame(script, otherEngine);
		Assert.assertSame(firstEngine, script.getEngine());
		Assert.assertSame(secondEngine, otherEngine.getEngine());
	}

	@Test
	public void engineConfigsIsolateImportsModulesAndFunctions() throws Exception {
		ScriptEngineConfig firstConfig = ScriptEngineConfig.builder()
				.addDefaultImport("tenant", "first")
				.addModule("service", "module-first")
				.addFunctionLoader((context, name) -> "decorate".equals(name)
						? (Function<Object, Object>) value -> "first-" + value
						: null)
				.build();
		ScriptEngineConfig secondConfig = ScriptEngineConfig.builder()
				.addDefaultImport("tenant", "second")
				.addModule("service", "module-second")
				.addFunctionLoader((context, name) -> "decorate".equals(name)
						? (Function<Object, Object>) value -> "second-" + value
						: null)
				.build();
		JvmScriptEngine first = new JvmScriptEngine(new ScriptEngineFactory(), firstConfig);
		JvmScriptEngine second = new JvmScriptEngine(new ScriptEngineFactory(), secondConfig);
		String source = "import service; import '@decorate' as decorate; "
				+ "return tenant + ':' + service + ':' + decorate('value');";

		Assert.assertEquals("first:module-first:first-value", first.eval(source));
		Assert.assertEquals("second:module-second:second-value", second.eval(source));
		Assert.assertFalse(JvmScriptEngine.getDefaultImports().containsKey("tenant"));
	}

	@Test
	public void isolatedEngineDefaultsDoNotLeakThroughAReusedContext() {
		JvmScriptEngine first = new JvmScriptEngine(new ScriptEngineFactory(),
				ScriptEngineConfig.builder().addDefaultImport("tenant", "first").build());
		JvmScriptEngine second = new JvmScriptEngine(new ScriptEngineFactory(),
				ScriptEngineConfig.builder().addDefaultImport("tenant", "second").build());
		ScriptContext shared = new ScriptContext();

		Assert.assertEquals("first", Script.create("return tenant;", first).execute(shared));
		Assert.assertFalse(shared.contains("tenant"));
		Assert.assertEquals("second", Script.create("return tenant;", second).execute(shared));
		Assert.assertFalse(shared.contains("tenant"));
	}

	@Test
	public void engineConfigOwnsClassResolutionAndAccessPolicy() throws Exception {
		ScriptEngineConfig allowedConfig = ScriptEngineConfig.builder()
				.addPackage("isolated.*")
				.classLoader(name -> "isolated.Alias".equals(name) ? String.class : null)
				.build();
		ScriptEngineConfig deniedConfig = ScriptEngineConfig.builder()
				.accessPolicy(new ScriptAccessPolicy() {
					@Override
					public boolean allowClass(String className) {
						return !"java.lang.String".equals(className);
					}

					@Override
					public boolean allowMember(Class<?> owner, String memberName) {
						return true;
					}
				})
				.build();
		JvmScriptEngine allowed = new JvmScriptEngine(new ScriptEngineFactory(), allowedConfig);
		JvmScriptEngine denied = new JvmScriptEngine(new ScriptEngineFactory(), deniedConfig);

		Assert.assertEquals("String", allowed.eval("return Alias.getSimpleName();"));
		try {
			denied.eval("import java.lang.String as Text; return Text.getSimpleName();");
			Assert.fail("Expected the isolated engine policy to deny String");
		} catch (RuntimeException exception) {
			Assert.assertTrue(exception.getMessage().contains("java.lang.String"));
		}
	}

	@Test(expected = UnsupportedOperationException.class)
	public void engineConfigCollectionsAreImmutable() {
		ScriptEngineConfig.builder().addDefaultImport("name", "value").build()
				.getDefaultImports().put("other", "value");
	}

	@Test
	public void configuredFactoryCreatesAnIsolatedEngine() throws Exception {
		ScriptEngineConfig config = ScriptEngineConfig.builder()
				.addDefaultImport("configured", 42)
				.build();
		JvmScriptEngine engine = (JvmScriptEngine) new ScriptEngineFactory(config).getScriptEngine();

		Assert.assertFalse(engine.usesGlobalConfiguration());
		Assert.assertSame(config, engine.getConfig());
		Assert.assertEquals(42, engine.eval("return configured;"));
	}

	@Test
	public void isolatedEngineOwnsItsBoundedCompileCache() {
		ScriptEngineConfig config = ScriptEngineConfig.builder().compileCacheSize(1).build();
		JvmScriptEngine engine = new JvmScriptEngine(new ScriptEngineFactory(), config);
		Script first = Script.create("return 1;", engine);
		Assert.assertSame(first, Script.create("return 1;", engine));

		Script.create("return 2;", engine);
		Assert.assertNotSame(first, Script.create("return 1;", engine));
	}

	@Test(timeout = 5000)
	public void engineTimeoutStopsAnEmptyInfiniteLoop() throws Exception {
		ScriptExecutionLimits limits = ScriptExecutionLimits.builder()
				.timeout(25, TimeUnit.MILLISECONDS)
				.build();
		JvmScriptEngine engine = new JvmScriptEngine(new ScriptEngineFactory(),
				ScriptEngineConfig.builder().executionLimits(limits).build());

		try {
			engine.eval("while(true){} return 1;");
			Assert.fail("Expected script execution to time out");
		} catch (ScriptExecutionException exception) {
			Assert.assertEquals(ScriptExecutionException.Reason.TIMED_OUT, exception.getReason());
		}
	}

	@Test
	public void checkpointLimitStopsLongRunningLoop() {
		ScriptContext context = new ScriptContext().setExecutionLimits(
				ScriptExecutionLimits.builder().maxCheckpoints(10).build());
		try {
			Script.create("for(var i = 0; i < 1000; i++){} return i;", null).execute(context);
			Assert.fail("Expected the checkpoint limit to stop the script");
		} catch (ScriptExecutionException exception) {
			Assert.assertEquals(ScriptExecutionException.Reason.CHECKPOINT_LIMIT, exception.getReason());
		}
	}

	@Test(timeout = 10000)
	public void scriptContextCanCancelRunningScript() throws Exception {
		ScriptContext context = new ScriptContext().enableCancellation();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<Object> future = executor.submit(() ->
					Script.create("while(true){}", null).execute(context));
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			while (!context.isRunning() && System.nanoTime() - deadline < 0) {
				Thread.yield();
			}
			Assert.assertTrue("Script did not enter the running state", context.isRunning());
			context.cancel();
			try {
				future.get(2, TimeUnit.SECONDS);
				Assert.fail("Expected script cancellation");
			} catch (ExecutionException exception) {
				Assert.assertTrue(exception.getCause() instanceof ScriptExecutionException);
				ScriptExecutionException executionException =
						(ScriptExecutionException) exception.getCause();
				Assert.assertEquals(ScriptExecutionException.Reason.CANCELLED,
						executionException.getReason());
			}
			Assert.assertFalse(context.isRunning());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void variableFastPathMatchesNormalEnvironmentResolution() {
		Assert.assertSame(String.class,
				Script.create("return String;", null).execute(new ScriptContext()));

		ScriptContext context = new ScriptContext().set("String", null);
		Assert.assertNull(Script.create("return String;", null).execute(context));
		Assert.assertEquals(Boolean.TRUE,
				Script.create("return String == null;", null).execute(context));
	}

	@Test(timeout = 10000)
	public void concurrentCacheMissCreatesOnlyOneScript() throws Exception {
		Script.setCompileCache(32);
		ExecutorService executor = Executors.newFixedThreadPool(8);
		CountDownLatch start = new CountDownLatch(1);
		try {
			@SuppressWarnings("unchecked")
			Future<Script>[] futures = new Future[16];
			for (int i = 0; i < futures.length; i++) {
				futures[i] = executor.submit(() -> {
					start.await();
					return Script.create("return 42;", null);
				});
			}
			start.countDown();
			Script expected = futures[0].get();
			for (Future<Script> future : futures) {
				Assert.assertSame(expected, future.get());
			}
		} finally {
			executor.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void compileCacheAllowsDifferentKeysToLoadConcurrently() throws Exception {
		CompileCache cache = new CompileCache(8);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch suppliersEntered = new CountDownLatch(2);
		CountDownLatch releaseSuppliers = new CountDownLatch(1);
		try {
			Future<Script> first = executor.submit(() -> {
				start.await();
				return cache.get("first", () -> createBlockedScript(
						"return 1;", suppliersEntered, releaseSuppliers));
			});
			Future<Script> second = executor.submit(() -> {
				start.await();
				return cache.get("second", () -> createBlockedScript(
						"return 2;", suppliersEntered, releaseSuppliers));
			});
			start.countDown();
			boolean concurrent = suppliersEntered.await(2, TimeUnit.SECONDS);
			releaseSuppliers.countDown();
			Assert.assertTrue("Different cache keys should not share a compilation lock", concurrent);
			Assert.assertNotSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
		} finally {
			releaseSuppliers.countDown();
			executor.shutdownNow();
		}
	}

	private static Script createBlockedScript(String source, CountDownLatch entered, CountDownLatch release) {
		entered.countDown();
		try {
			release.await();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(exception);
		}
		return Script.create(source, null);
	}

	@Test
	public void usesPublicDeclarationForJdkImplementationMethods() throws Throwable {
		JavaInvoker<Method> invoker = JavaReflection.getMethod(Collections.emptyList(), "size");
		Assert.assertNotNull(invoker);
		Assert.assertTrue(Modifier.isPublic(invoker.getExecutable().getDeclaringClass().getModifiers()));
		Assert.assertEquals(0, invoker.invoke0(Collections.emptyList(), null, new Object[0]));
	}

	@Test
	public void accessPolicyCanBlockClassesAndMembers() {
		ResourceLoader.setAccessPolicy(new ScriptAccessPolicy() {
			@Override
			public boolean allowClass(String className) {
				return !"java.lang.System".equals(className);
			}

			@Override
			public boolean allowMember(Class<?> owner, String memberName) {
				return !"getClass".equals(memberName);
			}
		});
		try {
			assertSecurityFailure("import java.lang.System; return System.currentTimeMillis();", "java.lang.System");
			ScriptContext context = new ScriptContext().set("value", "text");
			try {
				Script.create("return value.getClass();", null).execute(context);
				Assert.fail("Expected member access to be denied");
			} catch (RuntimeException ex) {
				Assert.assertTrue(ex.getMessage().contains("getClass"));
			}
		} finally {
			ResourceLoader.resetAccessPolicy();
		}
	}

	@Test
	public void accessPolicyChecksResolvedGettersAndBoundFunctions() {
		ResourceLoader.setAccessPolicy(new ScriptAccessPolicy() {
			@Override
			public boolean allowClass(String className) {
				return true;
			}

			@Override
			public boolean allowMember(Class<?> owner, String memberName) {
				return !"getSecret".equals(memberName)
						&& !"setSecret".equals(memberName)
						&& !"apply".equals(memberName);
			}
		});
		try {
			assertSecurityFailure("return bean.secret;", "getSecret",
					new ScriptContext().set("bean", new SecretBean()));
			assertSecurityFailure("bean.secret = 'changed';", "setSecret",
					new ScriptContext().set("bean", new SecretBean()));
			assertSecurityFailure("return fn('ok');", "apply",
					new ScriptContext().set("fn", (Function<String, String>) value -> value));
		} finally {
			ResourceLoader.resetAccessPolicy();
		}
	}

	@Test
	public void classWhitelistSkipsDeniedDefaultPackages() {
		ResourceLoader.setAccessPolicy(new ScriptAccessPolicy() {
			@Override
			public boolean allowClass(String className) {
				return className.startsWith("java.lang.");
			}

			@Override
			public boolean allowMember(Class<?> owner, String memberName) {
				return true;
			}
		});
		try {
			Assert.assertEquals("String", Script.create("return String.getSimpleName();", null).execute(new ScriptContext()));
		} finally {
			ResourceLoader.resetAccessPolicy();
		}
	}

	private static void assertSecurityFailure(String source, String expectedMessage) {
		assertSecurityFailure(source, expectedMessage, new ScriptContext());
	}

	private static void assertSecurityFailure(String source, String expectedMessage, ScriptContext context) {
		try {
			Script.create(source, null).execute(context);
			Assert.fail("Expected script access to be denied");
		} catch (RuntimeException ex) {
			Assert.assertTrue(ex.getMessage().contains(expectedMessage));
		}
	}

	@Test(expected = UnsupportedOperationException.class)
	public void registryViewsCannotMutateGlobalState() {
		ResourceLoader.addModule("readonly_registry_test", new Object());
		ResourceLoader.getModuleNames().remove("readonly_registry_test");
	}

	@Test(timeout = 10000)
	public void scriptClassRegistryReturnsAnImmutableSnapshot() {
		Map<String, ScriptClass> snapshot = JvmScriptEngine.getScriptClassMap();
		boolean mutated = false;
		try {
			snapshot.put("should_not_be_registered", new ScriptClass());
			mutated = true;
			Assert.fail("Expected an immutable script class registry snapshot");
		} catch (UnsupportedOperationException expected) {
			// expected
		} finally {
			if (mutated) {
				snapshot.remove("should_not_be_registered");
			}
		}
	}

	@Test
	public void compilationFallsBackWhenThreadContextClassLoaderIsNull() {
		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		thread.setContextClassLoader(null);
		try {
			Assert.assertEquals(42, Script.create("return 40 + 2; // null-tccl", null)
					.execute(new ScriptContext()));
		} finally {
			thread.setContextClassLoader(previous);
		}
	}

	@Test
	public void fatalVmErrorsAreNotWrappedAsScriptErrors() {
		FatalVmError expected = new FatalVmError();
		ScriptContext context = new ScriptContext().set("fn", (Function<Object, Object>) ignored -> {
			throw expected;
		});
		try {
			Script.create("return fn();", null).execute(context);
			Assert.fail("Expected the VM error to be propagated");
		} catch (FatalVmError actual) {
			Assert.assertSame(expected, actual);
		}
	}

	@Test
	public void callerVariablesTakePrecedenceOverDefaultImports() {
		JvmScriptEngine.addDefaultImport("priority_value", "default");
		try {
			ScriptContext context = new ScriptContext().set("priority_value", "caller");
			Assert.assertEquals("caller", Script.create("return priority_value;", null).execute(context));
		} finally {
			JvmScriptEngine.removeDefaultImport("priority_value");
		}
	}

	@Test
	public void contextEvalUsesNormalExecutionPipeline() {
		JvmScriptEngine.addDefaultImport("eval_default", 40);
		try {
			Assert.assertEquals(42, new ScriptContext().eval("eval_default + 2", Collections.emptyMap()));
		} finally {
			JvmScriptEngine.removeDefaultImport("eval_default");
		}
	}

	@Test
	public void readerFailuresAreReportedInsteadOfExecutingPartialSource() {
		JvmScriptEngine engine = new JvmScriptEngine(new ScriptEngineFactory());
		Reader reader = new Reader() {
			@Override
			public int read(char[] buffer, int offset, int length) throws IOException {
				throw new IOException("read failed");
			}

			@Override
			public void close() {
			}
		};
		try {
			engine.compile(reader);
			Assert.fail("Expected ScriptException");
		} catch (ScriptException ex) {
			Assert.assertTrue(ex.getMessage().contains("read failed"));
		}
	}

	@Test(timeout = 10000)
	public void debugScriptPausesAndResumesWithoutBlockingTheController() throws Exception {
		ScriptDebugContext debugContext = new ScriptDebugContext(Collections.singletonList(1));
		AtomicReference<Map<String, Object>> debugInfo = new AtomicReference<>();
		debugContext.setCallback(debugInfo::set);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<Object> result = executor.submit(() ->
					Script.createDebug("var value = 1;\nreturn value + 1;", null).execute(debugContext));
			Assert.assertTrue(debugContext.await(5, TimeUnit.SECONDS));
			Assert.assertEquals(ScriptDebugContext.State.PAUSED, debugContext.getState());
			Assert.assertNotNull(debugInfo.get());
			debugContext.signal();
			Assert.assertEquals(2, result.get(5, TimeUnit.SECONDS));
			Assert.assertEquals(ScriptDebugContext.State.RUNNING, debugContext.getState());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test(timeout = 10000)
	public void duplicateDebugSignalCannotResumeTheNextBreakpoint() throws Exception {
		ScriptDebugContext debugContext = new ScriptDebugContext(java.util.Arrays.asList(1, 2));
		AtomicInteger pauseCount = new AtomicInteger();
		debugContext.setCallback(info -> {
			if (pauseCount.incrementAndGet() == 1) {
				debugContext.signal();
				debugContext.signal();
			}
		});
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<Object> result = executor.submit(() -> Script.createDebug(
					"var first = 1;\nvar second = 2;\nreturn first + second;", null)
					.execute(debugContext));
			Assert.assertTrue(debugContext.await(5, TimeUnit.SECONDS));
			Assert.assertTrue(debugContext.await(5, TimeUnit.SECONDS));
			Assert.assertEquals(ScriptDebugContext.State.PAUSED, debugContext.getState());
			Assert.assertFalse("A duplicate signal must not pass the next breakpoint", result.isDone());
			debugContext.signal();
			Assert.assertEquals(3, result.get(5, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void scriptEngineFactoryBuildsStandardSyntaxHelpers() {
		ScriptEngineFactory factory = new ScriptEngineFactory();
		Assert.assertNotNull(factory.getEngineVersion());
		Assert.assertEquals("service.load(a, b)", factory.getMethodCallSyntax("service", "load", "a", "b"));
		Assert.assertEquals("System.out.println(\"a\\nb\");", factory.getOutputStatement("a\nb"));
		Assert.assertEquals("var a = 1;" + System.lineSeparator(), factory.getProgram("var a = 1"));
	}

	@Test
	public void scriptContextEvalExecutesExpression() {
		ScriptContext context = new ScriptContext();
		Map<String, Object> vars = new HashMap<>();
		vars.put("value", 7);
		Assert.assertEquals(12, context.eval("value + 5", vars));
	}

	@Test
	public void canResolveDefaultImportedJavaClasses() {
		Script script = Script.create("return new ArrayList().getClass().getSimpleName();", null);
		Assert.assertEquals("ArrayList", script.execute(new ScriptContext()));
	}

	@Test(expected = ResourceNotFoundException.class)
	public void loadingUnknownModuleThrowsException() {
		ResourceLoader.loadModule("module_not_exists");
	}

	@Test
	public void lambdaExpressionCanBeInvoked() {
		Script script = Script.create("var sum = (a,b)=>a+b; return sum(4,5);", null);
		Assert.assertEquals(9, script.execute(new ScriptContext()));
	}
}
