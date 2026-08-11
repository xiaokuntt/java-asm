package cn.ykccchen.script;

import cn.ykccchen.script.annotation.Function;
import cn.ykccchen.script.exception.ScriptAsyncRejectedException;
import cn.ykccchen.script.exception.ScriptErrorCode;
import cn.ykccchen.script.exception.ScriptEvaluationException;
import cn.ykccchen.script.exception.ScriptRuntimeException;
import cn.ykccchen.script.reflection.JavaReflection;
import cn.ykccchen.script.reflection.ScriptReflectionRegistry;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

public class OptimizationContractTests {

	public static final class ScopedFunctions {
		@Function
		public String optimizationSnapshotFunction() {
			return "snapshot";
		}
	}

	public static final class ScopedExtensions {
		public String sequence(CharSequence value) { return value.toString(); }
		public int arrayLength(Object[] values) { return values.length; }
	}

	public static class HostParent {
		public String inherited() { return "parent"; }
	}

	public static final class HostChild extends HostParent {
		public String local() { return "child"; }
	}

	public static final class HostChildExtra extends HostParent {
		public String local() { return "extra"; }
	}

	@Test
	public void classLoadingStatsShouldExposeGeneratedClassesAndMetaspace() {
		Script.clearCompileCache();
		Script.resetClassLoadingStats();
		Script.create("return 987654321;", null).compile();
		ScriptClassLoadingStats stats = Script.getClassLoadingStats();
		Assert.assertEquals(1, stats.getCreatedLoaderCount());
		Assert.assertEquals(1, stats.getDefinedClassCount());
		Assert.assertTrue(stats.getDefinedBytecodeBytes() > 0);
		Assert.assertTrue(stats.getEstimatedLiveLoaderCount() > 0);
		Assert.assertTrue(stats.getPeakEstimatedLiveLoaderCount() > 0);
		Assert.assertTrue(stats.getMetaspaceUsedBytes() == -1 || stats.getMetaspaceUsedBytes() > 0);
		Assert.assertTrue(stats.getMetaspaceCommittedBytes() == -1 || stats.getMetaspaceCommittedBytes() > 0);
		Assert.assertTrue(stats.getMetaspaceMaxBytes() >= -1);
		Script.resetClassLoadingStats();
		Assert.assertEquals(0, Script.getClassLoadingStats().getCreatedLoaderCount());
	}

	@Test
	public void engineShouldReuseBoundedClassLoaderGenerations() {
		Script.resetClassLoadingStats();
		ScriptEngineConfig config = ScriptEngineConfig.builder()
				.compileCacheSize(8)
				.classLoaderGenerationSize(2)
				.build();
		JvmScriptEngine engine = new JvmScriptEngine(new ScriptEngineFactory(config), config);
		Script.create("return 71001;", engine).compile();
		Script.create("return 71002;", engine).compile();
		Script.create("return 71003;", engine).compile();
		Assert.assertEquals(2, Script.getClassLoadingStats().getCreatedLoaderCount());
		Assert.assertEquals(3, Script.getClassLoadingStats().getDefinedClassCount());
		Assert.assertEquals(2, engine.getClassLoaderGeneration());
		engine.clearCompileCache();
		Script.create("return 71004;", engine).compile();
		Assert.assertEquals(3, Script.getClassLoadingStats().getCreatedLoaderCount());
	}

