import java.util.function.Function;
import cn.ykccchen.script.functions.*;

var s = new ArrayList<>();
var f = (a) -> {
System.out.println("Hello1, " + a);
return a;
};

FunctionTest.crt(f);
System.out.println(f);
Function<String, String> f1 = (a) -> {
System.out.println("Hello1, " + a);
return a;
};
FunctionTest.crt(f1);
System.out.println(f1);

var f2 = new Function<String, String>((a) -> a + "!");
System.out.println(f2.apply("test"));
FunctionTest.crt(f2);

var map = new HashMap();
map.put(f, "Hello, world!");
FunctionTest.functionMap(map);
var list = [map];
FunctionTest.functionMapList(list);
return f.apply("12666");
