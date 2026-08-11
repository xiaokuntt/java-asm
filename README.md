# java-script

`java-script` 是一个可嵌入 Java 应用的 JVM 脚本引擎。脚本会被解析为 AST，并通过 ASM 编译为 JVM 字节码运行，适合动态表达式、业务规则、插件逻辑和低代码平台等场景。

> 当前版本：`1.0.0` · Java 8+ · 脚本扩展名：`.ms` · 许可证：MIT

## 当前版本状态

| 项目 | 当前值 |
| --- | --- |
| Maven 坐标 | `cn.ykccchen:java-script:1.0.0` |
| Java 源码与目标版本 | Java 8 |
| 脚本扩展名 | `.ms` |
| JSR223 引擎名 | `Script`、`script` |
| JSR223 MIME 类型 | `application/script` |
| Java API 包名 | `cn.ykccchen.script` |
| 编译方式 | AST + ASM JVM 字节码 |
| 许可证 | MIT |

当前基线未启用或已删除以下旧能力：嵌入式语言代码块、LINQ 专用语法、Map 自动转 Bean、`asBean` 扩展，以及依赖这些能力的历史回归脚本。Java Stream API 不受影响。

## 内容索引

- [安装](#安装)
- [五分钟快速开始](#五分钟快速开始)
- [脚本语法与语言功能](#脚本语法与语言功能)
- [Java 互操作与扩展](#java-互操作与扩展)
- [生产接入](#生产接入)
- [错误诊断](#错误诊断)
- [调试能力](#调试能力)
- [构建与验证](#构建与验证)

## 核心能力

- Java 与脚本双向调用：对象、类、模块、函数、扩展方法和函数式接口；
- 常用语言能力：变量、集合、条件、循环、Lambda、异常处理、解构、可选链和模板字符串；
- JSR223：支持 `ScriptEngineManager`、`Compilable` 和预编译脚本；
- 多 Engine 隔离：独立导入、模块、类加载器、访问策略和编译缓存；
- 生产控制：访问白名单、超时、检查点、Java 调用次数和协作式取消；
- 命名脚本仓库：热更新、版本历史、回滚、事件监听和原子快照；
- 异步执行：宿主任务框架以及语言级 `async/await`；
- 可观测性：执行事件、任务指标、JMX、Micrometer 和 OpenTelemetry；
- 工程质量：JaCoCo、Checkstyle、SpotBugs、PIT、JMH 和 API 兼容检查。

`java-script` 提供宿主访问控制，但不是进程级安全沙箱。执行完全不可信的脚本时，仍建议使用独立进程或容器隔离。

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [完整使用手册](docs/USER_MANUAL.md) | 安装、Java API、语法、生产配置、仓库、监控和排错 |
| [使用文档](docs/USAGE.md) | 常见调用方式与 API 示例 |
| [语法文档](docs/SYNTAX.md) | 脚本语法速查 |
| [异步执行框架](docs/ASYNC_EXECUTION_DESIGN.md) | 异步任务、语言异步、取消、超时和生命周期契约 |
| [测试文档](docs/TESTING.md) | 测试分类、覆盖率和质量门禁 |

## 安装

### Maven

```xml
<dependency>
    <groupId>cn.ykccchen</groupId>
    <artifactId>java-script</artifactId>
    <version>1.0.0</version>
</dependency>
```

该版本能否直接从公共仓库获取取决于发布状态。本地开发可先安装到 Maven 本地仓库：

```bash
mvn clean install
```

构建要求为 JDK 8 或更高版本、Maven 3.x。构建产物位于 `target/java-script-1.0.0.jar`。

## 五分钟快速开始

### 执行脚本

```java
import cn.ykccchen.script.Script;
import cn.ykccchen.script.ScriptContext;

Script script = Script.create("return price * count;", null);

ScriptContext context = new ScriptContext();
context.set("price", 12);
context.set("count", 4);
context.setScriptName("examples/price.ms");

Object result = script.execute(context);
System.out.println(result); // 48
```

推荐缓存并复用 `Script`，每次执行创建独立的 `ScriptContext`。不要在并发请求之间共享可变上下文。

### 执行表达式

```java
Script expression = Script.create(true, "price * count", null);

ScriptContext context = new ScriptContext()
    .set("price", 8)
    .set("count", 5);

Object result = expression.execute(context); // 40
```

### 使用 JSR223

```java
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

ScriptEngine engine = new ScriptEngineManager().getEngineByName("script");
engine.put("a", 10);
engine.put("b", 20);

Object result = engine.eval("return a + b;"); // 30
```

支持的引擎名为 `Script` 和 `script`，MIME 类型为 `application/script`。

## 脚本示例

```javascript
import java.util.stream.Collectors;

var total = orders
    .stream()
    .map(order -> order.amount)
    .reduce(0, (left, right) -> left + right);

if (user?.level == 'VIP' && total >= 1000) {
    return {
        discount: total * 0.1,
        orderIds: orders
            .stream()
            .map(order -> order.id)
            .collect(Collectors.toList())
    };
}

return {discount: 0, orderIds: []};
```

## 脚本语法与语言功能

### 注释、语句与代码块

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

分号通常可以省略；同一行存在多条语句、`for` 头部或容易产生歧义时建议保留。

### 关键字

| 关键字 | 用途 |
| --- | --- |
| `var` | 声明变量 |
| `if`、`else` | 条件分支 |
| `for`、`while`、`do` | 循环 |
| `continue`、`break` | 继续下一次循环或退出循环 |
| `switch`、`case`、`default` | 多分支匹配 |
| `return` | 返回当前脚本或 Lambda 的结果 |
| `exit`、`assert` | 携带业务值立即终止整个脚本 |
| `try`、`catch`、`finally`、`throw` | 异常处理 |
| `import`、`as` | 导入 Java 类或模块，并可设置别名 |
| `new` | 创建 Java 对象 |
| `true`、`false`、`null` | 布尔值与空值 |
| `instanceof` | 类型判断 |
| `async`、`await` | 创建和等待异步任务 |

### 字面量

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
| 正则表达式 | `/\d+/gim` |
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

### 变量、类型前缀与解构

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

数组解构超出下标的值为 `null`。变量采用词法作用域，Lambda 捕获的是变量引用，因此能读取捕获变量后续更新后的值。

### 运算符

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

`&&`、`||` 使用短路求值。条件值支持宽松真值判断：`null`、空集合、空 Map、空数组、数值 `0` 和 `false` 被视为假值。

```javascript
var label = score >= 60 ? 'passed' : 'failed';

if (user != null && user.enabled) {
    return user.name;
}
```

### 条件语句

```javascript
if (score >= 90) {
    return 'A';
} else if (score >= 60) {
    return 'B';
} else {
    return 'C';
}
```

### 循环

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

初始化和后置部分支持多个表达式：

```javascript
for (int i = 0, j = 10; i < j; i++, j--) {
    // ...
}
```

遍历 Iterable、Iterator、Map 值或数组时使用冒号：

```javascript
for (var item : list) {
    println(item);
}
```

其他循环：

```javascript
while (condition) {
    process();
}

do {
    retry();
} while (needRetry);
```

### switch

```javascript
switch (status) {
    case 1:
        result = 'INIT';
        break;
    case 2: {
        result = 'RUNNING';
    } break;
    default: {
        result = 'UNKNOWN';
    }
}
```

复杂的 `case` 内容建议使用代码块。

### Lambda 与 Java Stream

```javascript
var add = (a, b) -> a + b;
var doubleValue = x -> x * 2;
var block = x -> {
    var result = x * 2;
    return result;
};

return add(2, 3);
```

Lambda 可以适配 Java 函数式接口：

```javascript
import java.util.stream.Collectors;

return [1, 2, 3]
    .stream()
    .map(it -> it + 10)
    .collect(Collectors.toList());
```

这里调用的是 Java Stream API，不是当前已停用的 LINQ 专用语法。

`->` 与历史写法 `=>` 会被词法器归一为同一种 Lambda Token，参数、返回值、作用域和 Java 函数式接口适配语义完全相同。新代码和本文示例统一推荐更接近 Java 的 `->`；`=>` 作为向后兼容语法继续保留。

### 绑定方法引用

`对象::方法名` 会创建一个已经绑定目标对象的方法引用，调用时再按实参执行动态重载解析：

```javascript
var getOrder = orderService::getOrder;
var findOrder = orderService::findOrder;
var formatOrder = orderService::formatOrder;

var current = getOrder();
var found = findOrder('ORDER-1001');
var text = formatOrder('ORDER-1001', 2);
```

类对象可以绑定静态方法，方法引用也能直接传给 Java 函数式接口：

```javascript
var parse = Integer::parseInt;
var ids = orders.stream()
    .map(orderService::getOrderId)
    .collect(Collectors.toList());
```

第一版支持 `对象::实例方法`、`Class::静态方法`、零到多参数、运行时重载和 Java SAM 转换；暂不支持 `Class::实例方法`、`Class::new` 与 `?::`。方法引用捕获当前执行上下文，建议在本次脚本执行及其结构化子任务生命周期内使用，不要作为跨请求的长期对象缓存。

### 成员、下标与可选链

```javascript
var name = user.name;          // Map key、字段或 getter
var first = orders[0];         // List 或数组下标
var code = config['code'];     // Map key
user.name = 'bob';             // Map、字段或 setter 写入

var city = user?.address?.city;
var value = service?.load();
```

属性读取会依次尝试 Map key、字段、`getX()` 和 `isX()`。可选链只在目标为 `null` 时返回 `null`，普通 `.` 访问空对象会抛异常。

### Java 类型转换

```javascript
var number = (int) value;
var amount = (double) value;
var text = (String) value;
```

支持的名称包括 `int/Integer`、`long/Long`、`double/Double`、`float/Float`、`byte/Byte`、`short/Short`、`char/Character`、`boolean/Boolean` 和 `String`。

当前类型前缀与 cast 不应当作完整的 Java 静态类型系统使用；未识别的转换目标可能原样返回。

### 异常处理

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

抛出字符串会包装为 `ScriptRuntimeException`；抛入 `Throwable` 时会保留为 cause。

### return、exit 与 assert

`return` 返回普通 Java 对象：

```javascript
return {success: true, data: [1, 2, 3]};
```

`exit` 携带零个或多个值立即结束整个脚本，Java 端收到 `ExitValue`：

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

## Java 互操作与扩展

### 导入 Java 类与创建对象

```javascript
import java.util.ArrayList;
import 'java.text.SimpleDateFormat' as Formatter;
import java.io.*;

var list = new ArrayList();
var formatter = new Formatter('yyyy-MM-dd');
```

`java.lang.*` 和 `java.util.*` 已加入默认简单类名搜索范围。通配符导入不会绕过 `ScriptAccessPolicy`。

### 注册普通模块

Java 端：

```java
public class MathModule {
    public int add(int a, int b) {
        return a + b;
    }
}

Registration registration = ResourceLoader.addModule(
    "math",
    new MathModule()
);
```

脚本端：

```javascript
import math;
return math.add(2, 3);
```

模块可以是对象实例或 `Class<?>`；注册 Class 时主要用于调用静态成员。插件卸载时调用 `registration.close()`。

### 按上下文创建模块

```java
Registration registration = ResourceLoader.addModule(
    "formatter",
    new DynamicModuleImport(
        java.text.SimpleDateFormat.class,
        context -> new java.text.SimpleDateFormat(
            context.getString("datePattern")
        )
    )
);
```

脚本执行 `import formatter;` 时会基于当前上下文创建模块实例。

### 注册全局函数

```java
public class AppFunctions {
    @cn.ykccchen.script.annotation.Function
    public String greet(String name) {
        return "hello, " + name;
    }
}

Registration registration = JavaReflection.registerFunction(
    new AppFunctions()
);
```

```javascript
return greet('alice');
```

只有带 `@Function` 的 public 方法会注册。若参数中声明 `RuntimeContext`，运行时会自动注入，脚本无需传入。

### 注册扩展方法

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

```javascript
return 'java'.surround('[', ']'); // [java]
```

### 默认导入与动态加载

```java
JvmScriptEngine.addDefaultImport("appName", "demo");
ResourceLoader.addPackage("com.example.scriptapi.*");
ResourceLoader.setClassLoader(classLoader);
ResourceLoader.addFunctionLoader(functionLoader);
JavaReflection.registerImplicitConvert(converter);
```

脚本上下文中已显式传入的同名变量不会被默认导入覆盖。进程级注册表建议在应用启动阶段一次性配置；多租户服务优先使用隔离的 `ScriptEngineConfig`。

### 方法解析与隐式转换

调用 Java 方法时，引擎会根据运行时实参解析重载，并执行已注册的隐式转换。函数、扩展方法与转换器注册都会返回幂等的 `Registration`，卸载时可安全关闭。

方法解析失败会产生稳定错误码 `SCRIPT_METHOD_RESOLUTION_ERROR`；不要依赖反射异常文本判断业务类型。

更完整的语法边界、Java API 和执行契约见[完整使用手册](docs/USER_MANUAL.md)，可执行行为以源码和测试用例为准。

## 生产接入

### Engine 隔离与访问控制

新服务，特别是多租户服务，建议使用不可变的 `ScriptEngineConfig`：

```java
import cn.ykccchen.script.JvmScriptEngine;
import cn.ykccchen.script.ScriptAccessPolicy;
import cn.ykccchen.script.ScriptEngineConfig;
import cn.ykccchen.script.ScriptEngineFactory;

ScriptAccessPolicy policy = ScriptAccessPolicy.productionBuilder()
    .allowClassPrefix("java.time")
    .allowClassPrefix("com.example.scriptapi")
    .build();

ScriptEngineConfig config = ScriptEngineConfig.builder()
    .addDefaultImport("tenant", "tenant-a")
    .addModule("order", orderModule)
    .accessPolicy(policy)
    .compileCacheSize(500)
    .build();

JvmScriptEngine engine = new JvmScriptEngine(
    new ScriptEngineFactory(),
    config
);
```

生产预设默认拒绝文件、网络、进程、`System`、`Runtime` 和异步线程池等高风险入口。只应为可信脚本开放必要能力。

### 执行限制与取消

```java
ScriptContext context = new ScriptContext(input);
context.setScriptName("rules/order-discount.ms");
context.setExecutionLimits(ScriptExecutionLimits.builder()
    .timeout(200, TimeUnit.MILLISECONDS)
    .maxCheckpoints(100_000)
    .maxHostCalls(50)
    .build());

Object result = script.execute(context);
```

限制是协作式的。阻塞 Java I/O 必须由宿主接口自身配置超时。

### 命名脚本与热更新

```java
ScriptRepository repository = new ScriptRepository();

ScriptRevision stable = repository.reload(
    "order-price",
    "return amount * 0.9;"
);
repository.reload("order-price", "return amount * 0.8;");

ScriptRevision revision = repository.get("order-price");

Object result = revision.getScript().execute(
    new ScriptContext().set("amount", 100)
);

repository.rollback("order-price", stable.getVersion());
repository.save(Paths.get("data/scripts.bin"));
```

批量更新使用 `reloadAll(...)`：只有全部脚本编译成功后才会发布，避免部分生效。

仓库还支持：

- `reload(name, expectedVersion, source)`：乐观锁更新；
- `getHistory(name)`：读取不可变历史版本；
- `remove(name, true)`：删除脚本并清理历史；
- `purgeHistory(...)`、`purgeAllHistory()`：主动释放历史；
- `addChangeListener(...)`：监听严格递增的变更事件；
- `save(path)`、`restore(path)`：带版本和 CRC 校验的原子快照。

仓库默认保留每个名称最近 20 个版本，全仓库最多 10000 个历史条目。回滚会作为新的单调递增版本发布，不会重用旧版本号。

### 编译缓存与并发

`Script.create(...)` 默认按源码文本使用全局 LRU 缓存。多租户或长时间运行服务建议使用 Engine 隔离缓存：

```java
ScriptEngineConfig config = ScriptEngineConfig.builder()
    .compileCacheSize(500)
    .build();

JvmScriptEngine engine = new JvmScriptEngine(
    new ScriptEngineFactory(),
    config
);

CompileCacheStats stats = engine.getCompileCacheStats();
```

缓存提供命中、未命中、淘汰、epoch 和过期编译丢弃统计。清空或失效后，之前开始的并发编译结果不会重新填回已失效缓存。

编译后的 `Script` 可以跨线程复用；`ScriptContext` 及其变量不可在并发请求间共享。脚本类加载器按 Engine 分代复用，并可通过 `ScriptClassLoadingStats` 查看实例数、字节码量和 Metaspace 快照。

### 宿主异步任务框架

`ScriptAsyncExecutor` 提供有界线程池、队列背压、任务超时、版本固定、取消和任务指标：

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
    () -> new ScriptContext().set("amount", 100)
);

Object value = task.completion().toCompletableFuture().get();
asyncExecutor.close();
```

命名脚本默认使用 `PINNED` 策略，在提交时固定版本；`LATEST` 会在工作线程开始时读取最新版本。任务会记录实际版本，便于审计与复现。

任务状态包含 `QUEUED`、`RUNNING`、`CANCELLING`、`TIMING_OUT` 以及最终成功、失败、取消、超时或拒绝状态。运行中取消或超时只有在工作代码真正退出后才完成 `CompletionStage`。

### 语言级 async/await

语言级 `async/await` 必须由宿主显式配置并管理 `ExecutorService`：

```java
ScriptEngineConfig config = ScriptEngineConfig.builder()
    .languageAsyncExecutor(languagePool)
    .languageBlockingExecutor(blockingPool)
    .languageAsyncScheduler(timeoutScheduler)
    .build();
```

```javascript
var price = async () -> calculatePrice(order);
var stock = async inventory.load(order.productId);

return await price + await stock;
```

并行组合支持 `Async.all(...)`、`Async.race(...)` 和 `Async.timeout(...)`。线程池与调度器的关闭责任归宿主所有，完整契约见[异步执行框架](docs/ASYNC_EXECUTION_DESIGN.md)。

`async` 返回 `ScriptAsyncResult`。语言任务遵循结构化生命周期；嵌套调度支持 `INLINE`、`EXECUTOR` 和 `AUTO`。默认 `AUTO` 只在任务可以立即取得线程时并行，否则内联执行以避免线程池死锁。

取消后在期限内仍未退出的任务进入 `ABANDONED`，对应 Context 在线程真正退出前不得复用。当前异步调用链可追踪，但调试器不支持在异步子线程上暂停和单步。

### 监控

```java
ScriptExecutionMetrics metrics = new ScriptExecutionMetrics();

ScriptEngineConfig config = ScriptEngineConfig.builder()
    .addExecutionListener(metrics)
    .build();

ScriptExecutionStats stats = metrics.snapshot();
long succeeded = stats.getSucceededCount();
```

执行事件包含 execution ID、脚本名、开始时间、耗时、失败对象和状态。执行状态包括 `SUCCEEDED`、`FAILED`、`CANCELLED`、`TIMED_OUT` 和 `CHECKPOINT_LIMIT`。

Engine 级监听器适合统一指标，单次 `ScriptContext` 也可以通过 `addExecutionListener(...)` 增加监听器。监听器异常会被隔离，不会改变脚本执行结果；回调应保持轻量，远程上报应转交独立队列。

生产环境还可接入：

```java
ScriptMonitoring jmx = new ScriptMonitoring(engine);

MicrometerScriptMetrics micrometer = new MicrometerScriptMetrics(
    registry,
    engine
);

OpenTelemetryScriptMetrics otel =
    OpenTelemetryScriptMetrics.fromOpenTelemetry(
        openTelemetry,
        "script-runtime",
        engine
    );
```

JMX 会按 Engine 暴露执行统计、异步任务统计和 Future bridge 的线程、活跃数、队列、完成数与拒绝数。

## 错误诊断

执行前可以完成解析和字节码编译校验，而不运行脚本：

```java
ScriptDiagnostics diagnostics = Script.validate(
    source,
    "rules/order-discount.ms"
);

for (ScriptDiagnostic diagnostic : diagnostics.getDiagnostics()) {
    System.err.println(
        diagnostic.getCode() + ": " + diagnostic.getMessage()
    );
}
```

运行异常会尽量保留脚本名、错误码、源码行列和原始原因。建议每次执行都设置稳定的脚本名。

### 稳定错误码

| 错误码 | 含义 |
| --- | --- |
| `SCRIPT_TOKEN_ERROR` | 词法或 Token 解析失败 |
| `SCRIPT_PARSE_ERROR` | 脚本语法解析失败 |
| `SCRIPT_COMPILE_ERROR` | AST 或字节码编译失败 |
| `SCRIPT_RESOURCE_NOT_FOUND` | 类、模块、函数或脚本资源不存在 |
| `SCRIPT_SECURITY_ERROR` | 访问策略拒绝宿主类或成员 |
| `SCRIPT_NULL_ACCESS` | 对空对象执行普通属性或方法访问 |
| `SCRIPT_OPERATOR_ERROR` | 运算符不支持当前操作数 |
| `SCRIPT_TYPE_CONVERSION_ERROR` | 类型转换失败 |
| `SCRIPT_UNSUPPORTED_OPERATION` | 当前运行时不支持该操作 |
| `SCRIPT_ASSIGNMENT_ERROR` | 属性、下标或变量赋值失败 |
| `SCRIPT_METHOD_RESOLUTION_ERROR` | 找不到匹配的 Java 方法重载 |
| `SCRIPT_ASYNC_REJECTED` | 异步线程池或队列拒绝任务 |
| `SCRIPT_CANCELLED` | 脚本被取消 |
| `SCRIPT_TIMED_OUT` | 脚本执行超时 |

### 常见异常

| 异常 | 常见原因 |
| --- | --- |
| `ScriptCompileException` | AST 编译或字节码生成失败 |
| `ScriptEvaluationException` | 语法或运行错误经脚本位置包装后抛出 |
| `ResourceNotFoundException` | 类、模块、函数、脚本或语言资源不存在 |
| `ScriptRuntimeException` | 主动 `throw`、非法运算、空值访问、类型转换或方法解析失败 |
| `ScriptExecutionException` | 取消、超时或超过检查点限制 |
| `ScriptSecurityException` | 访问策略拒绝类、构造器、方法或属性 |
| `ScriptAsyncRejectedException` | 异步执行资源已满或执行器已关闭 |

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

### 常见问题

- 找不到模块：确认执行前调用了 `ResourceLoader.addModule(...)`，并且名称与 `import name;` 完全一致；
- 找不到函数：确认方法为 public、带当前包下的 `@Function`，并已调用 `JavaReflection.registerFunction(...)`；
- 找不到方法：检查 Java 重载参数；当前不支持已删除的 Map-to-Bean 自动转换；
- 类名无法解析：使用完整类名导入，或通过 `ResourceLoader.addPackage(...)` 增加搜索包；
- JSR223 返回空 Engine：确认构建产物包含 `META-INF/services/javax.script.ScriptEngineFactory`；
- 迁移后仍执行旧类：删除旧 `target`，执行 `mvn clean test`；
- 异步任务不退出：检查宿主 I/O 超时、取消响应和外部 Executor 生命周期。

## 调试能力

`ScriptDebugContext` 支持普通断点、条件断点、表达式求值、变量快照、step-into、step-over 和 step-out：

```java
ScriptDebugContext debug = new ScriptDebugContext(
    java.util.Arrays.asList(3, 8)
);
debug.setTimeout(60);
debug.setStepInto(false);
debug.setCallback(info -> System.out.println(info));
debug.setScriptBreakpoints(java.util.Collections.singletonList(
    ScriptBreakpoint.builder(8)
        .condition(vars ->
            ((Number) vars.get("total")).intValue() > 100)
        .build()
));

Script script = Script.createDebug(source, null);
Object result = script.execute(debug);
```

调试控制端可以：

- 使用 `await(timeout, unit)` 等待断点；
- 使用 `resume()`、`stepInto()`、`stepOver()` 或 `stepOut()` 恢复；
- 暂停时调用 `evaluate(expression)`；
- 通过 `getPausedVariables()`、`getCurrentLine()`、`getFrameDepth()` 和 `getPauseReason()` 获取状态。

普通 `Script.create(...)` 不注入断点指令，因此没有调试检查开销。当前语言级 `async` 不支持在子线程暂停和单步。

## 构建与验证

可执行示例和测试位置：

- `src/test/resources/grammar`：变量、运算符、循环、Lambda、import 和异常；
- `src/test/resources/boundary`：可选链、数值、解构和退出等边界；
- `src/test/resources/functions`：Java 函数、重载、枚举和方法调用；
- `src/test/resources/stream`：Java Stream API；
- `ScriptApiTests`：核心 Java API；
- `ScriptRepositoryTests`：命名脚本、热更新、历史和快照；
- `ScriptAsyncExecutorTests`、`LanguageAsyncTests`：两类异步执行；
- `ScriptExecutionMonitoringTests`、`MonitoringAdapterTests`：监控与适配器。

```bash
# 默认回归测试
mvn clean test

# 覆盖率报告与门槛检查
mvn -Pcoverage clean verify

# Checkstyle、SpotBugs、依赖约束和依赖分析
mvn -Pquality -DskipTests verify

# 性能测试
mvn -Pperformance test

# JMH 基准
mvn -Pbenchmark -DskipTests package
java -jar target/java-script-1.0.0-benchmarks.jar

# PIT 变异测试
mvn -Pmutation verify

# 与已发布版本比较二进制和源码 API
mvn -Papi-compat -Dapi.baseline.version=<上一版本> verify

# 只运行一个测试类
mvn -Dtest=ScriptApiTests test

# 生成 jar
mvn clean package
```

当前基线包含 278 个默认回归测试：272 个通过，6 个按设计跳过。JaCoCo 行覆盖率为 90.53%（7957/8789，门槛 85%），分支覆盖率为 71.03%（3014/4243，门槛 70%）。

## 生产检查清单

- 使用 `ScriptAccessPolicy.productionDefaults()` 或更严格的访问策略；
- 应用启动阶段完成模块、函数、扩展方法和默认导入注册；
- 为每次请求创建独立 `ScriptContext`，为脚本设置稳定名称；
- 对外部脚本设置超时、检查点和宿主调用次数限制；
- 宿主 Java I/O、数据库和远程调用必须设置自身超时；
- 配置有界缓存、异步线程池、队列容量、背压与关闭流程；
- 记录版本、输入摘要、执行结果、稳定错误码和耗时指标；
- 发布前保留输入样例、预期输出、边界测试和回滚版本；
- 完全不可信的脚本使用独立进程或容器隔离。


## License

[MIT](LICENSE)