	@Test
	public void scopedReflectionShouldCachePositiveAndNegativeSignatures() {
		ScriptEngineConfig config = ScriptEngineConfig.builder()
				.addFunction(new ScopedFunctions())
				.addMethodExtension(CharSequence.class, new ScopedExtensions())
				.addMethodExtension(Object[].class, new ScopedExtensions())
				.reflectionCacheSize(2)
				.build();
		ScriptReflectionRegistry registry = config.getReflectionRegistry();
		Assert.assertNotNull(registry.getMethod("value", "substring", 1));
		Assert.assertNotNull(registry.getMethod("value", "substring", 1));
		Assert.assertNull(registry.getMethod("value", "definitelyMissing", 1));
		Assert.assertEquals(2, registry.getCachedMethodCount(String.class));
		Assert.assertNotNull(registry.getFunction("optimizationSnapshotFunction"));
		Assert.assertNotNull(registry.getFunction("optimizationSnapshotFunction"));
		Assert.assertEquals(1, registry.getCachedFunctionCount());
		Assert.assertEquals(2, registry.stats().getHitCount());
		Assert.assertEquals(3, registry.stats().getMissCount());
		Assert.assertEquals(2, registry.stats().getMaximumEntriesPerCache());
		cn.ykccchen.script.reflection.JavaInvoker<java.lang.reflect.Method> sequence =
				registry.getMethod("value", "sequence");
		Assert.assertNotNull(sequence);
		Assert.assertTrue(sequence.isExtension());
		Assert.assertTrue(registry.getMethod(new String[]{"a"}, "arrayLength").isExtension());
		registry.getMethod("value", "substring", 0, 1);
		Assert.assertTrue(registry.stats().getEvictionCount() > 0);
		Assert.assertTrue(registry.getCachedMethodCount(String.class) <= 2);
		Assert.assertTrue(registry.stats().getMethodCacheSize() >= 2);
		registry.clearCaches();
		Assert.assertEquals(0, registry.stats().getMethodCacheSize());
		Assert.assertEquals(0, registry.getCachedFunctionCount());
		long misses = registry.stats().getMissCount();
		Assert.assertNotNull(registry.getMethod("value", "substring", 1));
		Assert.assertEquals(misses + 1, registry.stats().getMissCount());
	}

	@Test
	public void hostPolicyAndCallLimitShouldIsolateJavaBoundary() {
		ScriptAccessPolicy policy = ScriptAccessPolicy.builder()
				.allowClassPrefix("java.lang.")
				.denyMember("java.lang.System", "getProperty")
				.build();
		ScriptEngineConfig config = ScriptEngineConfig.builder().accessPolicy(policy).build();
		JvmScriptEngine engine = new JvmScriptEngine(new ScriptEngineFactory(config), config);
		Assert.assertEquals(3, Script.create("return Math.abs(-3);", engine)
				.execute(new ScriptContext()));
		assertThrows(cn.ykccchen.script.exception.ScriptEvaluationException.class, () ->
				Script.create("return System.getProperty('user.home');", engine)
						.execute(new ScriptContext()));

		ScriptContext limited = new ScriptContext().setExecutionLimits(
				ScriptExecutionLimits.builder().maxHostCalls(1).build());
		try {
			Script.create("var values = [1]; values.size(); return values.size();", null)
					.execute(limited);
			Assert.fail("Expected host call limit");
		} catch (cn.ykccchen.script.exception.ScriptExecutionException exception) {
			Assert.assertEquals(cn.ykccchen.script.exception.ScriptExecutionException.Reason.HOST_CALL_LIMIT,
					exception.getReason());
		}

		ScriptAccessPolicy declaringPolicy = ScriptAccessPolicy.builder()
				.allowClassPrefix(HostChild.class.getName())
				.build();
		ScriptEngineConfig declaringConfig = ScriptEngineConfig.builder()
				.accessPolicy(declaringPolicy).build();
		JvmScriptEngine declaringEngine = new JvmScriptEngine(
				new ScriptEngineFactory(declaringConfig), declaringConfig);
		Assert.assertEquals("child", Script.create("return child.local();", declaringEngine)
				.execute(new ScriptContext().set("child", new HostChild())));
		assertThrows(cn.ykccchen.script.exception.ScriptEvaluationException.class, () ->
				Script.create("return child.inherited();", declaringEngine)
						.execute(new ScriptContext().set("child", new HostChild())));
	}

