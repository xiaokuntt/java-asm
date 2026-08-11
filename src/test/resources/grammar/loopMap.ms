/*
    测试循环Map
*/
var k = 'key4';
var k5 = 5;
var map = {
    key1 : 1,
    key2 : 2,
    key3 : 3,
    [k]: 4,
    k5
};
var sum = 0;
var keys = '';
for(var entry : map.entrySet()){
    sum = sum + entry.value;
    keys = keys + entry.key
}
return keys + '-' + sum;