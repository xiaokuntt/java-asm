# Java Script 使用文档

## 1. 项目定位

`java-script` 是一个运行在 JVM 上的脚本引擎，面向内嵌式动态表达式、业务规则和插件逻辑场景。

核心目标：

- 提供接近 JavaScript 的脚本语法（变量、条件、循环、函数、lambda、import、异常处理等）
- 与 Java 对象生态打通（类导入、模块导入、扩展方法、反射函数）
- 在运行时支持调试上下文与动态求值

## 2. 核心架构

代码主链路可以按 4 层理解：

- 解析层：`Tokenizer`、`Parser` 把脚本源码转为 AST（`Node`）
- 编译层：`ScriptCompiler` 遍历 AST 生成字节码并装载为 `ScriptRuntime`
- 执行层：`Script` 持有编译结果并在 `ScriptContext` 中执行
- 资源层：`ResourceLoader` 负责模块、函数、类、脚本语言加载

关键入口：

- `Script.create(...)`：创建可执行脚本对象
- `Script.compile()`：编译为运行时对象（含缓存机制）
- `Script.execute(ScriptContext)`：在上下文中执行脚本
- `JvmScriptEngine`：JSR223 适配实现

## 3. 快速开始

### 3.1 直接执行脚本

```java
Script script = Script.create("return a + b;", null);
ScriptContext context = new ScriptContext();
context.set("a", 1);
context.set("b", 2);
Object result = script.execute(context); // 3
```

### 3.2 使用 JSR223 接口

```java
javax.script.ScriptEngine engine = new ScriptEngineFactory().getScriptEngine();
Object result = engine.eval("return 100 + 23;");
```

### 3.3 注册默认导入变量

```java
JvmScriptEngine.addDefaultImport("appName", "java-script");
Script script = Script.create("return appName;", null);
Object result = script.execute(new ScriptContext()); // java-script
```

### 3.4 注册模块

```java
ResourceLoader.addModule("math", new Object() {
    public int add(int a, int b) { return a + b; }
});
```

脚本中可用：

```javascript
import math;
return math.add(1,2);
```

### 3.5 注册函数/扩展方法

- 通过 `JavaReflection.registerFunction(...)` 暴露全局函数
- 通过 `JavaReflection.registerMethodExtension(...)` 给类型扩展方法

可参考测试：`MethodCallTests`、`IssuesTests`

#### 3.5.1 Lambda 与绑定方法引用

新脚本优先使用 Java 风格 `->` 定义 Lambda：

```javascript
var add = (a, b) -> a + b;
var total = orders.stream()
    .map(order -> order.amount)
    .reduce(0, (left, right) -> left + right);
```

`=>` 与 `->` 完全等价并继续兼容，但文档和新增案例统一使用 `->`。

`对象::方法名` 用于创建绑定方法引用，重载在调用时根据真实参数解析：

```javascript
var getter = orderService::getOrder;
var finder = orderService::findOrder;
var parser = Integer::parseInt;

var current = getter();
var found = finder('ORDER-1001');
var number = parser('42');
```

方法引用可传给 Java 函数式接口，例如 `stream.map(orderService::getOrderId)`。当前支持 `对象::实例方法` 和 `Class::静态方法`，暂不支持 `Class::实例方法`、`Class::new`、`?::`。它会捕获当前执行上下文，建议不要跨请求或脱离结构化任务生命周期缓存。

### 3.6 命名脚本仓库与热更新

`ScriptRepository` 会在发布新版本前完成编译，失败 reload 不会替换当前可用版本：

```java
ScriptRepository repository = new ScriptRepository();
repository.reload("pricing", "return amount * 2;");

Object result = repository.execute(
    "pricing",
    new ScriptContext().set("amount", 10));

repository.reload("pricing", "return amount * 3;");
```

`reloadAll(...)` 会先编译整个批次，任一脚本失败时不会发布其中任何版本。仓库保留有界版本历史，可回滚并持久化当前活动快照：

```java
ScriptRevision old = repository.getHistory("pricing").get(1);
repository.rollback("pricing", old.getVersion());

Path snapshot = Paths.get("data/scripts.bin");
repository.save(snapshot);       // 临时文件 + 原子移动
repository.restore(snapshot);    // 全部编译成功后再批量发布
```

`addChangeListener(...)` 可监听新增、更新、回滚和删除事件。事件的 `sequence` 严格递增，即使监听器中再次触发 reload，也会保持每个观察者收到的顺序。

长期运行仓库可配置单脚本历史数、TTL 和全仓库历史总量，并使用 `purgeHistory` / `purgeAllHistory` 主动释放编译版本。快照 v2 包含 CRC 校验和、单脚本与总大小限制，并在原子移动前执行强制刷盘。

### 3.7 执行监控

```java
ScriptExecutionMetrics metrics = new ScriptExecutionMetrics();
ScriptEngineConfig config = ScriptEngineConfig.builder()
    .addExecutionListener(metrics)
    .build();

ScriptExecutionStats stats = metrics.snapshot();
```

也可以通过 `ScriptContext.addExecutionListener(...)` 只监听一次上下文。事件可区分成功、普通失败、取消、超时和检查点上限；监听器异常不会改变脚本执行结果。