	@Test
	public void accessPolicyPrefixesShouldRespectQualifiedNameBoundaries() {
		ScriptAccessPolicy packagePolicy = ScriptAccessPolicy.builder()
				.allowClassPrefix("java.lang")
				.build();
		Assert.assertTrue(packagePolicy.allowClass("java.lang.String"));
		Assert.assertTrue(packagePolicy.allowClass("java.lang.invoke.MethodHandle"));
		Assert.assertFalse(packagePolicy.allowClass("java.language.Fake"));

		ScriptAccessPolicy exactClassPolicy = ScriptAccessPolicy.builder()
				.allowClassPrefix(HostChild.class.getName())
				.build();
		Assert.assertTrue(exactClassPolicy.allowClass(HostChild.class.getName()));
		Assert.assertFalse(exactClassPolicy.allowClass(HostChildExtra.class.getName()));

		ScriptAccessPolicy memberPolicy = ScriptAccessPolicy.builder()
				.defaultClassAccess(true)
				.defaultMemberAccess(false)
				.allowMember(HostChild.class.getName(), "local")
				.build();
		Assert.assertTrue(memberPolicy.allowMember(HostChild.class, "local"));
		Assert.assertFalse(memberPolicy.allowMember(HostChildExtra.class, "local"));
	}

	@Test
	public void productionPolicyShouldBeDenyByDefaultAndCapabilityBased() {
		ScriptAccessPolicy policy = ScriptAccessPolicy.productionDefaults();
		Assert.assertTrue(policy.allowClass("java.lang.String"));
		Assert.assertTrue(policy.allowClass("java.util.ArrayList"));
		Assert.assertTrue(policy.allowClass("java.time.LocalDate"));
		Assert.assertFalse(policy.allowClass("java.lang.System"));
		Assert.assertFalse(policy.allowClass("java.lang.Runtime"));
		Assert.assertFalse(policy.allowClass("java.util.concurrent.Executors"));
		Assert.assertFalse(policy.allowClass("java.io.File"));
		Assert.assertFalse(policy.allowClass("java.net.Socket"));
		Assert.assertFalse(policy.allowMember(String.class, "getClass"));
		Assert.assertFalse(policy.allowMember(String.class, "class"));
		Assert.assertTrue(policy.allowMember(String.class, "substring"));

		ScriptAccessPolicy extended = ScriptAccessPolicy.productionBuilder()
				.allowCapability(ScriptAccessPolicy.Capability.FILE_IO)
				.allowCapability(ScriptAccessPolicy.Capability.NETWORK)
				.allowCapability(ScriptAccessPolicy.Capability.ASYNC_INTEROP)
				.allowClassPrefix(HostChild.class.getName())
				.build();
		Assert.assertTrue(extended.allowClass("java.io.File"));
		Assert.assertTrue(extended.allowClass("java.nio.file.Files"));
		Assert.assertTrue(extended.allowClass("java.net.Socket"));
		Assert.assertTrue(extended.allowClass("java.util.concurrent.CompletableFuture"));
		Assert.assertFalse(extended.allowClass("java.util.concurrent.Executors"));
		Assert.assertTrue(extended.allowMember(HostChild.class, "local"));

		ScriptEngineConfig config = ScriptEngineConfig.builder().accessPolicy(policy).build();
		JvmScriptEngine engine = new JvmScriptEngine(new ScriptEngineFactory(config), config);
		try {
			Assert.assertEquals(3, Script.create("return Math.abs(-3);", engine)
					.execute(new ScriptContext()));
			assertThrows(ScriptEvaluationException.class, () -> Script.create(
					"return System.getProperty('user.home');", engine).execute(new ScriptContext()));
			assertThrows(ScriptEvaluationException.class, () -> Script.create(
					"return 'value'.class;", engine).execute(new ScriptContext()));
		} finally {
			engine.close();
		}
	}

