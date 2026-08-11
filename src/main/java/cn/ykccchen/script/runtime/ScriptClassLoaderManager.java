package cn.ykccchen.script.runtime;

/**
 * Reuses one generated-class loader for a bounded generation. Rotating a
 * generation drops the manager's strong reference so evicted script classes
 * and their loader can be reclaimed together.
 */
public final class ScriptClassLoaderManager {

	private final int generationSize;
	private ScriptClassLoader current;
	private ClassLoader currentParent;
	private int definitions;
	private long generation;

	public ScriptClassLoaderManager(int generationSize) {
		if (generationSize <= 0) {
			throw new IllegalArgumentException("generationSize must be greater than zero");
		}
		this.generationSize = generationSize;
	}

	public synchronized Class<ScriptRuntime> define(ClassLoader parent, String name, byte[] bytecode)
			throws ClassNotFoundException {
		if (current == null || currentParent != parent || definitions >= generationSize) {
			current = new ScriptClassLoader(parent);
			currentParent = parent;
			definitions = 0;
			generation++;
		}
		definitions++;
		return current.load(name, bytecode);
	}

	public synchronized void rotate() {
		current = null;
		currentParent = null;
		definitions = 0;
	}

	public synchronized long getGeneration() {
		return generation;
	}

	public int getGenerationSize() {
		return generationSize;
	}

	public synchronized int getCurrentGenerationDefinitionCount() { return definitions; }
}
