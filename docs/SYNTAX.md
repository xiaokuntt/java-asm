# Java Script 语法文档

## 1. 基础结构

- 语句分隔：`;`（可省略，解析器会自动吞掉连续分号）
- 代码块：`{ ... }`
- 注释：
  - 单行：`// comment`
  - 多行：`/* comment */`

## 2. 字面量

### 2.1 数值

- `byte`：`1b` / `1B`
- `short`：`1s` / `1S`
- `int`：`1`
- `long`：`1l` / `1L`
- `float`：`1f` / `1F`
- `double`：`1d` / `1D` / `1.2`
- `BigDecimal`：`1m` / `1M`
- 16 进制：`0xFF`
- 2 进制：`0b1010`
- 支持数字下划线：`1_000_000`

### 2.2 字符串

- 单引号：`'hello'`
- 双引号：`"hello"`
- 三引号多行字符串：`"""line1\nline2"""`
- 模板字符串：`` `hello ${name}` ``

### 2.3 其他字面量

- 布尔：`true` / `false`
- 空值：`null`
- 正则：`/\d+/gim`
- 列表：`[1,2,3]`
- 映射：`{a:1, b:2}`
- 动态 key：`{[key]: value}`

## 3. 变量定义

### 3.1 `var` 定义

```javascript
var a = 1;
var b;
var x = 1, y = 2, z = 3;
```

### 3.2 类型前缀定义（Java 风格）

```javascript
String name = "magic";
int count = 10;
```

### 3.3 解构赋值

```javascript
var {name, age} = user;
var [first, second] = arr;
```

## 4. 运算符

### 4.1 算术

- `+ - * / %`
- 自增自减：`++ --`（前置/后置）
- 复合赋值：`+= -= *= /= %=`

### 4.2 位运算

- `& | ^ ~ << >> >>>`
- 对应复合赋值：`&= |= ^= <<= >>= >>>=`

### 4.3 比较

- `< <= > >=`
- `== !=`
- `=== !==`
- `<>`（SQL 风格不等于）
- `instanceof`

### 4.4 逻辑

- `!`
- `&& ||`
- `and or`（SQL 风格）

### 4.5 条件表达式

```javascript
var level = score >= 60 ? "pass" : "fail";
```

## 5. 访问与调用

### 5.1 成员访问

- 普通访问：`obj.name`
- 可选链：`obj?.name`

### 5.2 下标访问

- 数组/列表/Map：`arr[0]`、`map["k"]`

### 5.3 函数与方法调用

- 函数：`fn(a, b)`
- 方法：`obj.method(a, b)`

### 5.4 绑定方法引用

```javascript
var getter = orderService::getOrder;
var finder = orderService::findOrder;
var parser = Integer::parseInt;

var order = getter();
var found = finder('ORDER-1001');
var number = parser('42');
```

- `对象::实例方法`：绑定对象，调用函数值时传入方法参数。
- `Class::静态方法`：绑定类对象，调用时动态选择静态重载。
- 方法引用可赋值、重复调用，也可适配 `Supplier`、`Function`、`BiFunction` 等 Java 函数式接口。
- 暂不支持 `Class::实例方法`、`Class::new` 和可选方法引用 `?::`。
- 方法引用捕获当前脚本执行上下文，建议仅在当前执行及其结构化子任务生命周期内使用。

## 6. 流程控制

### 6.1 `if / else if / else`

```javascript
if (a > 10) {
  return "A";
} else if (a > 5) {
  return "B";
} else {
  return "C";
}
```

### 6.2 `for`

#### C 风格 for

```javascript
for (var i = 0; i < 10; i++) {
  // ...
}
```

#### for-each 风格（当前解析器语法）

```javascript
for (var item : list) {
  // ...
}
```

### 6.3 `while` / `do...while`

```javascript
while (cond) {
  // ...
}

do {
  // ...
} while (cond)
```

### 6.4 `switch / case / default`

```javascript
switch (status) {
  case 1: { return "INIT"; }
  case 2: { return "RUNNING"; }
  default: { return "UNKNOWN"; }
}
```

### 6.5 控制语句

- `break`
- `continue`
- `return`
- `exit`（直接返回 `ExitValue`）

## 7. 异常处理

### 7.1 `try / catch / finally`

```javascript
try {
  risky();
} catch (e) {
  return e;
} finally {
  cleanup();
}
```

### 7.2 try-with-resources

```javascript
try (var r = open()) {
  use(r);
} catch (e) {
  // ...
}
```

### 7.3 抛出异常

```javascript
throw "bad request";
```

## 8. 函数与 Lambda

### 8.1 Lambda 写法

```javascript
var add = (a, b) -> a + b;
var f = (x) -> {
  return x * 2;
};
var one = x -> x + 1;
```

`->` 与 `=>` 完全等价，都会生成同一种 Lambda Token。新脚本推荐使用 Java 风格 `->`；`=>` 作为历史兼容写法保留。

### 8.2 方法引用与 Lambda 的关系

```javascript
var finder = orderService::findOrder;
// 等价意图：var finder = id -> orderService.findOrder(id);
```

方法引用在真正调用时才根据实参解析重载，并沿用普通方法调用的访问策略、扩展方法和宿主调用限制。

### 8.3 调用

```javascript
add(1, 2)
```

## 9. 导入与对象创建

### 9.1 import

```javascript
import "java.util.ArrayList" as List;
import logger;
```

支持：

- 字符串类名导入（可 `as` 别名）
- 模块导入
- 包通配符导入（如 `java.util.*`）

### 9.2 `new`

```javascript
import "java.util.Date" as Date;
var now = new Date();
```

## 10. 强制类型转换

支持 Java 风格 cast：

```javascript
var i = (int) value;
var d = (double) value;
```

## 11. assert 语句

```javascript
assert a > 0 : "a must > 0";
```

## 12. 异步表达式

宿主配置语言异步 Executor 后，可以异步执行 Lambda、函数调用或方法调用：

```javascript
var task = async () -> calculate();
var value = await task;
```

`async` 返回 `ScriptAsyncResult`，也支持兼容写法 `task.get()`。异步局部变量按提交时浅快照，父脚本结束前会等待全部子任务完成。

## 13. 关键字

当前解析器保留关键字（不可作为变量名）：

- `import`, `as`, `var`, `return`, `break`, `continue`
- `if`, `else`, `for`, `in`, `while`, `do`, `switch`, `case`, `default`
- `new`, `true`, `false`, `null`
- `try`, `catch`, `finally`, `throw`, `exit`
- `and`, `or`, `assert`, `instanceof`
- `async`, `await`

## 14. 说明

- 语法定义以 `src/main/java/cn/ykccchen/script/parsing/Parser.java` 为准。
- 示例脚本可参考 `src/test/resources/grammar`。
