import java.util.stream.Collectors;
var list = [1,2,3,1,2,5,6];
return list.stream().distinct().collect(Collectors.toList()).toString();
