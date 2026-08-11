# Java Script 使用手册

> 适用范围：当前仓库 `java-script` 1.0.0 工作区（公开 Java 包名为 `cn.ykccchen.script`）  
> 文档依据：当前源码、测试用例与 `pom.xml`；最后核对日期：2026-08-11

## 1. 项目简介

Java Script 是一个可嵌入 Java 应用的 JVM 脚本引擎。脚本会先被解析为 AST，再使用 ASM 编译成 JVM 字节码，最后在 `ScriptContext` 提供的变量环境中运行。

适合的场景包括：

- 在 Java 应用中执行动态表达式或业务规则；
- 让脚本直接调用 Java 对象、类和模块；
- 为上层平台提供条件、循环、Lambda、异常处理等脚本能力；
- 对重复执行的脚本进行编译缓存。

它不是进程级安全沙箱。宿主可以通过 `ScriptAccessPolicy` 限制类和成员访问，但对完全不可信的脚本仍建议使用独立进程隔离。

## 2. 当前版本状态

### 2.1 项目信息

| 项目 | 当前值 |
| --- | --- |
| Maven 坐标 | `cn.ykccchen:java-script:1.0.0` |
| Java 源码/目标版本 | Java 8 |
| 脚本扩展名 | `.ms` |
| JSR223 引擎名 | `Script`、`script` |
| JSR223 MIME 类型 | `application/script` |
| 当前 Java API 包名 | `cn.ykccchen.script` |
| 许可证 | MIT |



验证结果：默认回归包含 278 个测试，其中 272 个通过、6 个按设计跳过，无失败或错误。行覆盖率为 90.53%（7957/8789），分支覆盖率为 71.03%（3014/4243），最低门槛分别为 85% 和 70%。

`JvmScriptEngine` 实现 `javax.script.ScriptEngine`，名称不同，不会与 JSR223 接口产生导包冲突。

另外，以下旧能力在当前基线中已删除或暂时停用：

- 嵌入式语言代码块；
- LINQ 专用语法；
- Map 自动转 Bean 及其可变参数转换；
- `asBean` 扩展；
- 依赖已删除旧扩展的部分历史回归脚本。

## 3. 安装与引入

### 3.1 使用 Maven 坐标

当前 `pom.xml` 声明的坐标如下。能否直接从公共仓库获取取决于该版本是否已发布；本地开发可先执行 `mvn install`。

```xml
<dependency>
    <groupId>cn.ykccchen</groupId>
    <artifactId>java-script</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 3.2 从源码构建

环境要求：JDK 8 或更高版本、Maven 3.x。构建使用 `--release 8` 约束语法、字节码和可调用的 JDK API。

```bash
mvn clean test
mvn clean package
```

构建成功后，产物位于 `target/java-script-1.0.0.jar`。

## 4. 五分钟快速开始

### 4.1 执行一段脚本

```java
import cn.ykccchen.script.Script;
import cn.ykccchen.script.ScriptContext;

Script script = Script.create("return a + b;", null);

ScriptContext context = new ScriptContext();
context.set("a", 2);
context.set("b", 3);

Object result = script.execute(context);
System.out.println(result); // 5
```

执行链路为：

1. `Script.create(...)` 解析源码，并按源码文本查询全局 LRU 缓存；
2. 首次执行时将 AST 编译成 JVM 字节码；
3. `execute(...)` 为本次调用创建运行时实例并读取 `ScriptContext`；
4. `return` 的值作为 Java 返回值。

### 4.2 只执行表达式

`Script.create(true, source, engine)` 会自动在表达式前补 `return`：

```java
Script expression = Script.create(true, "price * count", null);
ScriptContext context = new ScriptContext();
context.set("price", 12);
context.set("count", 4);

Object result = expression.execute(context); // 48
```

也可以通过上下文动态求值：

```java
Map<String, Object> variables = new HashMap<>();
variables.put("value", 7);

Object result = new ScriptContext().eval("value + 5", variables); // 12
```

### 4.3 批量传入变量

```java
Map<String, Object> input = new HashMap<>();
input.put("user", user);
input.put("orders", orders);

ScriptContext context = new ScriptContext(input);
context.setScriptName("order-rule.ms");

Object result = Script.create(
    "return user.name + ':' + orders.size();",
    null
).execute(context);
```

Map、List、数组和 Java Bean 都可以直接作为上下文值。`user.name` 会依次尝试字段、`getName()` 和 `isName()`；Map 的属性访问等价于按字符串 key 取值。

上下文使用 `containsKey` 区分“变量不存在”和“变量值为 `null`”。因此显式传入的 `null` 仍会遮蔽同名默认导入或 Java 类，简单变量返回和复杂表达式的解析语义保持一致。

## 5. 使用 JSR223

项目提供了 `META-INF/services/javax.script.ScriptEngineFactory`，打包正确时可由 `ScriptEngineManager` 发现。

```java
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

ScriptEngine engine = new ScriptEngineManager().getEngineByName("script");
engine.put("a", 10);
engine.put("b", 20);

