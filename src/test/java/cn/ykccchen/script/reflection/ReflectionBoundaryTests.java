package cn.ykccchen.script.reflection;

import cn.ykccchen.script.Registration;
import cn.ykccchen.script.convert.ClassImplicitConvert;
import cn.ykccchen.script.runtime.Variables;
import org.junit.Assert;
import org.junit.Test;

import java.beans.Transient;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.function.Function;

public class ReflectionBoundaryTests {

	public static class ParentFixture {
		private int inherited = 3;
	}

	public static final class ChildFixture extends ParentFixture {
		public String visible;
	}

	public static final class MethodFixture {
		@Transient
		public String hidden() { return "hidden"; }
		public String value() { return "value"; }
		public String number(float value) { return String.valueOf(value); }
		public String nullable(String value) { return value; }
		public String runnable(Runnable value) { return String.valueOf(value); }
	}

	public static final class ConstructorFixture {
		public ConstructorFixture(String value, Object... rest) { }
	}

	public static final class ArrayExtensions {
		public int arrayLength(Object[] values) { return values.length; }
		public String sequence(CharSequence value) { return value.toString(); }
	}

	public static final class InnerFixture {
		public static final class Nested { }
	}

	@Test
	public void reflectionShouldCoverApplyFilteringCoercionAndMetadataBoundaries() {
		Function<Object, Object> function = value -> value;
		Assert.assertNotNull(JavaReflection.getMethod(function, null));
		Assert.assertNull(JavaReflection.findInvoker(MethodFixture.class, "hidden", new Class<?>[0]));
		Assert.assertNotNull(JavaReflection.findInvoker(MethodFixture.class, "value"));
		Assert.assertNotNull(JavaReflection.findInvoker(
				MethodFixture.class, "number", new Class<?>[]{Integer.class}));
		Assert.assertNotNull(JavaReflection.findInvoker(
				MethodFixture.class, "nullable", new Class<?>[]{JavaReflection.Null.class}));

		Assert.assertTrue(JavaReflection.isPrimitiveAssignableFrom(Float.class, float.class));
		Assert.assertTrue(JavaReflection.isPrimitiveAssignableFrom(Byte.class, byte.class));
		Assert.assertTrue(JavaReflection.isPrimitiveAssignableFrom(Short.class, short.class));
		Assert.assertTrue(JavaReflection.isPrimitiveAssignableFrom(Long.class, long.class));
		Assert.assertTrue(JavaReflection.isPrimitiveAssignableFrom(Character.class, char.class));
		Assert.assertFalse(JavaReflection.isPrimitiveAssignableFrom(String.class, int.class));
		Assert.assertArrayEquals(new String[]{"null", "Integer"},
				JavaReflection.getStringTypes(new Object[]{null, 1}));
		Assert.assertEquals(0, JavaReflection.getStringTypes(null).length);
		Assert.assertEquals(InnerFixture.Nested.class,
				JavaReflection.getInnerClass(InnerFixture.class, "nested"));
		Assert.assertNull(JavaReflection.getInnerClass(InnerFixture.class, "missing"));
	}

