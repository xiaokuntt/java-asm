package cn.ykccchen.script;

import cn.ykccchen.script.annotation.Function;
import cn.ykccchen.script.exception.ResourceNotFoundException;
import cn.ykccchen.script.reflection.JavaReflection;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class RegistrationLifecycleTests {

	public static final class Functions {
		@Function
		public String registrationLifecycleFunction() {
			return "registered";
		}

		@Function
		public int registrationLifecycleApply(java.util.function.Function<Integer, Integer> function) {
			return function.apply(41);
		}
	}

	public static final class StringExtensions {
		public String registrationLifecycleExtension(String source) {
			return source + "-extended";
		}
	}

	@Test
	public void functionRegistrationCanBeClosedIdempotently() {
		Registration registration = JavaReflection.registerFunction(new Functions());
		Assert.assertNotNull(JavaReflection.getFunction("registrationLifecycleFunction"));

		registration.close();
		registration.close();
		Assert.assertNull(JavaReflection.getFunction("registrationLifecycleFunction"));
	}

	@Test
	public void extensionRegistrationCanBeClosed() {
		Registration registration = JavaReflection.registerMethodExtension(String.class, new StringExtensions());
		Assert.assertNotNull(JavaReflection.getExtensionMethod("value", "registrationLifecycleExtension"));

		registration.close();
		Assert.assertNull(JavaReflection.getExtensionMethod("value", "registrationLifecycleExtension"));
	}

	@Test
	public void moduleAndLoaderRegistrationsCanBeClosed() {
		Registration module = ResourceLoader.addModule("registration_lifecycle_module", "module");
		Registration loader = ResourceLoader.addFunctionLoader((context, name) ->
				"registration_lifecycle_function".equals(name) ? "loaded" : null);

		Assert.assertEquals("module", ResourceLoader.loadModule("registration_lifecycle_module"));
		Assert.assertEquals("loaded", ResourceLoader.loadFunction(new ScriptContext(), "registration_lifecycle_function"));
		module.close();
		loader.close();

		assertResourceMissing(() -> ResourceLoader.loadModule("registration_lifecycle_module"));
		assertResourceMissing(() -> ResourceLoader.loadFunction(new ScriptContext(), "registration_lifecycle_function"));
	}

	@Test
	public void packageRegistrationCanBeClosed() {
		Registration registration = ResourceLoader.addPackage("cn.ykccchen.script.*");
		Assert.assertEquals(RegistrationLifecycleTests.class,
				ResourceLoader.findClass("RegistrationLifecycleTests"));

		registration.close();
		Assert.assertNull(ResourceLoader.findClass("RegistrationLifecycleTests"));
	}

	@Test
	public void concurrentExtensionCloseKeepsActiveRegistration() throws Exception {
		Registration persistent = JavaReflection.registerMethodExtension(String.class, new StringExtensions());
		ExecutorService executor = Executors.newFixedThreadPool(4);
		try {
			List<Future<?>> tasks = new ArrayList<>();
			for (int i = 0; i < 50; i++) {
				tasks.add(executor.submit(() ->
						JavaReflection.registerMethodExtension(String.class, new StringExtensions()).close()));
			}
			for (Future<?> task : tasks) {
				task.get();
			}
			Assert.assertNotNull(JavaReflection.getExtensionMethod("value", "registrationLifecycleExtension"));
		} finally {
			executor.shutdownNow();
			persistent.close();
		}
		Assert.assertNull(JavaReflection.getExtensionMethod("value", "registrationLifecycleExtension"));
	}

	@Test
	public void engineScopedReflectionRegistriesShouldBeIsolated() {
		ScriptEngineConfig configured = ScriptEngineConfig.builder()
				.addFunction(new Functions())
				.addMethodExtension(String.class, new StringExtensions())
				.build();
		JvmScriptEngine first = (JvmScriptEngine) new ScriptEngineFactory(configured).getScriptEngine();
		JvmScriptEngine second = (JvmScriptEngine) new ScriptEngineFactory(
				ScriptEngineConfig.builder().build()).getScriptEngine();

		Assert.assertEquals("registered-value-extended", Script.create(
				"return registrationLifecycleFunction() + '-' "
						+ "+ 'value'.registrationLifecycleExtension();", first)
				.execute(new ScriptContext()));
		Assert.assertEquals(42, Script.create(
				"return registrationLifecycleApply(value => value + 1);", first)
				.execute(new ScriptContext()));
		try {
			Script.create("return registrationLifecycleFunction();", second)
					.execute(new ScriptContext());
			Assert.fail("Expected isolated registry");
		} catch (RuntimeException expected) {
			// The second engine must not inherit another engine's functions.
		}
	}

	private static void assertResourceMissing(Runnable operation) {
		try {
			operation.run();
			Assert.fail("resource should have been unregistered");
		} catch (ResourceNotFoundException expected) {
			// expected
		}
	}
}
