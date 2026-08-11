package cn.ykccchen.script.compile;


import org.objectweb.asm.Type;

/**
 * Utility class for generating method descriptors in ASM format.
 */
public class Descriptor {

	private Descriptor() {
		// Prevent instantiation
	}

	public static String makeDescriptor(Class<?> target, String methodName, Class<?>... args) {
		try {
			return Type.getMethodDescriptor(target.getMethod(methodName, args));
		} catch (NoSuchMethodException e) {
			throw new ScriptCompileException(e);
		}
	}

	public static String makeDescriptor(Class<?> type, Class<?>... args) {
		int len = args.length;
		Type[] types = new Type[len];
		for (int i = 0; i < len; i++) {
			types[i] = Type.getType(args[i]);
		}
		return Type.getMethodDescriptor(Type.getType(type), types);
	}
}
