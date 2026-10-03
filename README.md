# Spring Boot 可插拔扩展脚手架

在同一份 Spring Boot 模板之上，支持不同使用方**按配置启用、组合与切换**各自的扩展：
扩展通过白名单注册，由配置声明启用；加载时进行去重、实例化、框架兼容性、
依赖完整性与循环依赖校验；运行期支持**增量热更新**（只重建真正变化及其依赖链上的
扩展，未变化者沿用同一运行实例）、原子刷新、幂等提交、失败整体回退，
并可一步回到上一次成功生效配置。

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
  ExtensionManager          运行时管理器：查询 / 增量刷新 / 回到上一次成功配置
  DefaultExtensionManager   增量原子切换、实例复用、串行刷新、指纹幂等、失败回退
  ExtensionRefreshPlanner   差异计算：新增/移除/重建/沿用判定与依赖连带重建
  IncrementalPlan           一次增量生效计划（目标快照 + 待激活/待停用实例）
  ExtensionAction           单个扩展的处理动作（ADDED/REMOVED/REBUILT/REUSED）
  ExtensionChangeReason     动作判定原因（换实现/依赖连带/仅顺序变化/不变等）
  ExtensionChange / RefreshReport 逐扩展结论与一次刷新的可解释结果
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
- **构建与生效分离**：新扩展先离线完成全部校验与实例化，再依次 `activate()`；
  任何一步失败，已激活的新实例按相反顺序 `deactivate()`，本次切换被整体拒绝，
  **旧集合与对外状态完全不变，旧实例继续可用**。
- **幂等**：对 `(框架版本, 声明序列)` 计算稳定指纹，重复提交相同配置直接跳过
  （`applied=false`），不产生任何重建，快照引用保持不变。
- **串行化**：刷新在同一锁内执行，并发提交只会产生合法的完整快照之一。
- 切换成功后，仅被移除/替换的旧实例按逆序 `deactivate()`（尽力而为，
  其异常不影响已生效集合）。

### 6.1 增量热更新：谁会重建，谁被复用

提交新配置后，并不是把整套扩展重建一遍，而是先离线算出差异再做最小改动。
对每个扩展 id 的判定如下：

| 情况 | 动作 `action` | 原因 `reason` | 运行实例 |
| --- | --- | --- | --- |
| 新配置里新出现的 id | `ADDED` | `NEW_ENABLED` | 新建并激活 |
| 新配置里不再声明的 id | `REMOVED` | `REMOVED_FROM_CONFIG` | 切换后停用旧实例 |
| 同一 id 换了实现类（含实现携带的依赖边/优先级声明变化） | `REBUILT` | `IMPLEMENTATION_CHANGED` | 旧实例停用、新实例创建并激活 |
| 自身没变，但**依赖链上**有对象被新增/重建 | `REBUILT` | `DEPENDENCY_REBUILT` | 连带重建，避免新旧版本同时对外服务 |
| 仅配置里的优先级覆盖变化 | `REUSED` | `ORDER_ONLY_CHANGED` | **沿用同一实例**，只改变生效顺序 |
| 实现、依赖、优先级均无变化 | `REUSED` | `UNCHANGED` | **沿用同一实例**，不停用、不重新激活 |

要点：

- **沿用是严格的“同一份实例”**：被判定为 `REUSED` 的扩展直接保留当前运行对象，
  既不会被悄悄换成新实例，也不会先停再启；因此连接池、缓存、后台线程这类重资源
  在“与它无关的变更”中完全不受影响。
- **连带重建沿依赖边向上传播到不动点**：只要某个扩展依赖（直接或传递）的对象
  被新增或替换，该扩展也会被重建。例如用另一个同 id 实现替换 `demo-core`，
  依赖它的 `demo-feature` 会被一并重建，保证不会出现“新 core + 旧 feature”的混合态。
- **开销与变化规模成正比**：增量刷新只对 `ADDED/REBUILT` 的实例执行 `activate()`，
  只对 `REMOVED/REBUILT` 的旧实例执行 `deactivate()`；新增一个与现有扩展无关的
  扩展时，其余扩展零生命周期回调。
- 依赖被**移除**（新配置不再声明被依赖方）属于非法配置，会在离线校验阶段以
  `MISSING_DEPENDENCY` 拒绝，本次刷新不生效，因此不存在“依赖突然消失”的运行态。

### 6.2 刷新结果：每个扩展被怎么处理、为什么

`POST /api/extensions/refresh` 的响应除新快照外，还带一份逐扩展结论 `changes`，
以及是否真正生效、回滚标记、切换前后指纹：

```json
{
  "frameworkVersion": "1.2.0",
  "extensions": [ ... ],
  "applied": true,
  "rolledBack": false,
  "previousFingerprint": "fw=1.2.0;ext=...DemoCoreExtension",
  "currentFingerprint": "fw=1.2.0;ext=...DemoCoreExtension,...DemoFeatureExtension",
  "changes": [
    {"id": "demo-core", "action": "REUSED", "reason": "UNCHANGED",
     "detail": "implementation and dependencies unchanged; same running instance retained"},
    {"id": "demo-feature", "action": "ADDED", "reason": "NEW_ENABLED",
     "detail": "newly enabled implementation com.github....DemoFeatureExtension"}
  ]
}
```

