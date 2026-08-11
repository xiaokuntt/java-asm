package cn.ykccchen.script.input;

import cn.ykccchen.script.annotation.Function;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DynamicMainFunctions {

	private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPERS = createPrimitiveWrappers();
	private static final Pattern PUBLIC_CLASS_PATTERN = Pattern.compile("public\\s+(?:final\\s+|abstract\\s+)*class\\s+([A-Za-z_$][A-Za-z\\d_$]*)");
	private static final Pattern CLASS_PATTERN = Pattern.compile("\\bclass\\s+([A-Za-z_$][A-Za-z\\d_$]*)");

	@Function
	public Object dynamic_main_invoke(String source, String method, Object... args) {
		return dynamic_main_invoke_with_class(source, null, method, args);
	}

	@Function
	public Object dynamic_main_invoke_with_class(String source, String className, String method, Object... args) {
		Path tempDir = null;
		try {
			String resolvedClassName = resolveClassName(source, className);
			tempDir = compileMainClass(source, resolvedClassName);
			try (URLClassLoader classLoader = new URLClassLoader(new URL[]{tempDir.toUri().toURL()}, getClass().getClassLoader())) {
				Class<?> mainClass = Class.forName(resolvedClassName, true, classLoader);
				Object instance = mainClass.getDeclaredConstructor().newInstance();
				Method targetMethod = findCompatibleMethod(mainClass, method, args);
				targetMethod.setAccessible(true);
				return targetMethod.invoke(instance, args);
			}
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		} finally {
			deleteDirectoryQuietly(tempDir);
		}
	}

	private Method findCompatibleMethod(Class<?> mainClass, String methodName, Object[] args) throws NoSuchMethodException {
		Method best = null;
		int bestScore = -1;
		Method[] methods = mainClass.getMethods();
		for (Method method : methods) {
			if (!method.getName().equals(methodName)) {
				continue;
			}
			Class<?>[] parameterTypes = method.getParameterTypes();
			if (parameterTypes.length != args.length) {
				continue;
			}
			int score = 0;
			boolean compatible = true;
			for (int i = 0; i < parameterTypes.length; i++) {
				int match = matchScore(parameterTypes[i], args[i]);
				if (match < 0) {
					compatible = false;
					break;
				}
				score += match;
			}
			if (compatible && score > bestScore) {
				best = method;
				bestScore = score;
			}
		}
		if (best == null) {
			String signature = Arrays.stream(args).map(arg -> arg == null ? "null" : arg.getClass().getName()).collect(Collectors.joining(","));
			throw new NoSuchMethodException("No compatible method found: " + methodName + "(" + signature + ")");
		}
		return best;
	}

	private int matchScore(Class<?> parameterType, Object arg) {
		if (arg == null) {
			return parameterType.isPrimitive() ? -1 : 1;
		}
		Class<?> argType = arg.getClass();
		if (parameterType.isPrimitive()) {
			Class<?> wrapper = PRIMITIVE_WRAPPERS.get(parameterType);
			return wrapper != null && wrapper.equals(argType) ? 3 : -1;
		}
		if (parameterType.equals(argType)) {
			return 3;
		}
		if (parameterType.isAssignableFrom(argType)) {
			return 2;
		}
		return -1;
	}

	private static Map<Class<?>, Class<?>> createPrimitiveWrappers() {
		Map<Class<?>, Class<?>> wrappers = new HashMap<>();
		wrappers.put(boolean.class, Boolean.class);
		wrappers.put(byte.class, Byte.class);
		wrappers.put(short.class, Short.class);
		wrappers.put(int.class, Integer.class);
		wrappers.put(long.class, Long.class);
		wrappers.put(float.class, Float.class);
		wrappers.put(double.class, Double.class);
		wrappers.put(char.class, Character.class);
		return wrappers;
	}

	private String resolveClassName(String source, String className) {
		if (className != null && !className.trim().isEmpty()) {
			return className.trim();
		}
		Matcher publicClassMatcher = PUBLIC_CLASS_PATTERN.matcher(source);
		if (publicClassMatcher.find()) {
			return publicClassMatcher.group(1);
		}
		Matcher classMatcher = CLASS_PATTERN.matcher(source);
		if (classMatcher.find()) {
			return classMatcher.group(1);
		}
		throw new IllegalArgumentException("No class definition found in dynamic source");
	}

	private Path compileMainClass(String source, String className) throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			throw new IllegalStateException("JavaCompiler is not available in current JRE");
		}
		Path tempDir = Files.createTempDirectory("magic-script-dynamic-main-");
		Path sourceFile = tempDir.resolve(className + ".java");
		Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));

		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(Collections.singletonList(sourceFile.toFile()));
			Iterable<String> options = Arrays.asList("-classpath", System.getProperty("java.class.path"), "-d", tempDir.toString());
			Boolean success = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
			if (!Boolean.TRUE.equals(success)) {
				String message = diagnostics.getDiagnostics()
						.stream()
						.map(Diagnostic::toString)
						.collect(Collectors.joining(System.lineSeparator()));
				throw new IllegalArgumentException("Compile dynamic class failed: " + message);
			}
		}
		return tempDir;
	}

	private void deleteDirectoryQuietly(Path path) {
		if (path == null) {
			return;
		}
		try (Stream<Path> stream = Files.walk(path)) {
			stream.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
	}
}
