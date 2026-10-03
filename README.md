# Spring Boot 可插拔扩展脚手架

在同一份 Spring Boot 模板之上，支持不同使用方**按配置启用、组合与切换**各自的扩展：
扩展通过白名单注册，由配置声明启用；加载时进行去重、实例化、框架兼容性、
依赖完整性与循环依赖校验；运行期支持**增量热更新**（只重建真正变化及受其依赖
连带影响的扩展，未变化者沿用同一运行实例）、原子刷新、幂等提交、失败整体回退，
以及一步回到上一次成功生效配置。

## 1. 模块结构

```
extension/
  Extension                 扩展统一接口（id / 版本 / 优先级 / 依赖 / 最低框架版本 / 生命周期回调）
  ExtensionRegistry         可用实现白名单（未注册 = 未声明的实现）
  DefaultExtensionRegistry  线程安全、按类名稳定排序的白名单实现
  ExtensionDeclaration      一条声明：实现类全名 + 可选优先级覆盖
  ExtensionConfiguration    一次刷新的输入：框架版本 + 有序声明列表（含幂等指纹）
  EnabledExtensions         已生效扩展的不可变快照
  ExtensionPlanBuilder      离线校验 + 确定性拓扑排序，产出可生效快照
  ExtensionManager          运行时管理器：查询 / 增量刷新 / 回退
  DefaultExtensionManager   增量重建、双缓冲原子切换、串行刷新、指纹幂等、失败回退、快照回退
  ExtensionAction           逐扩展处理动作（新增/移除/重建/沿用/恢复）
  ExtensionDecision         单个扩展一次刷新的处理结论（id + 动作 + 判定依据）
  RefreshReport             一次刷新/回退的完整处理报告
  ExtensionFailureReason    可区分的失败原因枚举
  ExtensionFailure          单条失败记录（原因 / 来源 / 冲突对象 / 说明）
  ExtensionLoadException    聚合全部失败原因的拒绝异常
  Version                   按 “.” 分段的数字版本比较
config/
  ExtensionProperties       extension.* 配置绑定
  ExtensionRegistrar        各使用方实现它以登记自己的扩展
  ExtensionAutoConfiguration 组装 Bean 并在启动时执行首次加载
web/
  ExtensionController       GET /api/extensions、POST /api/extensions/refresh
  ExtensionExceptionHandler 422（按原因可区分）/ 400 错误体
demo/
  DemoCoreExtension / DemoFeatureExtension / DemoExtensionRegistrar
```

## 2. 扩展声明格式

### 2.1 实现一个扩展

扩展必须实现 `Extension`，并提供**可访问的无参构造器**：

```java
public class MyFeatureExtension implements Extension {
    public String id()                       { return "my-feature"; }   // 必填，集合内唯一
    public String version()                  { return "1.0.0"; }        // 可选
    public int priority()                    { return 20; }             // 可选，默认 100，越小越靠前
    public Set<String> requiredExtensions()  { return Set.of("my-core"); } // 依赖的扩展 id
    public String requiredFrameworkVersion() { return "1.0.0"; }        // 最低宿主版本
    public void activate()   { /* 进入生效集合时调用 */ }
    public void deactivate() { /* 退出生效集合时调用 */ }
}
```

### 2.2 登记白名单

只有登记过的实现才允许被配置启用。实现 `ExtensionRegistrar` 并注册为 Spring Bean：

```java
@Component
public class MyExtensionRegistrar implements ExtensionRegistrar {
    public void register(ExtensionRegistry registry) {
        registry.register(MyCoreExtension.class);
        registry.register(MyFeatureExtension.class);
    }
}
```

### 2.3 配置启用

`src/main/resources/application.properties`：

```properties
extension.framework-version=1.2.0
extension.enabled=com.acme.MyFeatureExtension,com.acme.MyCoreExtension
```

YAML 写法等价：

```yaml
extension:
  framework-version: "1.2.0"
  enabled:
    - com.acme.MyFeatureExtension
    - com.acme.MyCoreExtension
```

运行期通过接口提交（`enabled` 支持列表，单项也可写成逗号分隔串）：

```http
POST /api/extensions/refresh
Content-Type: application/json

{
  "frameworkVersion": "1.2.0",
  "enabled": ["com.acme.MyFeatureExtension", "com.acme.MyCoreExtension"]
}
```

## 3. 优先级与依赖规则