Object result = engine.eval("return a + b;"); // 30
```

也可以直接创建，避免依赖服务发现：

```java
ScriptEngine engine = new cn.ykccchen.script.ScriptEngineFactory()
    .getScriptEngine();
```

需要复用编译结果时：

```java
import javax.script.Compilable;
import javax.script.CompiledScript;

CompiledScript compiled = ((Compilable) engine)
    .compile("return price * count;");

engine.put("price", 8);
engine.put("count", 5);
Object result = compiled.eval(engine.getContext()); // 40
```

JSR223 求值时会先合并 `GLOBAL_SCOPE`，再合并 `ENGINE_SCOPE`，因此同名变量以 `ENGINE_SCOPE` 为准。

## 6. ScriptContext

`ScriptContext` 是 Java 与脚本之间的数据边界。

| 方法 | 用途 |
| --- | --- |
| `set(name, value)` | 设置一个根变量，返回当前上下文以便链式调用 |
| `get(name)` | 读取一个根变量 |
| `getString(name)` | 将变量转换为字符串；空值返回 `null` |
| `putMapIntoContext(map)` | 批量写入变量 |
| `setScriptName(name)` | 设置脚本名，便于日志和异常定位 |
| `eval(script, vars)` | 以表达式模式动态执行脚本 |
| `getRootVariables()` | 获取根变量 Map |

推荐每次执行创建独立的 `ScriptContext`。上下文及其中对象是可变的，多个线程共享同一实例可能造成变量覆盖或业务数据竞争。

## 7. 脚本语法

### 7.1 注释、语句和代码块

```javascript
// 单行注释

/*
 * 多行注释
 */

var total = 1 + 2;
if (total > 0) {
    total += 1;
}
```

分号通常可以省略，但在同一行放置多条语句、`for` 头部以及容易产生歧义的位置建议保留。

### 7.2 字面量

| 类型 | 示例 |
| --- | --- |
| `byte` | `1b`、`1B` |
| `short` | `1s`、`1S` |
| `int` | `1`、`0x10`、`0b11`、`1_000_000` |
| `long` | `1l`、`1L` |
| `float` | `1f`、`1F` |
| `double` | `1.2`、`1d`、`1D` |
| `BigDecimal` | `1m`、`1M` |
| 布尔值 | `true`、`false` |
| 空值 | `null` |
| 字符串 | `'hello'`、`"hello"` |
| 多行字符串 | `"""line 1\nline 2"""` |
| 模板字符串 | `` `hello ${name}` `` |
| 正则 | `/\d+/gim` |
| List | `[1, 2, 3]` |
| Map | `{name: 'alice', age: 20}` |

Map 支持动态 key 和属性简写：

```javascript
var key = 'level';
var name = 'alice';
var user = {
    name,
    [key]: 7,
    active: true
};
```

### 7.3 变量与解构

```javascript
var a = 1;
var b;
var x = 1, y = 2;

// Java 风格类型前缀；当前主要承担声明语法作用
String name = 'java';
int count = 10;

var {id, status} = record;
var [first, second] = values;
```

数组解构超出下标的值为 `null`。脚本变量采用词法作用域；Lambda 会捕获变量引用，因此能读到捕获变量后续更新的值。

### 7.4 运算符

| 类别 | 运算符 |
| --- | --- |
| 算术 | `+ - * / %` |
| 自增/自减 | `++ --` |
| 算术复合赋值 | `+= -= *= /= %=` |
| 比较 | `< <= > >= == != === !== <>` |
| 逻辑 | `! && || and or` |
| 位运算 | `& \| ^ ~ << >> >>>` |
| 位复合赋值 | `&= \|= ^= <<= >>= >>>=` |
| 类型判断 | `instanceof` |
| 三元表达式 | `condition ? a : b` |

`&&`、`||` 使用短路求值。条件值支持宽松真值判断；`null`、空集合、空 Map、空数组、数值 0 和 `false` 会被视为假值。

### 7.5 条件语句

```javascript
if (score >= 90) {
    return 'A';
} else if (score >= 60) {
    return 'B';
} else {
    return 'C';
}
```

### 7.6 循环

C 风格循环：

```javascript
var sum = 0;
for (var i = 0; i < 10; i++) {
    if (i == 3) {
        continue;
    }
    if (i == 8) {
        break;
    }
    sum += i;
}
```

初始化和后置部分都支持多个表达式：

```javascript
for (int i = 0, j = 10; i < j; i++, j--) {
    // ...
}
```

遍历 Iterable、Iterator、Map 值或数组时，当前语法使用冒号：

```javascript
for (var item : list) {
    // ...
}
```

其他循环：

```javascript
while (condition) {
    // ...
}