	@Test
	@SuppressWarnings("deprecation")
	public void legacyGlobalRegistriesShouldSupportSnapshotResetAndRestore() {
		ResourceLoader.GlobalRegistrySnapshot resources = ResourceLoader.snapshotGlobalRegistry();
		JvmScriptEngine.GlobalRegistrySnapshot engine = JvmScriptEngine.snapshotGlobalRegistry();
		JavaReflection.GlobalRegistrySnapshot reflection = JavaReflection.snapshotGlobalRegistry();
		try {
			ResourceLoader.addModule("optimization_snapshot_module", 42);
			JvmScriptEngine.addDefaultImport("optimizationSnapshotImport", 7);
			JavaReflection.registerFunction(new ScopedFunctions());
			ResourceLoader.GlobalRegistrySnapshot changedResources = ResourceLoader.snapshotGlobalRegistry();
			JvmScriptEngine.GlobalRegistrySnapshot changedEngine = JvmScriptEngine.snapshotGlobalRegistry();
			JavaReflection.GlobalRegistrySnapshot changedReflection = JavaReflection.snapshotGlobalRegistry();

			ResourceLoader.resetGlobalRegistry();
			JvmScriptEngine.resetGlobalRegistry();
			JavaReflection.resetGlobalRegistry();
			Assert.assertFalse(ResourceLoader.getModuleNames().contains("optimization_snapshot_module"));
			Assert.assertFalse(JvmScriptEngine.getDefaultImports().containsKey("optimizationSnapshotImport"));
			Assert.assertNull(JavaReflection.getFunction("optimizationSnapshotFunction"));

			ResourceLoader.restoreGlobalRegistry(changedResources);
			JvmScriptEngine.restoreGlobalRegistry(changedEngine);
			JavaReflection.restoreGlobalRegistry(changedReflection);
			Assert.assertEquals(42, ResourceLoader.loadModule("optimization_snapshot_module"));
			Assert.assertEquals(7, JvmScriptEngine.getDefaultImports().get("optimizationSnapshotImport"));
			Assert.assertNotNull(JavaReflection.getFunction("optimizationSnapshotFunction"));
			Assert.assertTrue(changedResources.getModuleNames().contains("optimization_snapshot_module"));
			Assert.assertTrue(changedResources.getPackages().contains("java.lang."));
			Assert.assertTrue(changedEngine.getDefaultImports().containsKey("Async"));
			Assert.assertTrue(changedReflection.getFunctionCount() > reflection.getFunctionCount());
		} finally {
			ResourceLoader.restoreGlobalRegistry(resources);
			JvmScriptEngine.restoreGlobalRegistry(engine);
			JavaReflection.restoreGlobalRegistry(reflection);
		}
	}

	@Test
	public void diagnosticsShouldCarryStableErrorCodeLocationAndSourceName() {
		ScriptDiagnostics token = Script.validate("return §;", "token-error.ms");
		Assert.assertEquals("SCRIPT_TOKEN_ERROR", token.getDiagnostics().get(0).getCode());
		assertEvaluationCode("return 'x'.definitelyMissing();", null,
				ScriptErrorCode.SCRIPT_METHOD_RESOLUTION_ERROR, "method-error.ms");
		assertEvaluationCode("import 'missing.diagnostic.Type'; return 1;", null,
				ScriptErrorCode.SCRIPT_RESOURCE_NOT_FOUND, "resource-error.ms");
		assertEvaluationCode("return null.value;", null,
				ScriptErrorCode.SCRIPT_NULL_ACCESS, "null-error.ms");
		assertEvaluationCode("return 1 < '2';", null,
				ScriptErrorCode.SCRIPT_OPERATOR_ERROR, "operator-error.ms");
		assertEvaluationCode("return (int)'x';", null,
				ScriptErrorCode.SCRIPT_TYPE_CONVERSION_ERROR, "conversion-error.ms");

		ScriptEngineConfig productionConfig = ScriptEngineConfig.builder()
				.accessPolicy(ScriptAccessPolicy.productionDefaults()).build();
		JvmScriptEngine productionEngine = new JvmScriptEngine(
				new ScriptEngineFactory(productionConfig), productionConfig);
		try {
			assertEvaluationCode("import 'java.lang.System'; return 1;", productionEngine,
					ScriptErrorCode.SCRIPT_SECURITY_ERROR, "security-error.ms");
		} finally {
			productionEngine.close();
		}

		Assert.assertEquals('x', cn.ykccchen.script.runtime.ScriptRuntime.castTo('x', "char"));
		Assert.assertEquals(Boolean.TRUE,
				cn.ykccchen.script.runtime.ScriptRuntime.castTo(Boolean.TRUE, "boolean"));
		Assert.assertEquals(ScriptErrorCode.SCRIPT_RUNTIME_ERROR,
				ScriptRuntimeException.create("message").getErrorCode());
		Assert.assertNotNull(ScriptRuntimeException.create(new Exception("cause")).getCause());
		Assert.assertNull(ScriptRuntimeException.create(42).getMessage());
	}