生效顺序由两阶段决定，结果**稳定且可复现**：

1. **依赖优先（拓扑序）**：被依赖的扩展始终排在依赖方之前；
2. **同层确定性裁决**：同一就绪层按 `(priority 升序, 配置声明下标)` 选择。

因此最终顺序只取决于配置本身，与注册先后、类加载顺序、重复提交次数无关。

- `priority` 可取扩展自身声明值，也可由配置覆盖（编程式：
  `new ExtensionDeclaration(className, priorityOverride)`）；
- 依赖只能指向同一次启用集合内的扩展 id。

## 4. 兼容性判定

- 版本号按 `.` 分段逐段比较数字大小，缺失段视为 `0`：`1.2 == 1.2.0`，`2.0.1 > 2.0`；
- 判定条件：`实际框架版本 >= 扩展声明的 requiredFrameworkVersion`，否则拒绝；
- 无法解析的版本号直接作为非法输入（HTTP 400）报出。

## 5. 拒绝原因（可区分、可解释）

校验是**全量收集**的：一次提交即使存在多处问题，也会在同一个
`ExtensionLoadException`（HTTP 422）中返回所有原因，每条包含
`reason / source / conflict / detail`：

| reason | 触发条件 | conflict 含义 |
| --- | --- | --- |
| `UNDECLARED_IMPLEMENTATION` | 声明的类不在白名单 | - |
| `INSTANTIATION_FAILED` | 无无参构造器或构造/取 id 抛异常 | - |
| `DUPLICATE_DECLARATION` | 类名重复或扩展 id 重复 | 重复的类名/id |
| `MISSING_DEPENDENCY` | 依赖的扩展不在启用集合 | 缺失的依赖 id |
| `INCOMPATIBLE_VERSION` | 框架版本低于最低要求 | 要求的最低版本 |
| `CIRCULAR_DEPENDENCY` | 依赖关系成环 | 环上全部 id 列表 |
| `ACTIVATION_FAILED` | `activate()` 抛异常 | - |

响应示例：

```json
{
  "error": "EXTENSION_LOAD_REJECTED",
  "failures": [
    {"reason": "MISSING_DEPENDENCY", "source": "beta", "conflict": "alpha",
     "detail": "declared dependency is not present in the enabled set"}
  ]
}
```

## 6. 运行期刷新语义

- **原子可见性**：生效集合保存在不可变快照中，经 `AtomicReference` 切换。
  刷新期间 `GET /api/extensions` 只会观察到完整的旧集合或完整的新集合，
  不会出现半加载或新旧混用。
- **构建与生效分离**：新集合先离线完成全部校验与实例化，再依次 `activate()`；
  任何一步失败，已激活的新实例按相反顺序 `deactivate()`，本次切换被整体拒绝，
  **旧集合与对外状态完全不变**。
- **幂等**：对 `(框架版本, 声明序列)` 计算稳定指纹，重复提交相同配置直接跳过，
  快照引用保持不变。
- **串行化**：刷新在同一锁内执行，并发提交只会产生合法的完整快照之一。
- 切换成功后，旧实例按逆序 `deactivate()`（尽力而为，其异常不影响已生效集合）。

查询接口返回当前快照：

```http
GET /api/extensions
```

```json
{
  "frameworkVersion": "1.2.0",
  "extensions": [
    {"id": "demo-core", "version": "1.0.0", "priority": 10,
     "requiredExtensions": [], "requiredFrameworkVersion": "0.0.0",
     "implementation": "com.github....DemoCoreExtension"},
    {"id": "demo-feature", "version": "1.2.0", "priority": 20,
     "requiredExtensions": ["demo-core"], "requiredFrameworkVersion": "1.0.0",
     "implementation": "com.github....DemoFeatureExtension"}
  ]
}
```

## 7. 增量热更新

刷新不是把整套扩展推倒重建，而是先**离线做差异判定**，只让真正受影响的扩展付出重启成本。
连接池、缓存、后台线程这类启动昂贵的扩展，只要它自己及其（传递）依赖没有变化，
就会一直沿用正在运行的同一份实例——不换引用、不先停后启、不重复 `activate()`。

### 7.1 什么情况下复用、什么情况下重建

判定以「实现类全名 + 依赖关系」为准，逐扩展给出四种动作：

