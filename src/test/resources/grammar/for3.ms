/*
    测试循环
*/


var sum = 0;

for(int i= 0, a=0; i < 100; i++, a+=2){
     if(i > 90){
            break;  //跳出循环
     }

    if(i % 3 == 0){
         continue;   //进入下一次循环
    }
     sum = sum + a;
}


return 5400;