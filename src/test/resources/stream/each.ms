import java.util.stream.Collectors;
var list = [{a:1}];
return list.stream()
    .map(it => {
        it.a = it.a == 1 ? 'yes' : 'no';
        return it;
    })
    .collect(Collectors.toList())
    .toString();