package cn.ykccchen.script.functions;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class FunctionTest {

	public static String crt(Function<String, String> a){
		return a.apply("Hello, World!");
	}

	public static void functionMap(Map<Function<String,String>, String> a){
		System.out.println(123);
		for (Function<String, String> stringStringFunction : a.keySet()) {
			System.out.println(1234);
			crt(stringStringFunction);
		}
	}

	public static void functionMapList(List<Map<Function<String,String>, String>> a){
		for (Map<Function<String, String>, String> functionStringMap : a) {
			functionMap(functionStringMap);
		}
	}

}
