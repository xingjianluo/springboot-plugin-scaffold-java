# Spring Boot 可插拔扩展脚手架

在同一份 Spring Boot 模板之上，支持不同使用方**按配置启用、组合与切换**各自的扩展：
扩展通过白名单注册，由配置声明启用；加载时进行去重、实例化、框架兼容性、
依赖完整性与循环依赖校验；运行期支持原子刷新、幂等提交与失败整体回退。

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
  ExtensionManager          运行时管理器：查询 / 刷新
  DefaultExtensionManager   双缓冲原子切换、串行刷新、指纹幂等、失败回退
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

## 7. 本地验证

### 7.1 运行测试

```bash
mvn test
```

测试覆盖（用例日志中会打印每条输入配置与判定依据）：

- `VersionTest`：版本比较与最低版本满足判定；
- `ExtensionPlanBuilderTest`：未声明实现、实例化失败、重复声明、依赖缺失、
  版本不兼容、循环依赖（列出全部环成员）、多原因聚合、顺序稳定可复现；
- `ExtensionManagerTest`：相同配置幂等、激活失败整体回退且旧快照不变、
  成功切换后旧扩展逆序停用、16 线程并发刷新只观察到合法完整快照；
- `ExtensionControllerTest`：查询、刷新成功、422 原因可区分、非法请求 400；
- `ExtensionWiringIntegrationTest`：Spring Boot 端到端启动加载与依赖排序。

仅运行单类：

```bash
mvn test -Dtest=ExtensionPlanBuilderTest
```

### 7.2 启动后手工验证

```bash
mvn spring-boot:run

# 查询（返回 application.properties 声明的 demo 扩展，顺序 demo-core, demo-feature）
curl -s http://localhost:8080/api/extensions

# 运行期刷新为仅启用 demo-core
curl -s -X POST http://localhost:8080/api/extensions/refresh \
  -H 'Content-Type: application/json' \
  -d '{"frameworkVersion":"1.2.0","enabled":["com.github.xingjianluo.springbootpluginscaffoldjava.demo.DemoCoreExtension"]}'

# 非法刷新（未登记的实现），返回 422 与 UNDECLARED_IMPLEMENTATION
curl -s -i -X POST http://localhost:8080/api/extensions/refresh \
  -H 'Content-Type: application/json' \
  -d '{"frameworkVersion":"1.2.0","enabled":["com.example.NotRegisteredExtension"]}'

# 再查一次，确认旧集合保持不变
curl -s http://localhost:8080/api/extensions
```
