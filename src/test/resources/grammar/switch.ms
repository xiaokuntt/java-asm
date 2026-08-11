/*
    switch 测试
*/

var i = "1";
var sum = 0;
switch (i){
    case "0" :  sum = 1;break;
    case "1" : sum = 2;break;
    case "2" :{
         sum = 3;
     }break;
    default:{
        sum = 100;
    }
}
return sum;