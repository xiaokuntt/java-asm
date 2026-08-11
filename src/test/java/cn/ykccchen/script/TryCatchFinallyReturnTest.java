package cn.ykccchen.script;

import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import cn.ykccchen.script.category.GrammarCategory;

import java.util.ArrayList;
import java.util.List;

@Category(GrammarCategory.class)
public class TryCatchFinallyReturnTest extends BaseTest {

	@Test
	public void finallyReturn() {
		Assert.assertEquals(finallyReturnJAVA().toString(), execute("grammar/try_catch_finally_return_3.ms").toString());
	}

	private static List<Object> finallyReturnJAVA() {
		int a = 1;
		StringBuilder sb = new StringBuilder();
		try {
			int b = a++ / 0;
		} catch (Exception e) {
			a++;
			sb.append("a: " + a + " " + e + ", ");
			int c = a++ / 0;
		} finally {
			sb.append("b: " + a++ + ", ");
			return returnWarp(a, sb);
		}
	}

	@Test
	public void tryNestedReturn() {
		Assert.assertEquals(tryNestedReturnJAVA().toString(), execute("grammar/try_catch_finally_return_5.ms").toString());
	}

	private static List<Object> tryNestedReturnJAVA() {
		int a = 1;
		StringBuilder sb = new StringBuilder();
		try {
			a++;
			try {
				a++;
				return returnWarp(a, sb);
			} finally {
				sb.append("a: " + a++ + ", ");
			}
		} finally {
			sb.append("b: " + a++ + ", ");
		}
	}

	@Test
	public void tryFinallyNestedReturn() {
		Assert.assertEquals(tryFinallyNestedReturnJAVA().toString(), execute("grammar/try_catch_finally_return_6.ms").toString());
	}

	private static List<Object> tryFinallyNestedReturnJAVA() {
		int a = 1;
		StringBuilder sb = new StringBuilder();
		try {
			a++;
			try {
				a++;
				try {
					a++;
					try {
						a++;
						sb.append("a: " + a++ + ", ");
						return returnWarp(a, sb);
					} finally {
						sb.append("b: " + a++ + ", ");
						return returnWarp(a, sb);
					}
				} finally {
					sb.append("c: " + a++ + ", ");
					return returnWarp(a, sb);
				}
			} finally {
				sb.append("d: " + a++ + ", ");
				return returnWarp(a, sb);
			}
		} finally {
			sb.append("e: " + a++ + ", ");
			return returnWarp(a, sb);
		}
	}

	private static List<Object> returnWarp(int a, StringBuilder sb) {
		List<Object> ret = new ArrayList<>(2);
		ret.add(a);
		ret.add(sb);
		return ret;
	}

}
