# 可插拔扩展加载与运行时管理

本脚手架提供一套按配置启用、组合、切换扩展的能力。使用方只需：

1. 实现 `PluginExtension` 接口（公开无参构造）；
2. 在配置中声明要启用的扩展、优先级、依赖与最低框架版本；
3. 通过 HTTP 查询当前集合或在运行期原子刷新。

## 1. 扩展声明格式

配置前缀为 `plugin`：

```properties
# 当前框架版本（用于兼容性判定），语义化版本
plugin.framework-version=1.2.0

# 声明启用的扩展（下标即书写顺序，不影响最终加载顺序）
plugin.extensions[0].name=audit
plugin.extensions[0].implementation=com.example.AuditExtension
plugin.extensions[0].version=1.3.0
plugin.extensions[0].priority=10
plugin.extensions[0].requires[0]=storage:2.0.0
plugin.extensions[0].requires[1]=metrics:1.0.0
plugin.extensions[0].min-framework-version=1.0.0
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `name` | 是 | 扩展唯一名，依赖引用与查询都基于它；重复即拒绝 |
| `implementation` | 是 | 实现类全限定名，必须实现 `PluginExtension`，非抽象且有公开无参构造 |
| `version` | 否（默认 `1.0.0`） | 扩展自身版本，供依赖做最低版本校验 |
| `priority` | 否（默认 `100`） | 优先级，数值越小越靠前 |
| `requires` | 否 | 依赖列表，每项格式 `依赖名:最低版本`；省略版本等价 `0.0.0` |
| `min-framework-version` | 否 | 要求的最低框架版本，不满足即拒绝 |

实现类要求：

```java
public class AuditExtension implements PluginExtension {
    public AuditExtension() { /* 必须有公开无参构造，构造抛异常会被拒绝 */ }
}
```

脚手架内置两个可直接试用的样例实现：
`...extension.sample.SampleCoreExtension` 与 `...extension.sample.SampleAddonExtension`（后者依赖前者）。
启动后通过 `POST /api/extensions/refresh` 提交如下最小配置即可验证：

```json
{"extensions": [
  {"name": "sample-core", "implementation": "com.github.xingjianluo.springbootpluginscaffoldjava.extension.sample.SampleCoreExtension",
   "version": "1.5.0", "priority": 10, "requires": []},
  {"name": "sample-addon", "implementation": "com.github.xingjianluo.springbootpluginscaffoldjava.extension.sample.SampleAddonExtension",
   "version": "1.0.0", "priority": 20, "requires": ["sample-core:1.0.0"]}
]}
```

## 2. 优先级与加载顺序规则

最终顺序由 Kahn 拓扑排序决定，保证**被依赖者一定出现在依赖者之前**；
在“当前没有未满足依赖、可立即输出”的节点集合中，按以下次序稳定选取：

1. `priority` 升序（数值小者优先）；
2. `priority` 相同则按 `name` 字典序。

因此加载集合与顺序与声明书写顺序无关，只取决于配置内容，**稳定且可复现**。

## 3. 依赖规则

- 依赖的扩展必须在同一份配置中声明启用，否则拒绝（`MISSING_DEPENDENCY`）；
- 被依赖扩展的 `version` 必须不低于要求的最低版本（`DEPENDENCY_VERSION_NOT_SATISFIED`）；
- 不允许循环依赖，检测到环时拒绝并打印具体环路（`CIRCULAR_DEPENDENCY`）；
- 任一扩展校验/实例化失败，整批加载被拒绝，**不会出现部分扩展生效**。

## 4. 兼容性判定方式

- 版本按 `major.minor.patch` 三段数值比较，缺省段按 `0` 处理，
  后缀（如 `-SNAPSHOT`）忽略；例如 `1.2` ≙ `1.2.0`。
- 若扩展声明了 `min-framework-version`，当前 `plugin.framework-version`
  必须不低于它，否则拒绝（`FRAMEWORK_VERSION_NOT_SATISFIED`）。
- 依赖最低版本判定使用同一套比较规则。

## 5. 拒绝原因（可区分）

| reason | 含义 | conflictTarget 示例 |
| --- | --- | --- |
| `DUPLICATE_DECLARATION` | 同名扩展重复声明 | `audit` |
| `UNDECLARED_IMPLEMENTATION` | 实现类未声明/不存在/未实现接口 | `audit` |
| `UNINSTANTIABLE_IMPLEMENTATION` | 抽象类、接口、无公开无参构造或构造抛异常 | `audit` |
| `MISSING_DEPENDENCY` | 依赖的扩展未启用 | `audit->storage` |
| `DEPENDENCY_VERSION_NOT_SATISFIED` | 依赖版本过低 | `audit->storage@1.5.0` |
| `CIRCULAR_DEPENDENCY` | 存在依赖环 | `alpha -> beta -> alpha` |
| `FRAMEWORK_VERSION_NOT_SATISFIED` | 框架版本低于扩展要求 | `audit` |

## 6. 运行期刷新与一致性

- `GET /api/extensions`：查询当前生效集合（稳定顺序）与配置签名；
- `GET /api/extensions/health`：返回签名与启用数量；
- `POST /api/extensions/refresh`：提交新的完整声明集合进行刷新。

刷新请求体：

```json
{
  "extensions": [
    {"name": "audit", "implementation": "com.example.AuditExtension",
     "version": "1.3.0", "priority": 10, "requires": ["storage:2.0.0"],
     "minFrameworkVersion": "1.0.0"}
  ]
}
```

刷新语义：

- **原子性**：新配置在“影子状态”上完成全部校验与实例化，成功后才替换
  `volatile` 快照引用；刷新期间并发查询只会看到完整旧快照或完整新快照，
  不会看到半加载/新旧混用状态。
- **幂等性**：对配置内容（名称、实现、版本、优先级、依赖集合、框架要求）
  计算 SHA-256 签名，与声明书写顺序无关；签名相同返回 `changed=false` 且
  不替换快照。并发提交相同配置只有一个线程实际切换。
- **整体回退**：新配置任一环节失败时返回 `422` 与上述可区分原因，
  旧快照原封不动，不允许部分启用/停用。
- 启动时按 `application.properties` 做一次初始加载；初始配置不合法将直接
  导致应用启动失败，避免歧义状态。

刷新成功响应：`{"changed": true, "signature": "...", "extensions": {...}}`；
失败响应：`422 {"reason": "MISSING_DEPENDENCY", "message": "...", "conflictTarget": "audit->storage"}`。

## 7. 本地验证方法

```bash
# 编译
mvn compile

# 运行全部测试（20 个：加载器单测、注册中心并发/回滚/幂等单测、Spring 集成测试）
mvn test

# 只跑扩展相关测试并查看判定日志（测试中会打印输入配置、期望/实际原因与判定依据）
mvn test -Dtest='ExtensionLoaderTests,ExtensionRegistryTests,ExtensionIntegrationTests'

# 启动后手工验证
mvn spring-boot:run
curl -s http://localhost:8080/api/extensions
curl -s http://localhost:8080/api/extensions/health
curl -s -X POST http://localhost:8080/api/extensions/refresh \
  -H 'Content-Type: application/json' -d '{"extensions":[]}'
```

## 8. 主要类

- `PluginExtension`：扩展统一接口；
- `ExtensionDeclaration` / `ExtensionProperties`：声明模型与配置绑定；
- `Version`：语义版本解析与比较；
- `ExtensionLoader`：校验、实例化、依赖/兼容性判定、稳定拓扑排序、签名计算；
- `ExtensionRegistry`：持有不可变快照，提供无锁查询与原子/幂等/可回退刷新；
- `ExtensionInitializer`：启动时加载初始配置；
- `web.ExtensionController`：查询与刷新 HTTP 接口。