do {
    // 至少执行一次
} while (condition);
```

### 7.7 switch

```javascript
switch (status) {
    case 1: result = 'INIT'; break;
    case 2: {
        result = 'RUNNING';
    } break;
    default: {
        result = 'UNKNOWN';
    }
}
```

复杂的 `case` 内容建议使用代码块，避免当前解析器对单表达式 case 的限制。

### 7.8 Lambda 与 Java 函数式接口

```javascript
var add = (a, b) -> a + b;
var doubleValue = x -> x * 2;
var block = (x) -> {
    var result = x * 2;
    return result;
};

return add(2, 3);
```

Lambda 可自动适配 Java 函数式接口，因此能配合 Stream API：

```javascript
import java.util.stream.Collectors;

return [1, 2, 3]
    .stream()
    .map(it -> it + 10)
    .collect(Collectors.toList());
```

`->` 和 `=>` 在词法阶段会转换成同一个 Lambda Token，因此参数、代码块、闭包、返回值和 Java 函数式接口转换语义完全等价。新代码统一推荐 Java 风格 `->`；`=>` 仅作为历史兼容写法保留。

### 7.9 绑定方法引用

使用 `对象::方法名` 创建已绑定目标的方法引用：

```javascript
var getter = orderService::getOrder;
var finder = orderService::findOrder;
var formatter = orderService::formatOrder;

var current = getter();
var found = finder('ORDER-1001');
var display = formatter('ORDER-1001', 2);
```

它表达的意图与 Lambda 相同，但避免重复写目标对象：

```javascript
var finder = orderService::findOrder;
var same = id -> orderService.findOrder(id);
```

类对象可以绑定静态方法，方法引用也可以作为 Java SAM 参数：

```javascript
var parser = Integer::parseInt;
var ids = orders.stream()
    .map(orderService::getOrderId)
    .collect(Collectors.toList());
```

重载在调用时依据真实参数解析，普通方法调用已有的扩展方法、安全策略、宿主访问统计和 `maxHostCalls` 限制继续生效。当前支持 `对象::实例方法` 与 `Class::静态方法`；暂不支持 `Class::实例方法`、构造器引用 `Class::new`、可选方法引用 `?::`。

方法引用会捕获创建时的 `RuntimeContext`。推荐在当前脚本执行及其结构化异步子任务生命周期内调用；不要把它缓存为跨请求长期对象，也不要在父脚本结束后交给不受管理的线程延迟调用。

### 7.10 成员、下标和可选链

```javascript
var name = user.name;          // Map key、字段或 getter
var first = orders[0];         // List/数组下标
var code = config['code'];     // Map key
user.name = 'bob';             // Map、字段或 setter 写入

var city = user?.address?.city;
var value = service?.load();
```

可选链只在链上的目标为 `null` 时返回 `null`；普通 `.` 访问空对象会抛异常。

### 7.11 Java 类型转换

当前支持数值、布尔和字符串等内置目标类型的 Java 风格 cast：

```javascript
var number = (int) value;
var amount = (double) value;
var text = (String) value;
```

支持的名称包括 `int/Integer`、`long/Long`、`double/Double`、`float/Float`、`byte/Byte`、`short/Short`、`char/Character`、`boolean/Boolean` 和 `String`。对当前实现未识别的转换目标，值可能原样返回；不要把它当作完整的 Java 强制类型检查。

### 7.12 异常处理

```javascript
try {
    riskyOperation();
} catch (e) {
    return e.message;
} finally {
    cleanup();
}
```

支持 try-with-resources：

```javascript
try (var resource = openResource()) {
    return resource.read();
}
```

主动抛错：

```javascript
throw 'invalid input';
```

`throw` 字符串会包装为 `ScriptRuntimeException`；抛入 Throwable 对象时会以它作为 cause。

### 7.13 return、exit 与 assert

`return` 返回一个普通 Java 对象：

```javascript
return {success: true, data: [1, 2, 3]};
```

`exit` 用于携带零个或多个值立即结束整个脚本，Java 端收到 `ExitValue`：

```javascript
exit 200, 'success', [1, 2, 3];
```

```java
Object result = script.execute(context);
if (result instanceof ExitValue) {
    Object[] values = ((ExitValue) result).getValues();
}
```

`assert` 在条件为假时执行与 `exit` 相同的退出逻辑：

```javascript
assert user != null : 400, 'user is required';
```

## 8. 调用 Java 与导入资源

### 8.1 导入 Java 类

```javascript
import java.util.ArrayList;
import 'java.text.SimpleDateFormat' as Formatter;

var list = new ArrayList();
var formatter = new Formatter('yyyy-MM-dd');
```

包通配符导入：

```javascript
import java.io.*;
```

`java.lang.*` 和 `java.util.*` 已默认加入简单类名查找范围，因此通常可以直接使用 `String`、`ArrayList` 等类。

### 8.2 注册普通模块

Java 端：

```java
public class MathModule {
    public int add(int a, int b) {
        return a + b;
    }
}

