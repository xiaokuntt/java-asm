var source = """
import java.util.List;

public class Main {
    public String testList(List<String> items){
        return items.get(0) + ":" + items.size();
    }
}
""";
return source;