	@Test
	public void asyncCompositionShouldCoverBoundaryAndRejectionPaths() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
		ScriptContext context = new ScriptContext()
				.setLanguageAsyncExecutor(executor)
				.setLanguageAsyncScheduler(scheduler);
		try {
			Assert.assertEquals(Collections.emptyList(), LanguageAsyncRuntime.all(context, null)
					.toCompletableFuture().get());
			Assert.assertEquals(Arrays.asList(1, 2), LanguageAsyncRuntime.all(context, new Object[]{
					CompletableFuture.completedFuture(1), CompletableFuture.completedFuture(2)})
					.toCompletableFuture().get());
			Assert.assertEquals(1, LanguageAsyncRuntime.race(context,
					Collections.singletonList(CompletableFuture.completedFuture(1)))
					.toCompletableFuture().get());
			CompletableFuture<Object> failedStage = new CompletableFuture<>();
			failedStage.completeExceptionally(new IllegalStateException("stage-failed"));
			try {
				LanguageAsyncRuntime.all(context, Collections.singletonList(failedStage))
						.toCompletableFuture().get();
				Assert.fail("Expected all failure");
			} catch (ExecutionException exception) {
				Assert.assertEquals("stage-failed", exception.getCause().getMessage());
			}
			try {
				LanguageAsyncRuntime.race(context, Collections.singletonList(failedStage))
						.toCompletableFuture().get();
				Assert.fail("Expected race failure");
			} catch (ExecutionException exception) {
				Assert.assertEquals("stage-failed", exception.getCause().getMessage());
			}

			FutureTask<Object> plainFuture = new FutureTask<>(() -> 9);
			plainFuture.run();
			Assert.assertEquals(Collections.singletonList(9), LanguageAsyncRuntime.all(
					context, Collections.singletonList(plainFuture)).toCompletableFuture().get());
			FutureTask<Object> failedFuture = new FutureTask<>(() -> {
				throw new Exception("future-failed");
			});
			failedFuture.run();
			try {
				LanguageAsyncRuntime.all(context, Collections.singletonList(failedFuture))
						.toCompletableFuture().get();
				Assert.fail("Expected future failure");
			} catch (ExecutionException exception) {
				Assert.assertEquals("future-failed", exception.getCause().getMessage());
			}
			assertThrows(ScriptRuntimeException.class,
					() -> LanguageAsyncRuntime.race(context, Collections.emptyList()));
			assertThrows(ScriptRuntimeException.class,
					() -> LanguageAsyncRuntime.all(context, Collections.singletonList(1)));
			assertThrows(IllegalArgumentException.class,
					() -> LanguageAsyncRuntime.timeout(context, CompletableFuture.completedFuture(1),
							0, TimeUnit.MILLISECONDS));
			Assert.assertEquals(3, LanguageAsyncRuntime.timeout(context,
					CompletableFuture.completedFuture(3), 1, TimeUnit.SECONDS)
					.toCompletableFuture().get());

			CompletableFuture<Object> never = new CompletableFuture<>();
			try {
				LanguageAsyncRuntime.timeout(context, never, 5, TimeUnit.MILLISECONDS)
						.toCompletableFuture().get();
				Assert.fail("Expected timeout");
			} catch (ExecutionException exception) {
				Assert.assertTrue(exception.getCause() instanceof TimeoutException);
				Assert.assertTrue(never.isCancelled());
			}
			assertThrows(ScriptRuntimeException.class, () -> LanguageAsyncRuntime.timeout(
					new ScriptContext().setLanguageAsyncExecutor(executor),
					CompletableFuture.completedFuture(1), 1, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
			scheduler.shutdownNow();
		}

		ScheduledExecutorService rejected = Executors.newSingleThreadScheduledExecutor();
		rejected.shutdownNow();
		CompletableFuture<Object> rejectedResult = LanguageAsyncRuntime.timeout(
				new ScriptContext().setLanguageAsyncScheduler(rejected),
				CompletableFuture.completedFuture(1), 1, TimeUnit.SECONDS).toCompletableFuture();
		try {
			rejectedResult.get();
			Assert.fail("Expected scheduler rejection");
		} catch (ExecutionException expected) {
			Assert.assertTrue(expected.getCause() instanceof java.util.concurrent.RejectedExecutionException);
		}

		ExecutorService rejectedExecutor = Executors.newSingleThreadExecutor();
		rejectedExecutor.shutdownNow();
		FutureTask<Object> ordinary = new FutureTask<>(() -> 1);
		ordinary.run();
		CompletableFuture<Object> rejectedBridge = LanguageAsyncRuntime.all(
				new ScriptContext().setLanguageBlockingExecutor(rejectedExecutor),
				Collections.singletonList(ordinary)).toCompletableFuture();
		try {
			rejectedBridge.get();
			Assert.fail("Expected bridge rejection");
		} catch (ExecutionException expected) {
			Assert.assertTrue(expected.getCause() instanceof java.util.concurrent.RejectedExecutionException);
		}
		Assert.assertEquals("[1]", String.valueOf(LanguageAsyncRuntime.all(
				new ScriptContext(), Collections.singletonList(ordinary))
				.toCompletableFuture().get()));
	}

	@Test(timeout = 10000)
	public void engineFutureBridgesShouldBeIsolatedAndOwnedExplicitly() throws Exception {
		ScriptEngineConfig bridgeConfig = ScriptEngineConfig.builder()
				.languageBlockingThreads(1)
				.languageBlockingQueueCapacity(1)
				.build();
		JvmScriptEngine first = new JvmScriptEngine(new ScriptEngineFactory(bridgeConfig), bridgeConfig);
		JvmScriptEngine second = new JvmScriptEngine(new ScriptEngineFactory(bridgeConfig), bridgeConfig);
		Assert.assertNotSame(first.getLanguageBlockingExecutor(), second.getLanguageBlockingExecutor());
		ThreadPoolExecutor firstBridge = (ThreadPoolExecutor) first.getLanguageBlockingExecutor();
		Assert.assertTrue(firstBridge.allowsCoreThreadTimeOut());
		Assert.assertTrue(firstBridge.submit(() -> Thread.currentThread().isDaemon()).get());
		ExecutorService host = Executors.newFixedThreadPool(2);
		FutureTask<Object> blockedOne = new FutureTask<>(() -> 1);
		FutureTask<Object> blockedTwo = new FutureTask<>(() -> 2);
		Script firstScript = Script.create("return await Async.all([input]);", first);
		try {
			Future<?> active = host.submit(() -> firstScript.execute(
					new ScriptContext().set("input", blockedOne)));
			awaitBridgeState(first, 1, 0);
			Future<?> queued = host.submit(() -> firstScript.execute(
					new ScriptContext().set("input", blockedTwo)));
			awaitBridgeState(first, 1, 1);

			FutureTask<Object> completed = new FutureTask<>(() -> 9);
			completed.run();
			Assert.assertEquals(Collections.singletonList(9), Script.create(
					"return await Async.all([input]);", second)
					.execute(new ScriptContext().set("input", completed)));
			try {
				firstScript.execute(new ScriptContext().set("input", completed));
				Assert.fail("Expected isolated first-engine bridge rejection");
			} catch (RuntimeException expected) {
				Assert.assertTrue(first.getFutureBridgeStats().getBridgeRejectedCount() > 0);
			}
			blockedOne.cancel(true);
			blockedTwo.cancel(true);
			try { active.get(2, TimeUnit.SECONDS); } catch (Exception ignored) { }
			try { queued.get(2, TimeUnit.SECONDS); } catch (Exception ignored) { }
		} finally {
			blockedOne.cancel(true);
			blockedTwo.cancel(true);
			host.shutdownNow();
			first.close();
			second.close();
		}
		Assert.assertTrue(first.isFutureBridgeShutdown());
		Assert.assertTrue(second.isFutureBridgeShutdown());

		ExecutorService external = Executors.newSingleThreadExecutor();
		ScriptEngineConfig externalConfig = ScriptEngineConfig.builder()
				.languageBlockingExecutor(external)
				.build();
		JvmScriptEngine externalEngine = new JvmScriptEngine(
				new ScriptEngineFactory(externalConfig), externalConfig);
		externalEngine.close();
		Assert.assertFalse(external.isShutdown());
		external.shutdownNow();
	}

	private static void awaitBridgeState(JvmScriptEngine engine, int active, int queued)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (System.nanoTime() < deadline) {
			ScriptAsyncRuntimeStats stats = engine.getFutureBridgeStats();
			if (stats.getBridgeActiveCount() == active && stats.getBridgeQueueSize() == queued) return;
			Thread.sleep(1);
		}
		Assert.fail("Future bridge did not reach active=" + active + ", queued=" + queued);
	}

