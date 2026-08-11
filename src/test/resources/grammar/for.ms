/*
    测试循环
*/
var sum = 0;
List list = new ArrayList();

for(int i= 0; i < 100; i++){
     list.add(i);
}
for(var val : list){   //包括0 包括100
    if(val > 90){
        break;  //跳出循环
    }
    if(val % 3 == 0){
        continue;   //进入下一次循环
    }
    sum = sum + val;
}

return sum;