ResourceLoader.addModule("math", new MathModule());
```

脚本端：

```javascript
import math;
return math.add(2, 3);
```

模块目标可以是对象实例，也可以是 `Class<?>`；注册 Class 时仅适合调用静态成员。

### 8.3 注册按上下文创建的模块

当模块实例依赖本次执行上下文时：

```java
ResourceLoader.addModule(
    "formatter",
    new DynamicModuleImport(
        java.text.SimpleDateFormat.class,
        context -> new java.text.SimpleDateFormat(
            context.getString("datePattern")
        )
    )
);
```

脚本中的 `import formatter;` 会为本次上下文解析出对应实例。

### 8.4 注册全局函数

```java
public class AppFunctions {
    @cn.ykccchen.script.annotation.Function
    public String greet(String name) {
        return "hello, " + name;
    }
}

Registration registration = JavaReflection.registerFunction(new AppFunctions());
```

脚本可直接调用：

```javascript
return greet('alice');
```

只有带 `@Function` 的 public 方法会被注册。若方法参数中声明 `RuntimeContext`，运行时会自动注入，脚本调用方不需要传该参数。

### 8.5 注册扩展方法

扩展方法的第一个参数是被扩展对象：

```java
public class StringExtensions {
    public String surround(String source, String left, String right) {
        return left + source + right;
    }
}

Registration registration = JavaReflection.registerMethodExtension(
    String.class,
    new StringExtensions()
);
```

脚本调用：

```javascript
return 'java'.surround('[', ']'); // [java]
```

### 8.6 默认导入值

```java
JvmScriptEngine.addDefaultImport("appName", "demo");
```

脚本可以直接读取 `appName`。`Script.execute(context)` 会在执行前补充默认导入；调用方已经传入的同名变量优先，不会被覆盖。全局注册表建议在应用启动阶段一次性配置。

### 8.7 自定义类加载与函数加载

高级集成入口：

- `ResourceLoader.setClassLoader(...)`：替换类名解析策略；
- `ResourceLoader.addPackage(...)`：增加简单类名搜索包；
- `ResourceLoader.addFunctionLoader(...)`：按名称动态解析函数对象；
- `JavaReflection.registerImplicitConvert(...)`：注册参数隐式转换器。

这些入口会改变进程级全局状态，建议在并发请求开始前完成注册。注册函数、扩展方法、隐式转换器、模块、包和资源加载器时都会返回幂等的 `Registration`；插件停止时调用 `close()` 即可注销对应项目。

### 8.8 配置脚本访问策略

默认策略为全放行，以保持兼容。执行外部脚本时可限制允许访问的类与成员：

```java
ScriptAccessPolicy accessPolicy = ScriptAccessPolicy.builder()
    .allowClassPrefix("java.time")
    .allowClassPrefix("com.example.scriptapi")
    .denyMember("java.lang.Object", "getClass")
    .build();
```

生产环境可直接使用拒绝优先的预设：

```java
ScriptAccessPolicy accessPolicy = ScriptAccessPolicy.productionDefaults();

// 在明确需要时按能力开放；也可以继续叠加更细的类和成员规则。
ScriptAccessPolicy filePolicy = ScriptAccessPolicy.productionBuilder()
    .allowCapability(ScriptAccessPolicy.Capability.FILE_IO)
    .build();
```

生产预设只开放核心类型、集合与时间能力，默认拒绝文件、网络、进程、`System`、`Runtime` 和异步线程池入口，并阻止 `class`、`getClass`、`wait`、`notify` 等对象继承成员。可选能力包括 `CORE_TYPES`、`COLLECTIONS`、`TIME`、`ASYNC_INTEROP`、`FILE_IO`、`NETWORK`、`PROCESS` 和 `SYSTEM`；开放能力前应评估它包含的完整宿主权限。

前缀按 Java 限定名边界匹配：`com.example` 可以匹配该包、子包和内部类，但不会误匹配 `com.examples`。拒绝访问时会抛出 `ScriptSecurityException`。属性读写除检查属性名外，还会检查最终执行的 `getX`、`isX` 或 `setX` 方法；绑定的 Java `Function` 会检查 `apply`。这是一层宿主访问控制，不替代操作系统或独立进程隔离。

### 8.9 使用 Engine 隔离配置

静态注册 API 为兼容旧代码继续保留。新服务，特别是多租户服务，建议为每个 Engine 创建不可变配置：

```java
ScriptEngineConfig config = ScriptEngineConfig.builder()
    .addDefaultImport("tenant", "tenant-a")
    .addModule("order", orderModule)
    .addPackage("com.example.scriptapi.*")
    .accessPolicy(accessPolicy)
    .compileCacheSize(500)
    .addFunctionLoader((context, name) -> loadFunction(name))
    .languageBlockingThreads(8)
    .languageBlockingQueueCapacity(1024)
    .build();