	@Test
	public void asyncResultShouldExposeEveryTerminalState() throws Exception {
		ScriptContext context = new ScriptContext();
		ScriptAsyncResult cancelled = new ScriptAsyncResult(context);
		Assert.assertTrue(cancelled.getTaskId() > 0);
		Assert.assertTrue(cancelled.cancel(false));
		FutureTask<Object> cancelledWorker = new FutureTask<>(() -> 1);
		cancelled.setWorkerFuture(cancelledWorker);
		Assert.assertTrue(cancelledWorker.isCancelled());
		Assert.assertTrue(cancelled.isCancelled());
		Assert.assertTrue(cancelled.isDone());
		Assert.assertFalse(cancelled.cancel());

		ScriptAsyncResult timedOut = new ScriptAsyncResult(context);
		timedOut.timeout();
		Assert.assertEquals(ScriptTaskState.TIMED_OUT, timedOut.getState());
		Assert.assertNotNull(timedOut.getFailure());

		ScriptAsyncResult succeeded = new ScriptAsyncResult(context);
		Assert.assertTrue(succeeded.start());
		Assert.assertFalse(succeeded.start());
		succeeded.succeed(42);
		Assert.assertEquals(42, succeeded.get());
		Assert.assertEquals(ScriptTaskState.SUCCEEDED, succeeded.getState());

		ScriptAsyncResult failed = new ScriptAsyncResult(context);
		Assert.assertTrue(failed.start());
		failed.fail(new Exception("checked"));
		try {
			failed.get();
			Assert.fail("Expected failure");
		} catch (ExecutionException exception) {
			Assert.assertEquals("checked", exception.getCause().getMessage());
		}

		ScriptAsyncResult runningCancellation = new ScriptAsyncResult(context);
		runningCancellation.start();
		Assert.assertTrue(runningCancellation.cancel(true));
		FutureTask<Object> cancellingWorker = new FutureTask<>(() -> 1);
		runningCancellation.setWorkerFuture(cancellingWorker);
		Assert.assertTrue(cancellingWorker.isCancelled());
		runningCancellation.fail(new InterruptedException());
		Assert.assertEquals(ScriptTaskState.CANCELLED, runningCancellation.getState());

		ScriptAsyncResult runningTimeout = new ScriptAsyncResult(context);
		runningTimeout.start();
		runningTimeout.timeout();
		runningTimeout.fail(new InterruptedException());
		Assert.assertEquals(ScriptTaskState.TIMED_OUT, runningTimeout.getState());

		ScriptAsyncResult classifiedCancellation = new ScriptAsyncResult(context);
		classifiedCancellation.start();
		classifiedCancellation.fail(new cn.ykccchen.script.exception.ScriptExecutionException(
				cn.ykccchen.script.exception.ScriptExecutionException.Reason.CANCELLED, "cancelled"));
		Assert.assertEquals(ScriptTaskState.CANCELLED, classifiedCancellation.getState());
		ScriptAsyncResult classifiedTimeout = new ScriptAsyncResult(context);
		classifiedTimeout.start();
		classifiedTimeout.fail(new cn.ykccchen.script.exception.ScriptExecutionException(
				cn.ykccchen.script.exception.ScriptExecutionException.Reason.TIMED_OUT, "timeout"));
		Assert.assertEquals(ScriptTaskState.TIMED_OUT, classifiedTimeout.getState());

		ScriptAsyncResult rejected = new ScriptAsyncResult(context);
		rejected.reject(new ScriptAsyncRejectedException("quota"));
		Assert.assertEquals(ScriptTaskState.REJECTED, rejected.getState());
	}