生产环境可直接使用 `ScriptMonitoring.registerMBean(...)` 暴露 JMX，或把宿主已有的 Micrometer `MeterRegistry` / OpenTelemetry `Meter` 传给对应适配器。反射调用方案按签名缓存；发现不兼容 API 后适配器会自动熔断，可通过 `isDisabled()` 和 `getDroppedMeasurements()` 诊断。

### 3.8 异步执行

`ScriptAsyncExecutor` 使用有界队列执行脚本，支持取消、超时、任务指标和明确的生命周期：

```java
ScriptTaskMetrics metrics = new ScriptTaskMetrics();
try (ScriptAsyncExecutor executor = ScriptAsyncExecutor.builder()
        .corePoolSize(4)
        .maximumPoolSize(8)
        .queueCapacity(500)
        .defaultTimeout(2, TimeUnit.SECONDS)
        .addTaskListener(metrics)
        .build()) {
    ScriptTask task = executor.submit(
        repository,
        "pricing",
        () -> new ScriptContext().set("amount", 10));
    Object result = task.completion().toCompletableFuture().get();
}
```

命名脚本默认使用 `PINNED`，任务会固定提交时的 `ScriptRevision`。如需在任务真正运行时读取最新版，应显式传入 `ScriptVersionPolicy.LATEST`。使用外部线程池时通过 `maxOutstandingTasks(...)` 限制排队与运行中的任务总量；框架不会关闭外部线程池。

取消采用协作式检查点。运行任务会先进入 `CANCELLING` 或 `TIMING_OUT`，工作代码真正退出后才进入最终状态。完整契约见 `docs/ASYNC_EXECUTION_DESIGN.md`。

语言级 `async/await` 需要显式配置独立的 `ExecutorService`：

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
    .addLanguageAsyncListener(metrics)
    .build();
```

```javascript
var price = async () -> calculatePrice(order);
var stock = async inventory.load(order.productId);
return await price + await stock;
```

`async` 返回 `ScriptAsyncResult`，仍可使用历史写法 `task.get()`。取消组合结果会取消输入任务，`race` 默认取消输家。默认 Future bridge 为有界守护线程池（32 线程、1024 队列）；取消后仍不退出的任务会在期限后进入 `ABANDONED`，原 Context 在孤儿线程真正结束前禁止复用。`AUTO` 嵌套策略仅在确认有立即可用工作线程时提交，否则内联避免死锁。

## 4. 执行上下文 `ScriptContext`

常用能力：

- `set/get`：设置与读取上下文变量
- `putMapIntoContext`：批量写入变量
- `eval(String, Map<String,Object>)`：在当前上下文动态执行表达式
- `setScriptName`：设置脚本名，便于排错定位

## 5. Engine 隔离与执行限制

多租户或不同权限脚本建议使用 `ScriptEngineConfig.builder()` 创建独立 Engine 配置。配置可以隔离默认导入、模块、包、类加载器、访问策略、资源加载器，以及通过 `addFunction`、`addMethodExtension`、`addImplicitConvert` 配置的反射扩展。旧的全局写入 API仅用于兼容。

通过 `ScriptExecutionLimits` 可以设置执行超时、最大检查点数和 `maxHostCalls(...)`；仅需要外部取消时，对 `ScriptContext` 调用 `enableCancellation()`，再由控制线程调用 `cancel()`。

`ScriptAccessPolicy.builder()` 提供类前缀 allowlist/denylist 和成员规则。检查覆盖运行时类、父类/接口及最终方法或字段声明类。`ScriptExecutionEvent` 分别暴露 host access 与实际 host call 数；超过调用限制时状态为 `HOST_CALL_LIMIT`。

限制采用协作式检查点，不会强制中断正在阻塞的宿主 Java 方法。完全不可信脚本或不可控阻塞调用仍应使用独立进程。

## 6. 调试能力

`ScriptDebugContext` 继承 `ScriptContext`，支持：

- 断点列表（`breakpoints`）
- 条件断点（`ScriptBreakpoint`）
- 单步进入、越过与跳出（`stepInto` / `stepOver` / `stepOut`）
- 暂停表达式求值（`evaluate`）
- 变量回调（`setCallback`）
- 线程协作暂停/继续（`await` / `signal`）

## 7. 错误与退出机制

- 正常业务退出：`exit ...` 会转换为 `ExitValue` 返回
- 资源缺失：`ResourceLoader` 会抛 `ResourceNotFoundException`
- 编译异常：`ScriptCompileException`
- 运行异常：由 `ParseError.transfer(...)` 进行上下文包装与转换

## 8. 典型目录说明

- `src/main/java/.../parsing`：词法 + 语法
- `src/main/java/.../compile`：字节码编译
- `src/main/java/.../runtime`：运行时执行、函数调用、变量模型
- `src/main/java/.../functions`：扩展函数与动态调用
- `src/test/resources`：脚本测试样例

## 9. 建议实践

- 对重复脚本启用编译缓存：`Script.setCompileCache(...)`
- 外部可变依赖优先用模块注入，减少脚本硬编码
- 回归修复优先在 `src/test/resources/issues` 增加复现脚本
- 对新语法能力先补 grammar 测试，再补 integration 测试
- 动态规则优先通过 `ScriptRepository.reload(...)` 发布，避免自行维护并发脚本引用
- 监控监听器中只做轻量、非阻塞操作，复杂上报交给独立队列
- 异步宿主 API 与语言级 `async/await` 契约见 `docs/ASYNC_EXECUTION_DESIGN.md`
