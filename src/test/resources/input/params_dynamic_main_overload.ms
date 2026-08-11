var source = """
public class Main {
    public String test2(String a){
        return "one-" + a;
    }
    public String test2(String a, String b){
        return "two-" + a + b;
    }
}
""";
return source;
