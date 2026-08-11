package cn.ykccchen.script.benchmark;

import cn.ykccchen.script.JvmScriptEngine;
import cn.ykccchen.script.Script;
import cn.ykccchen.script.ScriptContext;
import cn.ykccchen.script.ScriptEngineConfig;
import cn.ykccchen.script.ScriptEngineFactory;
import cn.ykccchen.script.ScriptRepository;
import cn.ykccchen.script.reflection.ScriptReflectionRegistry;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/** Repeatable microbenchmarks for the main production hot paths. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class ScriptEngineBenchmark {

	private static final String SOURCE = "return left + right;";
	private JvmScriptEngine engine;
	private Script script;
	private ScriptRepository repository;
	private ScriptReflectionRegistry reflection;

	@Setup(Level.Trial)
	public void setup() {
		ScriptEngineConfig config = ScriptEngineConfig.builder()
				.compileCacheSize(128)
				.reflectionCacheSize(128)
				.build();
		engine = new JvmScriptEngine(new ScriptEngineFactory(config), config);
		script = Script.create(SOURCE, engine);
		script.compile();
		repository = new ScriptRepository(engine);
		repository.reload("sum", SOURCE);
		reflection = config.getReflectionRegistry();
	}

	@Benchmark
	public Object executeCompiledScript() {
		return script.execute(new ScriptContext().set("left", 20).set("right", 22));
	}

	@Benchmark
	public Script compileCacheHit() {
		return Script.create(SOURCE, engine);
	}

	@Benchmark
	public Object executeRepositoryRevision() {
		return repository.execute("sum", new ScriptContext().set("left", 20).set("right", 22));
	}

	@Benchmark
	public Object resolveCachedHostMethod() {
		return reflection.getMethod("benchmark", "substring", 1);
	}

	@TearDown(Level.Trial)
	public void tearDown() {
		engine.close();
	}
}