JvmScriptEngine engine = new JvmScriptEngine(
    new ScriptEngineFactory(),
    config
);
```

也可以通过带配置的 Factory 创建 JSR223 Engine：

```java
javax.script.ScriptEngine engine = new ScriptEngineFactory(config).getScriptEngine();
```

配置拥有独立的默认导入、模块、自动导包、类加载器、访问策略、函数加载器和嵌入语言加载器，不会读取其他隔离 Engine 的对应资源。未传配置的旧构造器仍读取进程级全局注册表。`JavaReflection` 注册的注解函数、扩展方法和隐式转换器目前仍属于进程级能力，建议只在应用启动阶段注册。

每个 `JvmScriptEngine` 还默认拥有独立的有界 Future bridge，用于适配普通阻塞 `Future`。默认线程数为 `min(32, max(4, CPU * 2))`，队列容量为 1024。`getFutureBridgeStats()` 返回该 Engine 的队列、活跃、完成和拒绝统计；`close()` 只关闭 Engine 自建 bridge。若使用 `languageBlockingExecutor(...)` 注入外部 executor，其生命周期仍由宿主管理，且不能再同时设置内部 bridge 的线程数或队列容量。

### 8.10 超时、检查点限制与取消

执行限制通过编译器注入的协作式检查点实现，循环即使没有循环体也能被终止：

```java
ScriptExecutionLimits limits = ScriptExecutionLimits.builder()
    .timeout(2, TimeUnit.SECONDS)
    .maxCheckpoints(1_000_000)
    .build();

ScriptEngineConfig config = ScriptEngineConfig.builder()
    .executionLimits(limits)
    .build();
```

也可以只对一次直接执行设置限制：

```java
ScriptContext context = new ScriptContext()
    .setExecutionLimits(limits);
Script.create(source, null).execute(context);
```

如果只需要外部取消而不设置数值限制，需要显式启用，以保证默认无限制脚本维持原有循环性能：

```java
ScriptContext context = new ScriptContext().enableCancellation();
Future<?> future = executor.submit(() -> script.execute(context));

// 控制线程
context.cancel();
```

终止时抛出 `ScriptExecutionException`，`getReason()` 可区分 `CANCELLED`、`TIMED_OUT` 和 `CHECKPOINT_LIMIT`。检查点会检查线程中断。该机制可以终止脚本语句和脚本循环，但不能在任意时刻强制打断一个正在阻塞的 Java 方法；阻塞调用仍应由宿主 API 自身配置网络或 I/O 超时。

## 9. 编译缓存与并发

默认情况下，`Script.create(...)` 会初始化容量为 500 的进程级有界缓存。缓存 key 包含源码、表达式模式、调试模式和脚本引擎实例。命中读取直接来自并发 Map；LRU 顺序在无锁竞争时更新，竞争时允许跳过一次顺序提升，因此淘汰策略是近似 LRU，但容量和可见性保持严格。使用 `ScriptEngineConfig` 的隔离 Engine 拥有自己的缓存，容量由 `compileCacheSize(...)` 设置。

```java
Script.setCompileCache(1000);

CompileCacheStats stats = Script.getCompileCacheStats();
double hitRate = stats.getHitRate();
long skippedPromotions = stats.getRecencyContentionCount();
Script.invalidateCompileCache(source);
Script.clearCompileCache();
```

隔离 Engine 可通过 `getCompileCacheStats()`、`invalidateCompileCache(source)` 和 `clearCompileCache()` 管理自己的缓存，不影响其他 Engine。

实践建议：

- 在应用启动阶段设置一次缓存容量；
- 相同源码可以复用同一个 `Script`，每次执行使用新的 `ScriptContext`；
- 同一缓存 key 的并发 miss 只解析一次，不同 key 可以并行解析；
- 不要在运行中频繁调用 `setCompileCache`，该方法会替换全局缓存；
- 生产代码优先使用 `ScriptEngineConfig` 的 Engine scope；旧的进程级写入 API 已标记为 deprecated，并提供 snapshot/reset/restore 供兼容测试隔离。

可通过类加载统计观察频繁编译和热更新压力：

```java
ScriptClassLoadingStats stats = Script.getClassLoadingStats();
long loaders = stats.getCreatedLoaderCount();
long metaspace = stats.getMetaspaceUsedBytes();
```

生成类不再每次创建一个 ClassLoader。默认每 128 个类形成一个 generation，隔离 Engine 可通过 `classLoaderGenerationSize(...)` 调整；可查询当前 generation、容量和已定义类数。编译缓存使用 epoch，清空或失效期间已经开始的旧编译不会重新污染缓存。

Engine 级函数和方法解析使用有界缓存，默认每个目标类最多保留 256 个签名。可按 Engine 调整容量、读取命中和淘汰统计，并在插件卸载或诊断时主动清理：

```java
ScriptEngineConfig config = ScriptEngineConfig.builder()
    .reflectionCacheSize(256)
    .build();

