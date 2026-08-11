# Java Script 测试体系文档

## 1. 测试分层目标

本项目测试已按“语法能力、函数能力、编译行为、集成行为、回归问题、性能基线”进行分类，避免单一大类测试难维护。

对应分类如下：

- Grammar（语法语义）
  - `GrammarTests`
  - `TryCatchFinallyReturnTest`
  - `AddTests` / `MinusTests` / `MulTests` / `DivTests`
  - `ExitTests` / `ThrowTests` / `LinqTests`
- Function（函数与扩展）
  - `MethodCallTests`
  - `BeanTests`
- Compile（编译阶段）
  - `CompileTests`
- Integration（集成能力）
  - `LanguageTests`
  - `ExtensionTests`
  - `StreamTests`
  - `ScriptApiTests`（新增）
  - `MethodReferenceTests`（Lambda 箭头与绑定方法引用）
- Regression（历史问题回归）
  - `IssuesTests`
- Performance（性能基线）
  - `PerformanceTests`

## 2. Suite 入口（新增）

为便于 CI 与本地分批执行，新增了 Suite：

- 全量：`AllScriptTestsSuite`
- 语法：`GrammarTestsSuite`
- 函数：`FunctionTestsSuite`
- 编译：`CompileTestsSuite`
- 集成：`IntegrationTestsSuite`
- 回归：`RegressionTestsSuite`
- 性能：`PerformanceTestsSuite`

## 3. 新增覆盖点（更全面用例）

新增 `ScriptApiTests`，补充了原测试覆盖较弱的 API 行为：

- 内联脚本 + 上下文变量执行
- 相同源码命中编译缓存（同实例复用）
- `ScriptContext.eval` 动态求值
- 默认导包类解析（`ArrayList`）
- 未注册模块抛出 `ResourceNotFoundException`
- lambda 表达式定义与调用

新增 `MethodReferenceTests`，覆盖语言级函数引用边界：

- `->` 单行、代码块 Lambda，并验证 `=>` 保持等价兼容；
- `::` 词法 Token 与原有 Map、三元表达式、switch 冒号语法共存；
- 对象零参数、单参数、多参数方法引用和重复调用；
- 运行时方法重载与类静态方法引用；
- `Supplier`、`Function`、`BiFunction` Java 函数式接口适配；
- 空目标、方法不存在、安全策略拒绝和宿主调用次数限制。

新增 `ScriptBoundaryTests`（12 个边界场景）：

- lambda 捕获外部变量更新
- do-while 至少执行一次
- 可选链空值短路
- for 多后置表达式
- 空参数 `exit` 行为
- 数字字面量边界（16 进制/2 进制/下划线）
- 模板字符串表达式插值
- map 简写 + 显式键混用
- 强制类型转换表达式
- 解构缺失元素容错
- 关键字变量名编译失败
- 独立字面量语句编译失败

新增 `ScriptInputParameterTests`（入参场景）：

- 基础类型入参（a/b 计算）
- 嵌套 Map 入参（`user.profile.level`）
- List 入参（下标访问 + `size()`）
- 可选链处理缺失入参
- null 入参兜底分支
- Java 类方法调用入参（`Main.test1(a,b)` / `Main.test2(a,b)`）
- 方法参数个数约束（参数数量不符时报错）

上述类源码为 **ms 内动态定义**（字符串源码 + 运行时编译），并由 Java 测试代码在外部调用方法，不是在 ms 内直接调用。  
`dynamic_main_invoke` 已支持可变参数与签名匹配，可覆盖 `String`、`List` 等不同入参类型；类名可自动从源码推断，也可通过 `dynamic_main_invoke_with_class` 显式指定。

新增 `ScriptInputParameterMatrixTests`（入参矩阵场景）：

- 数值入参矩阵（负数/零/大数）
- 集合与 Map 边界（空集合、缺失字段）
- 深层可选链短路矩阵
- 空订单与非空订单分支矩阵
- 类型不匹配入参异常断言
- 动态 `Main` 调用矩阵（包含方法重载匹配）
- 动态 `Main` 异常矩阵（方法不存在、源码编译失败）

入参测试统一使用 `InputCaseTestSupport` 数据驱动基类，新增 case 只需补一条 `scriptCase/errorCase`。

新增运行时与公共 API 契约测试：

