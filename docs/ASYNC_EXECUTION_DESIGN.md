# 异步执行框架

## 1. 当前状态与边界

宿主 Java API 与语言级 `async/await` 均已实现。框架负责有界提交、状态、超时、取消、结果交付、脚本版本选择和监控集成；语言子任务使用宿主显式提供的 `ExecutorService`。

当前能力：

- 基于 Java 8 `CompletionStage` 提交 `Script` 或 `ScriptRepository` 中的命名脚本；
- 每个运行中任务独占一个 `ScriptContext`，检测并拒绝并发复用；
- 支持协作式取消、执行超时、有界背压和明确的完成异常；
- 命名脚本默认固定提交时版本，也可选择执行时最新版；
- 内部线程池由框架关闭，外部 Executor/Scheduler 始终由宿主拥有；
- 任务事件与 `ScriptExecutionEvent` 通过 task ID 关联。
- 支持 `async` 提交函数、方法或 Lambda，并通过 `await` 或兼容的 `.get()` 获取结果；
- 语言子任务采用结构化生命周期，父脚本结束前等待全部子任务退出。

非目标：

- 不承诺强制终止正在阻塞的宿主 Java I/O；
- 暂不提供异步子任务对父作用域变量的并发写回；
- 不创建 JVM 进程级安全沙箱。

## 2. API 与基本用法

```java
ScriptTaskMetrics metrics = new ScriptTaskMetrics();
ScriptAsyncExecutor executor = ScriptAsyncExecutor.builder()
    .corePoolSize(4)
    .maximumPoolSize(8)
    .queueCapacity(500)
    .defaultTimeout(2, TimeUnit.SECONDS)
    .addTaskListener(metrics)
    .build();

ScriptTask task = executor.submit(
    Script.create("return amount * 2;", null),
    () -> new ScriptContext().set("amount", 10));

CompletionStage<Object> result = task.completion();
```

命名脚本默认使用 `PINNED`：

```java
ScriptTask pinned = executor.submit(repository, "pricing", ScriptContext::new);

ScriptTask latest = executor.submit(
    repository,
    "pricing",
    ScriptVersionPolicy.LATEST,
    ScriptContext::new);
```

`PINNED` 在提交时保存 `ScriptRevision`，可复现且不受排队期间 reload 影响。`LATEST` 在工作线程开始时解析当前版本。`ScriptTask.getScriptVersion()` 返回实际执行版本。

### 2.1 语言级 async/await

语言线程池必须由 Context 或 Engine 显式提供：

```java
ExecutorService languagePool = new ThreadPoolExecutor(
    4, 8, 60, TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(500),
    new ThreadPoolExecutor.AbortPolicy());

ScriptEngineConfig config = ScriptEngineConfig.builder()
    .languageAsyncExecutor(languagePool)
    .languageBlockingExecutor(blockingPool)
    .languageAsyncPolicy(ScriptAsyncPolicy.builder()
        .cancellationJoinTimeout(5, TimeUnit.SECONDS)
        .nestedAsyncPolicy(ScriptNestedAsyncPolicy.AUTO)
        .build())
    .addLanguageAsyncListener(metrics)
    .build();
```

脚本可以提交 Lambda、函数调用或方法调用：

```javascript
var first = async () -> calculatePrice(order);
var second = async inventory.load(order.productId);
return await first + await second;
```

语言 Lambda 推荐使用 Java 风格 `->`；`=>` 与其完全等价，仅作为历史兼容写法保留。绑定方法引用可先赋值，再由异步 Lambda 调用：

```javascript
var calculate = pricingService::calculatePrice;
var first = async () -> calculate(order);
return await first;
```

方法引用捕获创建时的执行上下文，因此应留在父脚本及其结构化子任务作用域内。不要把它交给父脚本结束后仍可能运行的非托管线程；跨请求长期任务应传递稳定的业务标识和参数，在任务内部重新获取服务对象。

`async` 返回 `ScriptAsyncResult`，兼容 `Future.get()`、`cancel()` 和 `getState()`；推荐使用 `await`。普通 `Future` 使用 Engine 隔离的 bridge。每个 `JvmScriptEngine` 默认创建自己的有界守护线程池，工作线程数为 `min(32, max(4, CPU * 2))`，队列容量为 1024；一个 Engine 的饱和、拒绝统计和关闭不会影响其他 Engine。可通过 `engine.getFutureBridgeStats()` 查看活跃数、队列、拒绝与累计完成数。

可通过 `languageBlockingThreads(...)` 和 `languageBlockingQueueCapacity(...)` 调整 Engine 自有 bridge。也可以配置外部 `languageBlockingExecutor(...)`，但外部 executor 与内部池尺寸参数互斥，且不会被 Engine 关闭。Context 显式设置的 blocking executor 优先于 Engine bridge。

语言子任务采用结构化生命周期：父脚本正常返回前等待全部子任务完成，父脚本失败或取消时取消其子任务。取消后超过 `cancellationJoinTimeout` 仍不退出的任务进入 `ABANDONED` 并脱离父作用域；为避免孤儿线程读取被复用的数据，该 Context 在工作线程真正退出前不可再次执行。

嵌套策略支持 `INLINE`、`EXECUTOR` 和 `AUTO`。`AUTO` 只有在已知线程池存在空闲/可立即创建的工作线程时才提交，否则内联执行以避免单线程或排队线程池死锁。