ScriptReflectionStats stats = config.getReflectionRegistry().stats();
long evictions = stats.getEvictionCount();
config.getReflectionRegistry().clearCaches();
```

兼容路径中的进程级方法与字段解析缓存同样限制为每个目标类 256 项，并缓存解析失败结果；注册或注销旧式扩展方法、隐式转换器时通过 epoch 自动失效，不会继续返回旧解析结果。

### 9.1 命名脚本仓库与热更新

`ScriptRepository` 用名称管理已编译脚本。每次 reload 都会先完成解析和字节码编译，再原子替换当前版本，因此并发执行只会看到完整的新版本或旧版本：

```java
ScriptRepository repository = new ScriptRepository(engine);
ScriptRevision first = repository.reload("order-price", "return amount * 0.9;");

Object value = repository.execute(
    "order-price",
    new ScriptContext().set("amount", 100));

ScriptRevision second = repository.reload("order-price", "return amount * 0.8;");
long version = second.getVersion();
```

无效源码不会替换当前版本。`reloadAll(Map<String,String>)` 在整个批次编译成功后才开始发布；`getNames()`、`getRevisions()` 提供只读快照；`addChangeListener(...)` 返回可关闭的 `Registration`。

仓库默认保留每个名称最近 20 个版本、全仓库最多 10000 个历史条目；四参数构造器还可设置 TTL。`purgeHistory`、`purgeAllHistory` 和 `remove(name, true)` 可主动释放历史。回滚会作为新的单调递增版本发布：

```java
ScriptRevision target = repository.getHistory("order-price").get(1);
repository.rollback("order-price", target.getVersion());

Path snapshot = Paths.get("data/scripts.bin");
repository.save(snapshot);
repository.restore(snapshot);
```

快照 v2 使用版本化二进制格式和 CRC 校验，限制脚本数量、单源码大小与总大小；写入时强制刷盘后再原子移动。恢复时先校验并编译整个快照，失败不会部分发布。变更事件包含严格递增 sequence 和发布时间。

事件仍按提交顺序同步交付，但监听器在仓库写锁外执行，慢监听器不会阻止其他 reload 完成原子提交。监听器运行时异常会被隔离，可通过 `getListenerFailureCount()` 观测；监听器仍应避免阻塞，尤其不要等待另一个依赖事件交付的 reload 完成。全局历史使用版本有序索引，TTL 和总量淘汰不再反复扫描全部历史。

### 9.2 执行监控 API

Engine 级监听器适合统一指标：

```java
ScriptExecutionMetrics metrics = new ScriptExecutionMetrics();
ScriptEngineConfig config = ScriptEngineConfig.builder()
    .addExecutionListener(metrics)
    .build();
```

单次上下文也可调用 `addExecutionListener(...)`。`ScriptExecutionEvent` 包含 execution ID、脚本名、开始时间、耗时、失败对象和状态。状态包括 `SUCCEEDED`、`FAILED`、`CANCELLED`、`TIMED_OUT`、`CHECKPOINT_LIMIT`。

```java
ScriptExecutionStats stats = metrics.snapshot();
long success = stats.getSucceededCount();
long averageNanos = stats.getAverageDurationNanos();
```

异步任务指标的执行平均值使用 `getCompletedStartedCount()` 作为分母，运行中、排队取消和拒绝任务不会压低平均执行时间。

监听器异常会被隔离，不会改变脚本结果。监听回调应保持轻量，网络上报建议转交独立队列。

`ScriptMonitoring` 同时实现执行和任务监听器，并可注册 JMX。传入 `JvmScriptEngine` 后，JMX 还会按 Engine 暴露 Future bridge 的线程池大小、活跃数、队列、完成数与拒绝数；无参构造器继续读取兼容的全局 bridge：

```java
ScriptMonitoring jmx = new ScriptMonitoring(engine);
MicrometerScriptMetrics micrometer = new MicrometerScriptMetrics(registry, engine);
OpenTelemetryScriptMetrics otel = OpenTelemetryScriptMetrics.fromOpenTelemetry(
    openTelemetry, "script-runtime", engine);
```

Micrometer/OpenTelemetry 适配器缓存反射调用方案，遇到永久 API 不兼容时自动熔断；bridge 指标不可用时只停用该组采样，不影响执行和任务核心指标。

### 9.3 异步执行框架

`ScriptAsyncExecutor` 为宿主提供有界异步执行能力：

```java
ScriptTaskMetrics taskMetrics = new ScriptTaskMetrics();
ScriptAsyncExecutor asyncExecutor = ScriptAsyncExecutor.builder()
    .corePoolSize(4)
    .maximumPoolSize(8)
    .queueCapacity(500)
    .defaultTimeout(2, TimeUnit.SECONDS)
    .addTaskListener(taskMetrics)
    .build();

ScriptTask task = asyncExecutor.submit(
    repository,
    "order-price",
    () -> new ScriptContext().set("amount", 100));