相同配置重复提交时 `applied=false`，`changes` 中每项均为 `REUSED/UNCHANGED`。
失败原因仍由 `ExtensionLoadException` 区分（见第 5 节）：离线校验失败返回 422，
激活失败以 `ACTIVATION_FAILED` 返回 422 且旧集合保持可用。

### 6.3 回到上一次成功配置

```http
POST /api/extensions/rollback
```

- 管理器只保留**上一次成功生效的那一份快照**（包括快照里记录在案的扩展实例）。
  回退不是照着旧配置重新构建，而是把当时的快照整体恢复：仍在运行的实例直接沿用，
  已在后续刷新中被停用的实例调用其 `activate()` 重新激活同一份对象，
  因此回退后的运行状态与当时一致，而不是“按配置重新猜一遍”。
- 行为边界：
  - 自启动以来没有任何成功刷新时调用回退，返回 `409 ILLEGAL_STATE`；
  - 回退是一步到位的原子切换，同样只有完整旧集合或完整新集合对外可见；
  - 回退会把切换前状态记为新的“上一次”，因此再次调用等于重新前进（在两份成功
    快照间来回切换）；
  - 回退本身的激活失败同样整体拒绝，当前集合保持不变。

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

## 7. 本地验证

### 7.1 运行测试

```bash
mvn test
```

测试覆盖（用例日志中会打印每条输入配置、差异判定与处理结果）：

- `VersionTest`：版本比较与最低版本满足判定；
- `ExtensionPlanBuilderTest`：未声明实现、实例化失败、重复声明、依赖缺失、
  版本不兼容、循环依赖（列出全部环成员）、多原因聚合、顺序稳定可复现；
- `ExtensionManagerTest`：相同配置幂等、激活失败整体回退且旧快照不变、
  成功切换后旧扩展逆序停用、16 线程并发刷新只观察到合法完整快照；
- `IncrementalRefreshTest`（增量热更新）：
  - 仅新增无关扩展时其余扩展**同一实例复用**、无停启回调；
  - 仅优先级覆盖变化时复用实例、只改顺序；
  - 同 id 换实现触发重建并沿依赖边**连带重建**依赖方；
  - 增量激活中途失败**整体回退**、旧实例仍在原位且后续合法刷新可继续复用；
  - 相同配置重复提交 `applied=false`、零重建；
  - 多写者 + 多读者并发期间只观察到完整快照；
  - 回退恢复上一次成功快照及其原实例、无可回退目标时抛错；
  - 单次新增只产生一次激活，开销随实际差异变化；
- `IncrementalRollbackControllerTest`：刷新报告各扩展动作/原因、幂等 `applied=false`、
  回退接口恢复旧快照、无可回退目标返回 409；
- `ExtensionControllerTest`：查询、刷新成功、422 原因可区分、非法请求 400；
- `ExtensionWiringIntegrationTest`：Spring Boot 端到端启动加载与依赖排序。

仅运行单类：

```bash
mvn test -Dtest=IncrementalRefreshTest
```

### 7.2 启动后手工验证

```bash
mvn spring-boot:run

# 查询（返回 application.properties 声明的 demo 扩展，顺序 demo-core, demo-feature）
curl -s http://localhost:8080/api/extensions

# 增量刷新：只保留 demo-core（demo-feature 被移除，demo-core 沿用）
curl -s -X POST http://localhost:8080/api/extensions/refresh \
  -H 'Content-Type: application/json' \
  -d '{"frameworkVersion":"1.2.0","enabled":["com.github.xingjianluo.springbootpluginscaffoldjava.demo.DemoCoreExtension"]}'
# 响应 changes 中 demo-core=REUSED/UNCHANGED、demo-feature=REMOVED/REMOVED_FROM_CONFIG

# 回到上一次成功配置（恢复 demo-core + demo-feature 的原快照）
curl -s -X POST http://localhost:8080/api/extensions/rollback

# 相同配置重复提交：applied=false，changes 全部 REUSED
curl -s -X POST http://localhost:8080/api/extensions/refresh \
  -H 'Content-Type: application/json' \
  -d '{"frameworkVersion":"1.2.0","enabled":["com.github.xingjianluo.springbootpluginscaffoldjava.demo.DemoCoreExtension","com.github.xingjianluo.springbootpluginscaffoldjava.demo.DemoFeatureExtension"]}'

# 非法刷新（未登记的实现），返回 422 与 UNDECLARED_IMPLEMENTATION
curl -s -i -X POST http://localhost:8080/api/extensions/refresh \
  -H 'Content-Type: application/json' \
  -d '{"frameworkVersion":"1.2.0","enabled":["com.example.NotRegisteredExtension"]}'

# 再查一次，确认旧集合保持不变
curl -s http://localhost:8080/api/extensions
```