| 动作 | 触发条件 | 实例与生命周期 |
| --- | --- | --- |
| `RETAINED`（沿用） | 实现类不变，**且**它依赖的每个实例本次也都沿用（传递依赖同理） | 原实例继续运行，无任何回调，顺序不变 |
| `ADDED`（新增） | 新配置里出现、上一个生效集合没有的 id | 新建实例并 `activate()` |
| `REMOVED`（移除） | 上一个集合有、新配置不再声明的 id | 切换后对旧实例 `deactivate()` |
| `RECREATED`（重建） | 同一 id 换了实现类，**或**它依赖（含传递依赖）的对象被换掉 | 新建实例并 `activate()`，旧实例切换后 `deactivate()` |

说明与边界：

- **优先级覆盖变化**（配置里的 `priorityOverride`）只影响激活顺序，不改变扩展身份，
  因此仍然沿用原实例、不重建。
- **仅框架版本号变化**时：对仍满足 `requiredFrameworkVersion` 的扩展沿用原实例
  （报告会附带版本变化说明）；只要有任一扩展不再兼容，本次刷新在离线校验阶段即被整体拒绝，
  运行集合完全不变。
- 判定依据是扩展在接口里声明的 `requiredExtensions()`：依赖身份（被依赖方的实例）
  没变就不连带，变了就向上闭包连带。仅「新增」一个扩展，不会让任何既有扩展重建。
- 「同 id 换实现类」属于重建而不是沿用——即使两个类行为相同，身份已经改变。

### 7.2 依赖变化时怎么连带处理

系统对「沿用」关系求一个不动点闭包：一个扩展能沿用，当且仅当它的实现类没变
**并且**它依赖的所有实例也都沿用。因此：

- 依赖被换掉（或移除导致其重建）时，直接依赖方会被标记 `RECREATED`，
  依赖的依赖继续向上传播，直到没有新的连带对象；
- 与变化路径无关的扩展不受影响。例如 `a -> b`、独立扩展 `c`，只重建 `a` 时
  `b`、`c` 都沿用；若换的是 `b`，则 `a` 连带重建，`c` 仍沿用；
- 若新配置删掉了某个仍被依赖的扩展，属于 `MISSING_DEPENDENCY`，
  在离线校验阶段直接拒绝，**不会**走到任何激活/停用步骤。

### 7.3 原子切换与整体回退

一次增量刷新在同一把锁内串行执行，期间的查询只能读到完整旧快照或完整新快照：

1. **离线构建**：完成白名单、去重、实例化、版本、依赖、环等全部校验与差异判定，
   全程不触碰任何运行实例；
2. **只激活新建实例**：`ADDED` 与 `RECREATED` 的新实例按拓扑序 `activate()`，
   沿用实例保持运行。任一激活抛错，已激活的新实例按反序 `deactivate()`，
   本次切换被整体拒绝——不会出现有的换了、有的没换，旧集合继续正常服务；
3. **原子切换**：`AtomicReference` 一次性替换对外快照；
4. **清理旧实例**：仅对「被替换 / 被移除」的旧实例反序 `deactivate()`（尽力而为，
   其异常不影响已生效集合）；沿用实例的旧引用仍在新快照中，绝不停用。

开销与变化规模成正比：沿用实例既不实例化也不回调，只有新建实例才付出启动成本。

### 7.4 幂等

对 `(框架版本, 声明序列)` 计算稳定指纹。相同配置重复提交直接跳过，
报告中 `skipped=true`，每个扩展都是 `RETAINED` 且**不产生任何重建或回调**，
快照引用也保持不变。

### 7.5 回到上一次成功生效配置

`POST /api/extensions/restore`（编程式为 `ExtensionManager.restore()`）一步回到
**上一次成功生效**的配置：

- 回退复用的是**当时那份快照里的同一批实例对象**，而不是照着配置重新实例化、重新猜状态；
  两份快照共有的实例本就在运行、直接沿用，仅对「目标快照独有、此前已停用」的实例
  按拓扑序重新 `activate()`，然后原子切回；
- 回退本身同样是原子的：中途任何激活失败，已重新激活的实例反序停用，当前集合原样保留；
- 行为边界：只保留最近一份成功快照。回退之后再回退一次，等价于在最近两份成功配置之间
  **来回切换**（新的“上一次”即回退前的集合）；应用启动后从未成功刷新过时调用回退，
  返回 `409 INVALID_STATE`；被刷新拒绝（失败）的配置不会进入回退历史，回退目标永远是
  一份曾经成功生效过的配置。