Object value = task.completion().toCompletableFuture().get();
asyncExecutor.close();
```

命名脚本默认采用 `PINNED` 策略，在提交时固定版本；`ScriptVersionPolicy.LATEST` 会在工作线程开始时读取最新版本。任务会记录实际脚本版本，方便审计与复现。

任务状态包含 `QUEUED`、`RUNNING`、`CANCELLING`、`TIMING_OUT` 及五种最终状态。运行中取消或超时只有在工作代码真正退出后才完成 `CompletionStage`。阻塞 Java I/O 仍必须由宿主接口配置超时。

外部 Executor/Scheduler 始终由宿主关闭。内部线程池可以通过 `shutdown()`、`shutdownNow()`、`awaitTermination(...)` 或 `close()` 管理。详细状态、异常与背压契约见 [异步执行框架](ASYNC_EXECUTION_DESIGN.md)。

### 9.4 语言级 async/await

语言 `async` 子任务必须由 Context 或 Engine 配置宿主拥有的 `ExecutorService`；普通 `Future` 的阻塞等待则默认使用每个 Engine 独立的 bridge：

```java
ScriptEngineConfig config = ScriptEngineConfig.builder()
    .languageAsyncExecutor(languagePool)
    .languageBlockingExecutor(blockingPool)
    .languageAsyncScheduler(timeoutScheduler)
    .languageAsyncPolicy(ScriptAsyncPolicy.builder()
        .maxChildTasks(64)
        .scopeJoinTimeout(3, TimeUnit.SECONDS)
        .cancellationJoinTimeout(5, TimeUnit.SECONDS)
        .failurePolicy(ScriptAsyncFailurePolicy.FAIL_FAST)
        .cancelRaceLosers(true)
        .nestedAsyncPolicy(ScriptNestedAsyncPolicy.AUTO)
        .build())
    .addLanguageAsyncListener(taskMetrics)
    .build();
```

```javascript
var price = async () -> calculatePrice(order);
var stock = async inventory.load(order.productId);
return await price + await stock;
```

```javascript
var results = await Async.all([price, stock]);
var first = await Async.race([primary, backup]);
var limited = await Async.timeout(remoteCall, 1000);
```

`async` 返回 `ScriptAsyncResult`。默认普通 Future bridge 使用 `min(32, max(4, CPU * 2))` 个工作线程和 1024 个排队位置；可按 Engine 调整，也可在 Context 上显式覆盖。取消后不退出的任务进入 `ABANDONED`，其 Context 在物理线程退出前禁止复用。

语言任务遵循结构化生命周期。嵌套调度可选择 `INLINE / EXECUTOR / AUTO`；AUTO 只在任务能立即取得工作线程时并行，否则内联防死锁。任务事件新增 `ABANDONED`，JMX 可观察 bridge 队列、拒绝和孤儿任务累计数。

当前异步调用链已可追踪，但 `ScriptDebugContext` 仍不支持在子线程上暂停和单步，检测到语言级 `async` 时会明确失败。

## 10. 错误处理与排查

执行前可进行结构化校验，不需要通过捕获异常判断语法是否合法：

```java
ScriptDiagnostics result = Script.validate(source, "rules/order.ms");
for (ScriptDiagnostic diagnostic : result.getDiagnostics()) {
    System.err.println(diagnostic.getCode() + ": " + diagnostic.getMessage());
    if (diagnostic.hasLocation()) {
        System.err.println(diagnostic.getStartLine() + ":" + diagnostic.getStartColumn());
    }
}
```

校验会完成解析和字节码编译但不会执行脚本。除 `SCRIPT_PARSE_ERROR` 与 `SCRIPT_COMPILE_ERROR` 外，运行时还提供稳定错误码，例如 `SCRIPT_TOKEN_ERROR`、`SCRIPT_RESOURCE_NOT_FOUND`、`SCRIPT_SECURITY_ERROR`、`SCRIPT_NULL_ACCESS`、`SCRIPT_OPERATOR_ERROR`、`SCRIPT_TYPE_CONVERSION_ERROR`、`SCRIPT_UNSUPPORTED_OPERATION`、`SCRIPT_ASSIGNMENT_ERROR`、`SCRIPT_METHOD_RESOLUTION_ERROR`、`SCRIPT_ASYNC_REJECTED`、`SCRIPT_CANCELLED` 和 `SCRIPT_TIMED_OUT`。`ScriptEvaluationException` 会保留脚本名、源码位置和原始 cause。

### 10.1 常见异常

| 异常 | 常见原因 |
| --- | --- |
| `ScriptCompileException` | AST 编译或字节码生成失败 |
| `ScriptEvaluationException` | 语法错误或运行错误经脚本位置包装后抛出 |
| `ResourceNotFoundException` | 类、模块、函数、脚本或脚本语言未找到，错误码为 `SCRIPT_RESOURCE_NOT_FOUND` |
| `ScriptRuntimeException` | 主动 `throw`、属性写入、空值访问、非法运算、类型转换或方法解析失败，并携带对应稳定错误码 |
| `ScriptExecutionException` | 脚本被取消、执行超时或超过检查点限制 |
| `ScriptSecurityException` | 访问策略拒绝类、构造器、方法或属性，错误码为 `SCRIPT_SECURITY_ERROR` |

`ScriptEvaluationException` 会尽量包含行列范围和源码片段。建议始终设置脚本名：

```java
ScriptContext context = new ScriptContext(input);
context.setScriptName("rules/order-discount.ms");
```

捕获示例：

```java
try {
    return script.execute(context);
} catch (ScriptEvaluationException e) {
    System.err.println(e.getSimpleMessage());
    if (e.getLine() != null) {
        System.err.println("line: " + e.getLine().getLineNumber());
    }
    throw e;
}
```

### 10.2 常见问题

“找不到模块”：确认先调用了 `ResourceLoader.addModule(name, target)`，并且模块名与 `import name;` 完全一致。

“找不到函数”：确认方法为 public、带当前包下的 `@Function`，并在执行脚本前调用 `JavaReflection.registerFunction(...)`。

“找不到方法”：检查 Java 重载参数。引擎支持装箱/拆箱、部分数值拓宽和 Lambda 到函数式接口的转换，但当前不支持已删除的 Map-to-Bean 自动转换。

“类名无法解析”：使用完整类名导入，或通过 `ResourceLoader.addPackage("com.example.*")` 添加搜索包。

“直接 `mvn test` 出现旧包测试”：包迁移期间旧 `target` 可能残留，请先运行 `mvn clean test`，确保不再加载迁移前的 class 文件。

## 11. 调试上下文

`ScriptDebugContext` 提供断点协作能力：普通与条件断点、调用帧步进、暂停表达式求值、变量回调和执行线程暂停/继续。

```java
ScriptDebugContext debug = new ScriptDebugContext(
    java.util.Arrays.asList(3, 8)
);
debug.setTimeout(60);
debug.setStepInto(false);
debug.setCallback(info -> System.out.println(info));
debug.setScriptBreakpoints(java.util.Collections.singletonList(
    ScriptBreakpoint.builder(8)
        .condition(vars -> ((Number) vars.get("total")).intValue() > 100)
        .build()
));

