package cn.ykccchen.script;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class MonitoringReflection {
	private static final Class<?> NULL_ARGUMENT = NullArgument.class;
	private static final ClassValue<ConcurrentMap<Signature, Optional<Method>>> METHODS =
			new ClassValue<ConcurrentMap<Signature, Optional<Method>>>() {
				@Override protected ConcurrentMap<Signature, Optional<Method>> computeValue(Class<?> type) {
					return new ConcurrentHashMap<>();
				}
			};
	private MonitoringReflection() { }

	static Object invoke(Object target, String name, Object... arguments) throws ReflectiveOperationException {
		Class<?> type = target.getClass();
		Signature signature = new Signature(name, arguments);
		Optional<Method> cached = METHODS.get(type).get(signature);
		if (cached == null) {
			cached = Optional.ofNullable(findMethod(type, name, arguments));
			Optional<Method> raced = METHODS.get(type).putIfAbsent(signature, cached);
			if (raced != null) cached = raced;
		}
		if (!cached.isPresent()) throw new NoSuchMethodException(type.getName() + "." + name);
		Method method = cached.get();
		try {
			return method.invoke(target, arguments);
		} catch (InvocationTargetException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof RuntimeException) throw (RuntimeException) cause;
			throw exception;
		}
	}

	private static Method findMethod(Class<?> type, String name, Object[] arguments) {
		for (Method method : type.getMethods()) {
			if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
			Class<?>[] types = method.getParameterTypes();
			boolean compatible = true;
			for (int index = 0; index < types.length; index++) {
				if (arguments[index] != null && !wrap(types[index]).isInstance(arguments[index])) {
					compatible = false;
					break;
				}
			}
			if (!compatible) continue;
			return method;
		}
		return null;
	}

	private static Class<?> wrap(Class<?> type) {
		if (!type.isPrimitive()) return type;
		if (type == long.class) return Long.class;
		if (type == int.class) return Integer.class;
		if (type == boolean.class) return Boolean.class;
		if (type == double.class) return Double.class;
		if (type == float.class) return Float.class;
		if (type == short.class) return Short.class;
		if (type == byte.class) return Byte.class;
		if (type == char.class) return Character.class;
		return type;
	}

	private static final class Signature {
		private final String name;
		private final Class<?>[] argumentTypes;
		private final int hashCode;
		private Signature(String name, Object[] arguments) {
			this.name = name;
			this.argumentTypes = new Class<?>[arguments.length];
			for (int index = 0; index < arguments.length; index++) {
				argumentTypes[index] = arguments[index] == null ? NULL_ARGUMENT : arguments[index].getClass();
			}
			this.hashCode = 31 * name.hashCode() + Arrays.hashCode(argumentTypes);
		}
		@Override public int hashCode() { return hashCode; }
		@Override public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof Signature)) return false;
			Signature other = (Signature) object;
			return name.equals(other.name) && Arrays.equals(argumentTypes, other.argumentTypes);
		}
	}

	private static final class NullArgument { }
}