	@Test
	public void contextShouldCoverStringCancellationAndExclusiveClaimBoundaries() {
		ScriptContext context = new ScriptContext().set("number", 42);
		Assert.assertEquals("42", context.getString("number"));
		Assert.assertFalse(context.isCancellationRequested());
		Assert.assertTrue(context.tryClaimAsyncExecution(100));
		Assert.assertFalse(context.tryClaimAsyncExecution(101));
		context.releaseAsyncExecution(101);
		Assert.assertEquals(100, context.getAsyncTaskId());
		context.releaseAsyncExecution(100);
		Assert.assertEquals(0, context.getAsyncTaskId());

		Thread.currentThread().interrupt();
		try {
			context.checkpoint();
			Assert.fail("Expected interrupted checkpoint");
		} catch (cn.ykccchen.script.exception.ScriptExecutionException expected) {
			Assert.assertEquals(cn.ykccchen.script.exception.ScriptExecutionException.Reason.CANCELLED,
					expected.getReason());
		} finally {
			Thread.interrupted();
		}
	}

	private static void assertThrows(Class<? extends Throwable> type, ThrowingRunnable operation) {
		try {
			operation.run();
			Assert.fail("Expected " + type.getName());
		} catch (Throwable failure) {
			Assert.assertTrue("Unexpected failure: " + failure, type.isInstance(failure));
		}
	}

	private static void assertEvaluationCode(String source, javax.script.ScriptEngine engine,
			ScriptErrorCode expectedCode, String sourceName) {
		ScriptContext context = new ScriptContext();
		context.setScriptName(sourceName);
		try {
			Script.create(source, engine).execute(context);
			Assert.fail("Expected " + expectedCode);
		} catch (ScriptEvaluationException exception) {
			Assert.assertEquals(expectedCode, exception.getErrorCode());
			Assert.assertEquals(sourceName, exception.getSourceName());
			Assert.assertNotNull(exception.getLocation());
		}
	}

	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
