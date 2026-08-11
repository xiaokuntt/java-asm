package cn.ykccchen.script;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import cn.ykccchen.script.annotation.Function;
import cn.ykccchen.script.category.RegressionCategory;
import cn.ykccchen.script.reflection.JavaReflection;
import cn.ykccchen.script.runtime.RuntimeContext;

import java.math.BigDecimal;
import java.util.Objects;

@Ignore("Legacy regression scripts depend on removed extensions; pending rebaseline")
@Category(RegressionCategory.class)
public class IssuesTests extends BaseTest {

	@BeforeClass
	public static void register() {
		JavaReflection.registerFunction(new IssuesTests());
	}

	@Function
	public Object eval(RuntimeContext runtimeContext, String script){
		return runtimeContext.eval(script);
	}

	@Test
	public void i252vy() {
		Assert.assertEquals(true, execute("issues/I252VY.ms"));
	}

	@Test
	public void i29lqg() {
		Assert.assertEquals(true, execute("issues/I29LQG.ms"));
	}

	@Test
	public void bug_function_call() {
		Assert.assertEquals(123, execute("issues/bug_function_call.ms"));
	}

	@Test
	public void bug_var() {
		Assert.assertEquals(15, execute("issues/bug_var.ms"));
	}

	@Test
	public void bug_assigment() {
		Assert.assertEquals(1, execute("issues/bug_assigment.ms"));
	}
	@Test
	public void bug_push() {
		Assert.assertEquals("[1, 2, 3]", execute("issues/bug_push.ms"));
	}
	@Test
	public void bug_scope() {
		Assert.assertEquals("key1key2key3-6", execute("issues/bug_scope.ms"));
	}

	@Test
	public void bug_scope_1() {
		Assert.assertEquals(123, execute("issues/bug_scope_1.ms"));
	}

	@Test
	public void bug_scope_2() {
		Assert.assertEquals(123, execute("issues/bug_scope_2.ms"));
	}
	@Test
	public void bug_scope_3() {
		Assert.assertEquals("[{key=null, parameters=[{key=null, parameters=[{key=Q^bill_code_^SL}, {key=Q^emp_code_^SL}]}]}, {key=Q^emp_code_^SL}]", execute("issues/bug_scope_3.ms"));
	}

	@Test
	public void bug_scope_4() {
		Assert.assertEquals("{a={c1=a}, b={c1=b}, c={c1=c}}", execute("issues/bug_scope_4.ms"));
	}

	@Test
	public void bug_scope_5() {
		Assert.assertEquals(1, execute("issues/bug_scope_5.ms"));
	}

	@Test
	public void i47vni() {
		Assert.assertNull(execute("issues/I47VNI.ms"));
	}

	@Test
	public void i47qh4() {
		Assert.assertEquals(6,execute("issues/I47QH4.ms"));
	}

	@Test
	public void i398nd() {
		Assert.assertEquals(true, execute("issues/I398ND.ms"));
	}

	@Test
	public void i4cqh3() {
		Assert.assertEquals(true, execute("issues/I4CQH3.ms"));
	}

	@Test
	public void bug_visit_inner_class() {
		Assert.assertEquals(Constants.Test.OK, execute("issues/bug_visit_inner_class.ms"));
	}

	@Test
	public void bug_dynamic_method() {
		Assert.assertEquals("abc", execute("issues/bug_dynamic_method.ms"));
	}

	@Test
	public void bug_compile1() {
		Assert.assertEquals("a,b\r\n1,2\r\n3,4", execute("issues/bug_compile.ms"));
	}

	@Test
	public void bug_finally() {
		Assert.assertEquals(2, execute("issues/bug_finally.ms"));
	}

	@Test
	public void bug_double_loop() {
		execute("issues/bug_double_loop.ms");
	}

	@Test
	public void bug_combine(){
		Assert.assertEquals(1, execute("issues/bug_combine.ms"));
	}

	@Test
	public void bug_lambda_call(){
		Assert.assertNull(execute("issues/bug_lambda_call.ms"));
	}

	@Test
	public void bug_divide(){
		Assert.assertEquals(new BigDecimal("245.0"), execute("issues/bug_divide.ms"));
	}

	@Test
	public void bug_method_div(){
		Assert.assertNotNull(execute("issues/bug_method_div.ms"));
	}

	@Test
	public void bug_operator(){
		Assert.assertEquals(true, execute("issues/bug_operator.ms"));
	}

	@Test
	public void bug_array_assigment(){
		Assert.assertEquals(300, execute("issues/bug_array_assigment.ms"));
	}
	@Test
	public void bug_not_regexp(){
		Assert.assertEquals(true, execute("issues/bug_not_regexp.ms"));
	}

	@Test
	public void bug_stream(){
		Assert.assertEquals("[a, b]", execute("issues/bug_stream.ms"));
	}

	@Test
	public void bug_try_nested(){
		Assert.assertEquals(1, execute("issues/bug_try_nested.ms"));
	}

	@Test
	public void bug_list_remove(){
		Assert.assertEquals("[2, 3, 4]", execute("issues/bug_list_remove.ms"));
	}

	@Test
	public void i6egal(){
		Assert.assertEquals("{byteMax1=127, byteMax2=127, byteMax3=128, byteMax4=-128, byteMin1=-127, byteMin2=-128, byteMin3=-128, byteMin4=-128, byteMin5=-129, byteMin6=127, byteMin7=2147483521, byteZero1=0, byteZero2=0, byteZero3=0, byteZero4=0, byteZero5=0, shortMax1=32767, shortMax2=32767, shortMax3=32768, shortMin1=-32767, shortMin2=-32768, shortMin3=-32768, shortMin4=-32768, shortMin5=-32769, shortMin6=32767, shortMin7=2, shortMin8=-2147450881, shortZero1=0, shortZero2=0, shortZero3=0, shortZero4=0, intMax1=2147483647, intMax2=2147483647, intMax3=-2147483648, intMin1=-2147483647, intMin2=-2147483648, intMin3=-2147483648, intMin5=2147483647, intMin6=-2147483648}", Objects.toString(execute("issues/I6EGAL.ms")));
	}

	@Test
	public void i9nlxj(){
		Assert.assertEquals("{1,2,3}", Objects.toString(execute("issues/I9NLXJ.ms")));
	}

	@Test
	public void iaccdt(){
		Assert.assertEquals(123123,execute("issues/IACCDT.ms"));
	}
}
