/*
    switch 测试 case穿透
*/


var i = "1";
var sum = 0;
switch (i){
    case "0" : sum += 1;
    case "1" : sum += 2;
    case "2" :{
         sum += 3;
     }break;
    default:{
        sum = 100;
    }
}
return sum;