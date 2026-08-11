var source = """
import java.util.function.Function;

public class Main {
    public void test1(String a, String b){
        System.out.println(a+b);
    }
    public String test2(String a, String b){
        return a+b;
    }
}
""";
return source;