	@Test
	public void reflectionShouldCoverInheritedFieldsAndExtensionHierarchy() throws Throwable {
		ChildFixture child = new ChildFixture();
		Field inherited = JavaReflection.getField(child, "inherited");
		Assert.assertNotNull(inherited);
		Assert.assertEquals(3, JavaReflection.getFieldValue(child, inherited));
		JavaReflection.setFieldValue(child, inherited, 4);
		Assert.assertEquals(4, JavaReflection.getFieldValue(child, inherited));
		Assert.assertNull(JavaReflection.getField(child, "missing"));
		try {
			JavaReflection.getFieldValue("wrong", inherited);
			Assert.fail("Expected field read failure");
		} catch (RuntimeException expected) {
			Assert.assertNotNull(expected.getCause());
		}
		try {
			JavaReflection.setFieldValue("wrong", inherited, 1);
			Assert.fail("Expected field write failure");
		} catch (RuntimeException expected) {
			Assert.assertNotNull(expected.getCause());
		}

		Registration arrays = JavaReflection.registerMethodExtension(
				Object[].class, new ArrayExtensions());
		Registration sequences = JavaReflection.registerMethodExtension(
				CharSequence.class, new ArrayExtensions());
		try {
			JavaInvoker<Method> array = JavaReflection.getExtensionMethod(
					new String[]{"a", "b"}, "arrayLength");
			Assert.assertNotNull(array);
			array.setExtension(true);
			Assert.assertEquals(2, array.invoke0(new String[]{"a", "b"}, null, new Object[0]));
			Assert.assertNotNull(JavaReflection.getExtensionMethod("text", "sequence"));
		} finally {
			sequences.close();
			arrays.close();
		}
	}

	@Test
	public void constructorResolutionShouldCoverVarargsAndNullRejection() {
		Constructor<?> constructor = ConstructorFixture.class.getConstructors()[0];
		Assert.assertNotNull(JavaReflection.findConstructorInvoker(
				Collections.singletonList(constructor),
				new Class<?>[]{String.class, Integer.class, Integer.class}));
		Assert.assertNotNull(JavaReflection.findConstructorInvoker(
				Collections.singletonList(constructor),
				new Class<?>[]{String.class, JavaReflection.Null.class}));
		Assert.assertNull(JavaReflection.findConstructorInvoker(
				Collections.singletonList(constructor),
				new Class<?>[]{Integer.class}));
	}

	@Test
	@SuppressWarnings("deprecation")
	public void legacyReflectionCachesShouldBeBoundedAndResolutionErrorsShouldPropagate() {
		JavaReflection.clearLegacyResolutionCaches(MethodFixture.class);
		MethodFixture fixture = new MethodFixture();
		Assert.assertNull(JavaReflection.getMethod(fixture, "missing0"));
		Assert.assertEquals(1, JavaReflection.getLegacyMethodCacheSize(MethodFixture.class));
		for (int index = 1; index < 400; index++) {
			Assert.assertNull(JavaReflection.getMethod(fixture, "missing" + index));
		}
		Assert.assertTrue(JavaReflection.getLegacyMethodCacheSize(MethodFixture.class) <= 256);
		Assert.assertNull(JavaReflection.getMethod("value", "sequence"));
		Registration extension = JavaReflection.registerMethodExtension(
				CharSequence.class, new ArrayExtensions());
		try {
			Assert.assertNotNull(JavaReflection.getMethod("value", "sequence"));
		} finally {
			extension.close();
		}
		Assert.assertNull(JavaReflection.getMethod("value", "sequence"));

		JavaReflection.clearLegacyResolutionCaches(ChildFixture.class);
		ChildFixture child = new ChildFixture();
		Assert.assertNull(JavaReflection.getField(child, "missing0"));
		Assert.assertEquals(1, JavaReflection.getLegacyFieldCacheSize(ChildFixture.class));
		for (int index = 1; index < 400; index++) {
			Assert.assertNull(JavaReflection.getField(child, "missing" + index));
		}
		Assert.assertTrue(JavaReflection.getLegacyFieldCacheSize(ChildFixture.class) <= 256);

		Registration registration = JavaReflection.registerImplicitConvert(new ClassImplicitConvert() {
			@Override
			public boolean support(Class<?> from, Class<?> to) {
				throw new AssertionError("resolution failure must propagate");
			}

			@Override
			public Object convert(Variables variables, Object source, Class<?> target) {
				return source;
			}
		});
		try {
			JavaReflection.getMethod(fixture, "runnable", new Object());
			Assert.fail("Expected resolver error");
		} catch (AssertionError expected) {
			Assert.assertEquals("resolution failure must propagate", expected.getMessage());
		} finally {
			registration.close();
		}
	}
}