- `RuntimeHandleContractTests`：验证 336 个算术重载、256 个比较重载、150 个位运算重载，以及动态 call site、数组/集合访问和非法类型分支；
- `CoreBoundaryContractTests`：验证 Engine 隔离资源、加载失败、JSR223 Reader/Bindings、元数据模型和资源注册生命周期；
- `ScriptRepositoryTests`：验证严格事件顺序、历史 TTL/总量/清理、回滚、CRC 快照和并发执行；
- `ScriptExecutionMonitoringTests`：验证 Engine/Context 两级监听、状态分类、指标快照和监听器异常隔离；
- `ScriptAsyncExecutorTests`：验证固定/最新版本、背压拒绝、Context 隔离、取消、超时、监控关联、任务指标与线程池所有权；
- `LanguageAsyncTests`：验证有界退出、ABANDONED、Context 复用保护、嵌套调度及 bridge 统计；
- `MonitoringAdapterTests`：验证 JMX 注册，以及 Micrometer/OpenTelemetry 无强制依赖适配；
- `%` 的 `BigDecimal` / `BigInteger` 路径新增语义回归，确保返回余数而非“商和余数”数组。

## 4. 基线重构策略（当前分支）

当前分支采用 **rebaseline current** 策略：以“当前解析器 + 当前运行时能力”为准重建测试基线。

- 已回归通过：`Grammar`、`Compile`、`Integration`、`Function`、`Performance`
- Legacy 暂停项（以 `@Ignore` 标注）：
  - `LinqTests`：LINQ 语法链路暂未在当前运行时开放
  - `LanguageTests`：嵌入式 ```language``` 语法暂未启用
  - `IssuesTests`：历史问题脚本依赖已移除扩展能力
  - `BeanTests`：`asBean` 转换扩展已移除
  - `MethodCallTests` 中 2 个用例：依赖 map->bean 隐式转换

这些 legacy 用例保留在仓库中，后续如恢复相应能力可直接取消 `@Ignore` 继续验收。

## 5. 执行方式

全量测试：

```bash
mvn test
```

覆盖率报告与 85% 行覆盖率、70% 分支覆盖率门槛：

```bash
mvn -Pcoverage clean verify
```

当前基线：278 个测试，其中 272 个通过、6 个按设计跳过；行覆盖率 90.53%（7957/8789），分支覆盖率 71.03%（3014/4243），coverage profile 分别保持 85% 和 70% 门槛。

工程质量门禁：

```bash
# Maven/依赖约束、未使用 import、SpotBugs 高优先级问题、依赖声明
mvn -Pquality -DskipTests verify

# 关键运行时类的变异测试（覆盖率至少 90%，变异分数至少 75%）
mvn -Pmutation verify

# 需要仓库中已发布的旧版本作为基线
mvn -Papi-compat -Dapi.baseline.version=<上一版本> verify
```

JMH 性能基线：

```bash
mvn -Pbenchmark -DskipTests package
java -jar target/java-script-1.0.0-benchmarks.jar -l
java -jar target/java-script-1.0.0-benchmarks.jar
```

基准覆盖已编译脚本执行、编译缓存命中、命名仓库版本执行和宿主方法缓存解析。CI 只运行短冒烟测试；正式性能比较应使用固定 JVM、硬件和 JMH 参数，并保存结果作为基线。

当前 PIT 基线：关键类行覆盖率 94%（761/810），416 个变异体中杀死 323 个，变异分数 78%，测试强度 84%。PIT 仅由手动 CI 触发，避免拖慢每次提交。

按 Suite 执行（示例）：

```bash
mvn -Dtest=suite.cn.ykccchen.script.IntegrationTestsSuite test
mvn -Dtest=suite.cn.ykccchen.script.RegressionTestsSuite test
```

## 6. 编写新测试的规范建议

- 新语法能力：优先放入 Grammar 分类，并在 `src/test/resources/grammar` 提供 `.ms` 样例
- 新函数/扩展：放入 Function 分类，并验证重载、可变参数、上下文参数等边界
- Bug 修复：必须补 `issues/*.ms` 回归脚本，避免回归
- 非稳定耗时场景：放入 Performance 分类，避免干扰 CI 主链路
- 新测试命名建议采用“能力_场景_预期”模式，降低维护门槛