Script script = Script.createDebug(source, null);
Object result = script.execute(debug);
```

调试控制端使用 `await(timeout, unit)` 等待断点，使用 `resume()`、`stepInto()`、`stepOver()` 或 `stepOut()` 恢复执行；暂停时可调用 `evaluate(expression)`，并通过 `getPausedVariables()`、`getCurrentLine()`、`getFrameDepth()` 和 `getPauseReason()` 获取状态。每次暂停只接受一个恢复信号，重复调用不会穿透到下一个断点。`signal()` 继续作为底层兼容入口，旧的 `singal()` 已弃用。普通 `Script.create(...)` 不注入断点指令，因此不会承担调试检查开销。

## 12. 测试与示例位置

仓库中的可执行示例是理解当前行为的最佳补充：

- `src/test/resources/grammar`：变量、运算符、循环、Lambda、import、异常等；
- `src/test/resources/boundary`：可选链、数值边界、解构、退出等边界行为；
- `src/test/resources/functions`：Java 函数、重载、枚举和方法调用；
- `src/test/resources/stream`：Java Stream API 调用；
- `src/test/java/cn/ykccchen/script/ScriptApiTests.java`：核心 Java API 示例；
- `src/test/java/cn/ykccchen/script/functions/MethodCallTests.java`：函数和扩展注册示例。

常用验证命令：

```bash
# 标准回归验证
mvn clean test

# 独立运行性能测试
mvn -Pperformance test

# 生成 JaCoCo 报告并检查 85% 行覆盖率门槛
mvn -Pcoverage clean verify

# 静态质量门禁：依赖约束、Checkstyle、SpotBugs、依赖分析
mvn -Pquality -DskipTests verify

# 构建并运行 JMH 基准
mvn -Pbenchmark -DskipTests package
java -jar target/java-script-1.0.0-benchmarks.jar

# 对关键运行时类执行 PIT 变异测试
mvn -Pmutation verify

# 与一个已发布版本比较二进制和源码 API
mvn -Papi-compat -Dapi.baseline.version=<上一版本> verify

# 只执行一个测试类
mvn -Dtest=ScriptApiTests test

# 生成 jar
mvn clean package
```

## 13. 生产使用建议

1. 生产 Engine 优先使用 `ScriptAccessPolicy.productionDefaults()`，并只为可信脚本显式开放必要能力；完全不可信的脚本仍应使用独立进程隔离。
2. 应用启动时完成模块、函数、扩展方法、默认导入和缓存容量配置。
3. 缓存并复用 `Script`，但为每次请求创建独立 `ScriptContext`。
4. 为脚本设置稳定名称，并记录 `ScriptEvaluationException` 的简单消息、行列和 cause。
5. 对外部脚本设置业务级超时与资源限制；未绑定生产访问策略的普通 `ScriptContext` 本身不限制死循环、线程、文件或网络访问。
6. 升级或包名迁移后务必执行 `mvn clean test`，避免陈旧 class 文件制造误判。
7. 对每段生产规则保留输入样例、预期输出和边界测试，再发布到运行环境。