任务事件和 `ScriptAsyncResult.getTrace()` 提供 parent task ID 与异步深度。当前调试器仍不支持跨线程暂停帧，`ScriptDebugContext` 中使用语言级 `async` 会明确失败；普通执行和宿主异步任务不受影响。

## 3. 状态机与完成契约

```text
QUEUED -> RUNNING -> SUCCEEDED
                  -> FAILED
                  -> CANCELLING -> CANCELLED
                  -> TIMING_OUT -> TIMED_OUT
QUEUED -> CANCELLED
QUEUED -> REJECTED
```

状态使用 CAS 单向迁移。取消、超时和正常完成发生竞争时，只允许一个终止原因胜出。

- 排队任务取消后立即完成，不会创建 Context；
- 运行任务先进入 `CANCELLING` 或 `TIMING_OUT`；
- 只有工作代码真正退出后，`completion()` 才完成；
- 阻塞且不响应中断的 Java 调用可能长期停留在中间状态。

完成结果：

| 状态 | `CompletionStage` 结果 |
| --- | --- |
| `SUCCEEDED` | 脚本返回值 |
| `FAILED` | 原始失败异常 |
| `CANCELLED` | `CancellationException` |
| `TIMED_OUT` | `ScriptExecutionException(TIMED_OUT)` |
| `REJECTED` | `RejectedExecutionException` |

`cancel()` 返回 `true` 只表示成功发起取消，不表示阻塞调用已经退出。

## 4. 上下文隔离

Context 工厂在任务真正开始时调用：

```java
executor.submit(script, () -> new ScriptContext(input));
```

规则：

- 工厂不能返回 `null`；
- 排队任务取消或拒绝时不会调用工厂；
- 同一个 Context 不能同时被两个异步任务使用；
- 变量值不会被框架自动深复制；
- `correlationId` 会传入执行事件，task ID 会自动写入执行上下文。

## 5. 背压与线程池所有权

框架创建线程池时，`queueCapacity` 配置真实的有界工作队列：

```java
ScriptAsyncExecutor.builder()
    .corePoolSize(4)
    .maximumPoolSize(8)
    .queueCapacity(500)
    .build();
```

使用外部线程池时，通过 `maxOutstandingTasks` 限制“排队 + 运行”的任务总数：

```java
ScriptAsyncExecutor.builder()
    .executorService(workerPool)
    .scheduler(timeoutScheduler)
    .maxOutstandingTasks(500)
    .build();
```

达到上限会返回状态为 `REJECTED` 的任务。框架不会关闭外部 Executor 或 Scheduler。内部工作线程支持名称前缀和 daemon 配置。`JvmScriptEngine.close()` 只回收 Engine 自己创建的 Future bridge；宿主注入的 executor 仍由宿主负责关闭。

## 6. 超时与取消

默认超时从工作线程完成 Context 创建、准备开始脚本执行时计算，不包含排队时间：

```java
.defaultTimeout(2, TimeUnit.SECONDS)
```

也可以在单次提交时覆盖：

```java
executor.submit(script, contextFactory, 500, TimeUnit.MILLISECONDS);
```

取消和超时同时发送 Context 协作信号与线程中断。脚本检查点会响应信号；网络、文件或锁等待仍需宿主 API 自己配置超时。

## 7. 监控关联

`ScriptTaskListener` 接收每次状态变化。`ScriptTaskEvent` 包含：

- task ID、脚本名称和实际脚本版本；
- parent task ID 和异步深度；
- correlation ID；
- 提交、开始和完成时间；
- 排队耗时、执行耗时和失败对象。

`ScriptTaskMetrics` 聚合提交、启动、成功、失败、取消、超时、拒绝、活跃任务和平均耗时。脚本执行事件同时包含 `taskId` 与 `correlationId`，可以与任务事件直接关联。

监听器异常会被隔离。监听器应保持轻量，外部网络上报应转交独立的有界队列。

## 8. 生命周期

```java
executor.shutdown();
executor.awaitTermination(5, TimeUnit.SECONDS);
```

- `shutdown()`：停止接收新任务，等待已接收任务完成；
- `shutdownNow()`：请求取消当前所有任务；
- `close()`：先优雅关闭，超过 `closeTimeout` 后请求取消；
- `isShutdown()` / `isTerminated()`：查询生命周期状态。

## 9. 后续阶段

### 阶段二：上下文快照与批量执行

- `ScriptContextSnapshot`；
- `submitAll`、最大并行度与批量失败策略；
- 排队超时和按脚本并发配额。

### 阶段三：语言异步增强（组合与追踪已完成）

- 跨线程调试帧暂停与单步；
- 调用链可视化展示；
- 按脚本动态调整并发配额。

## 10. 已覆盖测试场景

- `PINNED` 与 `LATEST` 热更新语义；
- 排队取消不创建 Context；
- 运行中取消和超时终态；
- Context 并发复用保护；
- 任务数量上限与拒绝；
- task ID/correlation ID 执行事件关联；
- 外部线程池所有权和关闭后拒绝；
- 监听器异常隔离和任务指标快照。
- 语言 `async/await`、局部变量快照、独立 Future bridge、父子追踪、组合取消和 race 输家策略。
