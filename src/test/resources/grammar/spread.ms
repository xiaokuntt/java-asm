import java.util.ArrayList;
import java.util.stream.Collectors;

var list = [3, 2, 1]
var r = new ArrayList()
r.addAll(list)
r.addAll([9, 6])

var sum = (x, y, z) => {
    return x + y + z;
}

var mapped = [1, 2, 3].stream().map(e -> e + 3).collect(Collectors.toList())

return r == [3, 2, 1, 9, 6] && sum(1, 2, 3) == 6 && mapped == [4, 5, 6]