### 7.6 处理报告与可区分的失败原因

刷新/回退响应在快照之外附带 `report`：`mode`（`REFRESH`/`RESTORE`）、配置指纹、
`skipped` 标记，以及逐扩展的 `decisions`（`id` / `action` / `reason`），
`reason` 会说明为什么这么处理（新增、换实现、依赖连带、沿用、移除、恢复）。
失败仍按第 5 节返回 422 与可区分原因（含增量激活阶段的 `ACTIVATION_FAILED`）。

```json
{
  "frameworkVersion": "1.2.0",
  "extensions": [ ... ],
  "report": {
    "mode": "REFRESH",
    "fingerprint": "fw=1.2.0;ext=com.acme.DemoCoreExtension,...",
    "skipped": false,
    "decisions": [
      {"id": "demo-core", "action": "RETAINED",
       "reason": "implementation and its (transitive) dependencies are unchanged, reusing the running instance: ..."},
      {"id": "demo-feature", "action": "ADDED",
       "reason": "not present in the previous active set, freshly instantiated: ..."}
    ],
    "snapshotIds": ["demo-core", "demo-feature"]
  }
}
```

## 8. 本地验证

### 8.1 运行测试

```bash
mvn test
```

测试覆盖（用例日志中会打印每条输入配置、差异判定与处理结果）：

- `VersionTest`：版本比较与最低版本满足判定；
- `ExtensionPlanBuilderTest`：未声明实现、实例化失败、重复声明、依赖缺失、
  版本不兼容、循环依赖（列出全部环成员）、多原因聚合、顺序稳定可复现；
- `ExtensionManagerTest`：相同配置幂等、激活失败整体回退且旧快照不变、
  成功切换后旧扩展逆序停用、16 线程并发刷新只观察到合法完整快照；
- `IncrementalExtensionManagerTest`（增量热更新专项）：
  新增无关扩展时既有实例全部沿用且不重复回调；
  同 id 换实现后自身与传递依赖方连带重建、无关扩展不连坐；
  增量激活失败整体回退、旧实例仍是同一对象且可继续服务后再成功刷新；
  相同配置重复提交零重建零回调（`skipped=true`）；
  8 写 8 读并发下读线程只能观察到完整自洽快照、未变化扩展身份唯一；
  回退复用上一份成功快照的同一批实例、连续回退在两份成功配置间切换、无历史时拒绝；
- `ExtensionControllerTest`：查询、刷新成功、422 原因可区分、非法请求 400；
- `IncrementalExtensionControllerTest`：刷新响应中的逐扩展 `report`、
  幂等 `skipped`、`/restore` 回退报告与无历史时 409；
- `ExtensionWiringIntegrationTest`：Spring Boot 端到端启动加载与依赖排序。

仅运行增量专项：

```bash
mvn test -Dtest=IncrementalExtensionManagerTest
```

### 8.2 启动后手工验证

```bash
mvn spring-boot:run

# 查询（返回 application.properties 声明的 demo 扩展，顺序 demo-core, demo-feature）
curl -s http://localhost:8080/api/extensions

# 运行期刷新为仅启用 demo-core（响应 report.decisions 会给出每个扩展的动作与原因）
curl -s -X POST http://localhost:8080/api/extensions/refresh \
  -H 'Content-Type: application/json' \
  -d '{"frameworkVersion":"1.2.0","enabled":["com.github.xingjianluo.springbootpluginscaffoldjava.demo.DemoCoreExtension"]}'

# 回到上一次成功生效配置（即启动时的 demo-core + demo-feature），复用当时同一批实例
curl -s -X POST http://localhost:8080/api/extensions/restore

# 非法刷新（未登记的实现），返回 422 与 UNDECLARED_IMPLEMENTATION
curl -s -i -X POST http://localhost:8080/api/extensions/refresh \
  -H 'Content-Type: application/json' \
  -d '{"frameworkVersion":"1.2.0","enabled":["com.example.NotRegisteredExtension"]}'

# 再查一次，确认旧集合保持不变
curl -s http://localhost:8080/api/extensions
```

观察实例复用可重点对比两次刷新：先提交包含两个 demo 扩展的配置，再提交一个仅新增
（或移除）单个无关扩展的配置，`report.decisions` 中未受影响的扩展会标记为
`RETAINED`，应用日志里也只会出现新建扩展的 `activated`，不会出现沿用扩展的
停启记录。

