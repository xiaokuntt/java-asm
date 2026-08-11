package cn.ykccchen.script.reflection;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class MethodInvoker extends JavaInvoker<Method> {

	public MethodInvoker(Method method) {
		super(resolveAccessibleMethod(method));
	}

	public MethodInvoker(MethodInvoker invoker){
		super(invoker);
	}

	public MethodInvoker(Method method, Object defaultTarget) {
		super(resolveAccessibleMethod(method));
		setDefaultTarget(defaultTarget);
	}

	/**
	 * A public method declared by a package-private JDK implementation class is
	 * not invocable under strong module encapsulation. Prefer the equivalent
	 * declaration from a public interface or superclass when one exists.
	 */
	private static Method resolveAccessibleMethod(Method method) {
		if (Modifier.isPublic(method.getModifiers())
				&& Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
			return method;
		}
		Method resolved = findPublicDeclaration(method.getDeclaringClass(), method.getName(), method.getParameterTypes());
		return resolved == null ? method : resolved;
	}

	private static Method findPublicDeclaration(Class<?> type, String name, Class<?>[] parameterTypes) {
		if (type == null) {
			return null;
		}
		for (Class<?> interfaceType : type.getInterfaces()) {
			Method method = findPublicDeclaration(interfaceType, name, parameterTypes);
			if (method != null) {
				return method;
			}
		}
		if (Modifier.isPublic(type.getModifiers())) {
			try {
				Method method = type.getMethod(name, parameterTypes);
				if (Modifier.isPublic(method.getModifiers())
						&& Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
					return method;
				}
			} catch (NoSuchMethodException ignored) {
				// Continue with the superclass hierarchy.
			}
		}
		return findPublicDeclaration(type.getSuperclass(), name, parameterTypes);
	}

	@Override
	public MethodInvoker copy() {
		return new MethodInvoker(this);
	}

	@Override
	Object invoke(Object target, Object... args) throws InvocationTargetException, IllegalAccessException {
		Object defaultTarget = getDefaultTarget();
		return getExecutable().invoke(defaultTarget == null ? target : defaultTarget, args);
	}
}
