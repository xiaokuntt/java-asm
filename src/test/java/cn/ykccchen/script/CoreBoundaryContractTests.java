package cn.ykccchen.script;

import cn.ykccchen.script.annotation.Comment;
import cn.ykccchen.script.exception.ResourceNotFoundException;
import cn.ykccchen.script.exception.ScriptRuntimeException;
import cn.ykccchen.script.functions.DynamicModuleImport;
import cn.ykccchen.script.runtime.RuntimeContext;
import org.junit.Assert;
import org.junit.Test;

import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.SimpleScriptContext;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class CoreBoundaryContractTests {

	public enum FixtureEnum { FIRST, SECOND }

	public static class MetadataFixture {
		public String visibleField;

		public boolean isReady() {
			return true;
		}

		@Deprecated
		@Comment(value = "documented method", origin = true)
		public String documented(
			@Comment(value = "documented parameter", name = "value") String value,
			RuntimeContext runtime,
			String... rest) {
			return value + rest.length;
		}
	}

	@Test
	public void shouldBuildAndUseIsolatedEngineResources() throws Exception {
		ScriptExecutionLimits limits = ScriptExecutionLimits.builder()
			.timeout(2, TimeUnit.SECONDS).maxCheckpoints(20).build();
		ScriptEngineConfig config = ScriptEngineConfig.builder()
			.addDefaultImport("answer", 42)
			.addModule("module", "configured-module")
			.addPackage("fixture.*")
			.classLoader(name -> name.equals("fixture.Type") ? new StringBuilder() : null)
			.executionLimits(limits)
			.compileCacheSize(7)
			.addFunctionLoader((context, name) -> name.equals("configuredFunction") ? 9 : null)
			.addScriptLanguageLoader(name -> name.equals("configuredLanguage")
				? (variables, source) -> variables.size() + source.length() : null)
			.build();

		Assert.assertEquals(42, config.getDefaultImports().get("answer"));
		Assert.assertEquals(Collections.singleton("module"), config.getModuleNames());
		Assert.assertTrue(config.getPackages().contains("fixture."));
		Assert.assertSame(limits, config.getExecutionLimits());
		Assert.assertEquals(7, config.getCompileCacheSize());
		Assert.assertEquals("configured-module", config.loadModule("module"));
		Assert.assertTrue(config.loadClass("fixture.Type") instanceof StringBuilder);
		Assert.assertEquals(StringBuilder.class, config.forName("fixture.Type"));
		Assert.assertEquals(StringBuilder.class, config.findClass("Type"));
		Assert.assertEquals(9, config.loadFunction(new ScriptContext(), "configuredFunction"));
		Assert.assertEquals(3, config.loadScriptLanguage("configuredLanguage")
			.apply(Collections.singletonMap("x", 1), "ab"));

		ScriptContext context = new ScriptContext();
		context.setEngineConfig(config);
		Assert.assertEquals("configured-module", ResourceLoader.loadModule(context, "module"));
		Assert.assertTrue(ResourceLoader.loadClass(context, "fixture.Type") instanceof StringBuilder);
		Assert.assertEquals(StringBuilder.class, ResourceLoader.forName(context, "fixture.Type"));
		Assert.assertEquals(StringBuilder.class, ResourceLoader.findClass(context, "Type"));
		Assert.assertEquals(9, ResourceLoader.loadFunction(context, "configuredFunction"));
		Assert.assertNotNull(ResourceLoader.loadScriptLanguage(context, "configuredLanguage"));
	}

	@Test
	public void shouldReportConfigurationFailuresPrecisely() {
		ScriptEngineConfig empty = ScriptEngineConfig.builder()
			.classLoader(name -> null)
			.addFunctionLoader((context, name) -> null)
			.addScriptLanguageLoader(name -> null)
			.build();
		assertThrows(ResourceNotFoundException.class, () -> empty.loadModule("missing"));
		assertThrows(ResourceNotFoundException.class, () -> empty.loadClass("missing.Type"));
		assertThrows(ClassNotFoundException.class, () -> empty.forName("missing.Type"));
		Assert.assertNull(empty.findClass("Missing"));
		assertThrows(ResourceNotFoundException.class,
			() -> empty.loadFunction(new ScriptContext(), "missing"));
		assertThrows(ResourceNotFoundException.class, () -> empty.loadScriptLanguage("missing"));

		ScriptEngineConfig broken = ScriptEngineConfig.builder()
			.addFunctionLoader((context, name) -> { throw new IllegalStateException("function failure"); })
			.addScriptLanguageLoader(name -> { throw new IllegalStateException("language failure"); })
			.build();
		assertThrows(ScriptRuntimeException.class,
			() -> broken.loadFunction(new ScriptContext(), "broken"));
		assertThrows(ScriptRuntimeException.class, () -> broken.loadScriptLanguage("broken"));
		assertThrows(IllegalArgumentException.class,
			() -> ScriptEngineConfig.builder().compileCacheSize(0));

		ScriptEngineConfig defaults = ScriptEngineConfig.builder().build();
		Assert.assertEquals(String.class, defaults.loadClass("java.lang.String"));
		Assert.assertNull(defaults.findClass("DefinitelyMissingType"));
	}

	@Test
	public void shouldExposeGlobalResourceMetadataAndLoaderLifecycle() {
		Registration classModule = ResourceLoader.addModule("coverage_class_module", String.class);
		Registration dynamicModule = ResourceLoader.addModule("coverage_dynamic_module",
			new DynamicModuleImport(BigDecimal.class, context -> BigDecimal.ONE));
		Registration objectModule = ResourceLoader.addModule("coverage_object_module", new StringBuilder());
		Registration language = ResourceLoader.addScriptLanguageLoader(name -> name.equals("coverage_language")
			? (variables, source) -> source : null);
		try {
			Map<String, ScriptClass> modules = ResourceLoader.getModules();
			Assert.assertTrue(modules.get("coverage_class_module").isModule());
			Assert.assertEquals(BigDecimal.class.getName(), modules.get("coverage_dynamic_module").getClassName());
			Assert.assertEquals(StringBuilder.class.getName(), modules.get("coverage_object_module").getClassName());
			Assert.assertEquals("source", ResourceLoader.loadScriptLanguage("coverage_language")
				.apply(Collections.emptyMap(), "source"));
			Assert.assertTrue(ResourceLoader.getModuleNames().contains("coverage_class_module"));
		} finally {
			language.close();
			objectModule.close();
			dynamicModule.close();
			classModule.close();
		}
		assertThrows(ResourceNotFoundException.class,
			() -> ResourceLoader.loadScriptLanguage("coverage_language"));

		Registration brokenLanguage = ResourceLoader.addScriptLanguageLoader(name -> {
			throw new IllegalStateException("loader failure");
		});
		Registration brokenFunction = ResourceLoader.addFunctionLoader((context, name) -> {
			throw new IllegalStateException("loader failure");
		});
		try {
			assertThrows(ScriptRuntimeException.class,
				() -> ResourceLoader.loadScriptLanguage("broken_language"));
			assertThrows(ScriptRuntimeException.class,
				() -> ResourceLoader.loadFunction(new ScriptContext(), "broken_function"));
		} finally {
			brokenFunction.close();
			brokenLanguage.close();
		}
	}

	@Test
	public void shouldExposeScriptMetadataValueObjects() throws Exception {
		ScriptClass metadata = JvmScriptEngine.getScriptClassFromClass(MetadataFixture.class);
		Assert.assertEquals(MetadataFixture.class.getName(), metadata.getClassName());
		Assert.assertEquals(Object.class.getName(), metadata.getSuperClass());
		Assert.assertTrue(metadata.getAttributes().contains(
			new ScriptClass.ScriptAttribute("boolean", "ready")));
		ScriptClass.ScriptMethod method = new ScriptClass.ScriptMethod(
			MetadataFixture.class.getMethod("documented", String.class, RuntimeContext.class, String[].class));
		Assert.assertEquals("documented", method.getName());
		Assert.assertEquals("documented method", method.getComment());
		Assert.assertTrue(method.isOrigin());
		Assert.assertTrue(method.isDeprecated());
		Assert.assertEquals(String.class.getName(), method.getReturnType());
		Assert.assertEquals(2, method.getParameters().size());
		Assert.assertEquals("value", method.getParameters().get(0).getName());
		Assert.assertEquals("documented parameter", method.getParameters().get(0).getComment());
		Assert.assertTrue(method.getParameters().get(1).isVarArgs());
		Assert.assertEquals("String[]", method.getParameters().get(1).getType());

		ScriptClass left = new ScriptClass();
		left.setClassName("fixture");
		left.setEnums(FixtureEnum.values());
		left.addInterface(Runnable.class.getName());
		left.setModule(true);
		left.addMethod(method);
		left.addAttribute(new ScriptClass.ScriptAttribute("type", "name"));
		ScriptClass right = new ScriptClass();
		right.setClassName("fixture");
		Assert.assertEquals(left, right);
		Assert.assertEquals(left.hashCode(), right.hashCode());
		Assert.assertNotEquals(left, "fixture");
		Assert.assertArrayEquals(FixtureEnum.values(), left.getEnums());
		Assert.assertEquals(Collections.singletonList(Runnable.class.getName()), left.getInterfaces());
		Assert.assertTrue(left.isModule());
		Assert.assertTrue(left.getMethods().contains(method));
		Assert.assertTrue(left.getAttributes().contains(new ScriptClass.ScriptAttribute("type", "name")));
	}

	@Test
	public void shouldHonorJsr223FactoryAndReaderContracts() throws Exception {
		ScriptEngineFactory factory = new ScriptEngineFactory();
		Assert.assertEquals("Script", factory.getParameter(javax.script.ScriptEngine.ENGINE));
		Assert.assertEquals(factory.getEngineVersion(),
			factory.getParameter(javax.script.ScriptEngine.ENGINE_VERSION));
		Assert.assertEquals("Script", factory.getParameter(javax.script.ScriptEngine.LANGUAGE));
		Assert.assertEquals(factory.getLanguageVersion(),
			factory.getParameter(javax.script.ScriptEngine.LANGUAGE_VERSION));
		Assert.assertNull(factory.getParameter("THREADING"));
		assertThrows(IllegalArgumentException.class, () -> factory.getParameter("invalid"));
		Assert.assertEquals("first;" + System.lineSeparator() + "second;" + System.lineSeparator(),
			factory.getProgram(null, " ", "first", "second;"));

		JvmScriptEngine engine = (JvmScriptEngine) factory.getScriptEngine();
		Assert.assertSame(factory, engine.getFactory());
		Bindings bindings = engine.createBindings();
		bindings.put("value", 40);
		SimpleScriptContext context = new SimpleScriptContext();
		context.setBindings(bindings, javax.script.ScriptContext.ENGINE_SCOPE);
		Assert.assertEquals(42, engine.eval(new StringReader("return value + 2;"), context));
		CompiledScript compiled = engine.compile(new StringReader("return 6 * 7;"));
		Assert.assertEquals(42, compiled.eval(context));
		Assert.assertEquals(42, JvmScriptEngine.execute(Script.create("return 42;", engine), new ScriptContext()));
		JvmScriptEngine.addScriptClass(MetadataFixture.class);
		Assert.assertTrue(JvmScriptEngine.getScriptClassMap().containsKey(MetadataFixture.class.getName()));
		Assert.assertFalse(JvmScriptEngine.getScriptClass("missing.coverage.Type").iterator().hasNext());
		Assert.assertFalse(JvmScriptEngine.getScriptClass(FixtureEnum.class).isEmpty());
		Assert.assertNotNull(JvmScriptEngine.getFunctions());
		Assert.assertNotNull(JvmScriptEngine.getExtensionScriptClass());
	}

	private static void assertThrows(Class<? extends Throwable> expectedType, ThrowingRunnable operation) {
		try {
			operation.run();
			Assert.fail("Expected " + expectedType.getName());
		} catch (Throwable actual) {
			Assert.assertTrue("Expected " + expectedType.getName() + " but got " + actual,
				expectedType.isInstance(actual));
		}
	}

	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
