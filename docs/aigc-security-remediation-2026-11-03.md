# AIGC 代码审查整改与验证报告

日期：2026-11-03
仓库：`D:\Java\tianji`，分支 `Final`
对照：Muse 的 `tj-aigc-code-review-2026-11-03 (2).md`（v3），并保留历史编号 H1、M7、M13、L9 的复核结论。
范围：H1–H3、M1–M13、L1–L15，共 31 项。

## 1. 结论

31 项均有明确处理结论。成立且可在本次代码范围内修复的问题已落在当前工作区；M7 经本地 HTTP 协议测试验证已有恢复机制；H1/L9/L13 撤回或不成立；L12 保留产品既定行为。**不能据此宣称全部风险已清零或生产验收已完成**：L5 的旧库清理积压、L10 的生产配置与网络边界、L11 的剩余依赖告警仍须后续处理。内部接口令牌与新版记忆密文还要求协调发布。

本报告中的“已修”指源码完成并纳入本地回归；未操作真实业务库、真实模型、线上索引或实际密钥。验证完成后，经用户授权创建了分组本地提交（见第 6 节）；未 push、创建 PR 或部署。

本轮 AIGC 回归执行 **896 项：818 通过、78 跳过、0 失败、0 错误**；明确排除既有失效的 `AigcContractTest`。机器人调用侧 14 项通过，可选课程 MCP 模块 2 项通过、1 项跳过。**未声称无排除的全量测试通过**；旧契约测试的 9 项上下文错误及排除原因见第 5 节。

## 2. 逐项处理

### 2.1 H 系列

| 编号 | 结论 | 实际处理与证据 |
|---|---|---|
| H1 | 撤回 | 报告所指为嵌套环境变量表达式，未发现对应的字面量 API Key；没有凭空轮换真实密钥。 |
| H2 | 已修 | `UserMemoryStore` 领取事务只提交领取状态和 attempts，再在事务外解密任务、解析 JSON、读取快照。失败持久化退避/第三次 FAILED；按用户、epoch、token 保护登记；其他用户继续领取。后续 M11 将坏记忆行独立隔离，健康快照仍可使用。`UserMemoryStoreTest` 回归领取、坏 payload/JSON、旧令牌、epoch、快照冲突等。 |
| H3 | 已修 | `ChatServiceImpl` 对未分类错误返回固定 `CHAT_GENERATION_FAILED`，随后发送 STOP；保留专用错误及主动停止语义。兼容 Spring AI 对异常的包装，避免上下文超限误落通用分类。`ChatUnhandledErrorTest` 覆盖首 token 前、部分输出后失败及分类。客户端断开后不保证终止事件送达。 |

### 2.2 M 系列

| 编号 | 结论 | 实际处理与证据 |
|---|---|---|
| M1 | 已修 | `KnowledgeStoreConfig` 启动期检查存储和模式：Redis 仅 A/APRIME；B/C/D/B_CONTEXT 要求 ES，避免请求时静默降级。 |
| M2 | 已修 | `KnowledgeRetrievalAdvisor` 同步取消抛 `CancellationException` 快速结束；流式取消保留停止语义，迟到的 preparation 不启动模型。`RemainingRetrievalFixesTest`、`KnowledgeRetrievalAdvisorTimeoutTest`。 |
| M3 | 已修超时；保留串行 | embedding HTTP 读超时从 60s 降为 10s，仍由请求预算/guard 限制结果使用。双路召回保留现有串行顺序和 terminal 保护。未把“60 大于 25”当作必然额外阻塞 35s 的证据；未声称底层 HTTP 调用在任意取消瞬间都能抢占终止。 |
| M4 | 已修 | `KnowledgeRagProperties` 增加嵌套校验、正数/范围/超时/上下文预算约束，默认值及实验模式/rerank 回退保留。`KnowledgeRagValidationTest`。 |
| M5 | 已修 | 仅 COURSE_INTAKE/BASICS/GOAL 放宽不会使用的 clarification 字段；服务端生成 intake 问句；GENERAL/REFERENCE 等仍严格校验，处理 null 后再 strip。`AgentDispatcherIntakeClarificationTest`。 |
| M6 | 已修 | 平台规则捷径要求规则意图，课程指代优先排除；不再仅凭“签到/兑换码”抢走课程问题。`AgentDispatcherPlatformRuleTest`、`PlatformRuleQuestionTest`。 |
| M7 | 已有机制，验证关闭 | 本地会话感知 HTTP 服务模拟 MCP 会话恢复，SDK 现有恢复行为通过测试。未增加“任意异常都 invalidate 共享连接”的逻辑。`RemainingMcpSessionRecoveryTest`。 |
| M8 | 已修 | 五个 CourseTools 入口共用每回合计数，最多 8 次；新回合重置。工具异常处理器重抛超限异常以中断递归；保留原 8 轮限制。`CourseToolsCallBudgetTest`、`CourseToolCallBudgetOpenAiChatModelTest`。 |
| M9 | 已修 | MCP 等待本地期限约 25ms 检查，Redis 归属检查约 250ms；stage、结果返回和显式 checkActive 边界仍即时校验。`RemainingMcpCallScopeTest`、`CourseMcpClientTest`。 |
| M10 | 已修 | 删除无业务调用方的 `EmbeddingController`，消除 `/embedding` 任意写入口。受控 Markdown 白名单导入保留。测试确认不存在处理器且该类无法加载；公共异常处理可能返回 500，故不虚称旧路径必为 HTTP 404。 |
| M11 | 已修，发布有兼容条件 | AES-GCM 新密文携带格式版本和 keyId，保留用户 AAD；旧格式可用配置的旧钥解密，旧钥仅解密，新写入始终用当前钥。逐行隔离坏密文且保留存储行，坏行仍计配额。`UserMemoryCipherTest`、`UserMemoryStoreTest`。 |
| M12 | 已修 | 检查 markPersisted/markReady/complete 返回值并增加脱敏日志；停止不提取，已 READY 任务不被 abandon 误删。`UserMemoryReleaseTest`、`UserMemoryHandoffTest`。 |
| M13 | 已修 | 新增普通 Agent 请求预算，在会话记忆读入后按完整旧轮次裁剪本次送模内容，不删除数据库历史，保留当前问题及系统消息；课程准备循环每次模型调用及最终生成前检查，计入工具 schema、参数与结果。RouteAgent/BotTextService 直调也检查。token 为估算并预留输出/安全余量。`ChatContextBudgetTest`。 |

### 2.3 L 系列

| 编号 | 结论 | 实际处理与证据 |
|---|---|---|
| L1 | 已修 | `AgentDispatcher` 决策后 active/Redis 检查切到 boundedElastic；失效请求保留停止语义。`RemainingRoutingCardsIntakeTest`。 |
| L2 | 已修 | ES/Redis 按 documentId 枚举实际旧分片 ID，最多 512，返回不完整则拒绝处理；同 hash 还比对 ID、标题、章节、模型、index 和分片正文，完全相同才跳过。成功 add 后删除“实际旧 ID − 新 ID”，失败不删。无 schema 迁移，不扫全库。`KnowledgeDocumentServiceTest`、`KnowledgeDocumentIndexTest`、`RemainingRetrievalFixesTest`；真实 Redis 索引测试本轮未启用。 |
| L3 | 已修 | 课程检索使用独立 `tj.ai.course-retrieval.top-k` / `similarity-threshold`，默认 5/0.6；启动期校验。避免拿知识库的 3 条预算替代课程搜索语义。`CourseSemanticParametersTest`。 |
| L4 | 已修 | `RagTelemetry.StageException` 保留根因类名，failureType 使用根因分类，不输出异常 message；IOException 不再被统计为 IllegalStateException。 |
| L5 | 部分处理，旧库积压未关闭 | 清理队列幂等入队、不重置旧重试时间，单项远程/补偿失败不阻塞后项；增加其他存储身份积压告警。原 storeId 仍绑定原库，迁库后的历史队列必须连接原库核对并清理，未迁移实际数据。`MongoMemoryCleanupTest`。 |
| L6 | 已修，保持未接线 | Redis chat repository 通过一次 Lua 替换，空列表删除，SCAN 枚举；序列化失败不先删历史。配置仍拒绝启用该存储类型。`RedisChatMemoryRepositoryTest` 验证发出的脚本/调用契约，未执行真实 Redis Lua。 |
| L7 | 已修 | McpCallScope 的 budget/started 使用 volatile，先发布 budget 再发布 started；并发测试保护可见性。 |
| L8 | 已修，发布需配置令牌 | `/chat/reply` 在来源头之外校验专用令牌；缺失/错误时拒绝。Feign 专用拦截器覆盖外部旧头并发送配置令牌。`BotReplyHttpTest`；调用侧 `BotReplyFlowTest` 回归。 |
| L9 | 撤回 | 已有问题长度校验，原报告结论不成立；未重复添加另一套校验。 |
| L10 | 部分处理，生产验收未关闭 | Swagger 默认关闭；profile 可通过环境变量选择。ES URI 仍可外部配置；保留本地 dev/HTTP 默认。生产 TLS、网络隔离、Nacos namespace、认证配置尚未实际验收。 |
| L11 | 部分处理，扫描未清零 | 升级兼容补丁版本、增加可选 OWASP 门禁并实际运行 OSV 扫描。剩余 3 个组件坐标、4 个不同 advisory，详情见第 3 节。 |
| L12 | 保留设计 | legacyCourseIntake 的 `A || (B && C)` 符合既有选课流程/提示词，不为表面优先级改动产品语义；新增测试固化。 |
| L13 | 不成立 | 序号解析只认 1–3，详情卡与 AgentHistory 已限制 3；“第四门”不会走“只展示 N 门”快捷分支。回归测试固化。 |
| L14 | 已修 | ES 创建仅容忍 resource_already_exists，随后仍校验 mapping；权限、连接或其他创建错误继续失败。测试模拟双实例竞态。 |
| L15 | 已修 | CourseMaterialAccess 拼过滤表达式前限制材料 ID 为 `[a-zA-Z0-9_-]+`；原有授权拒绝保留。`RemainingRetrievalFixesTest`、`CourseMaterialAccessTest`。 |

## 3. 依赖审计：尚有匹配项

最终解析的 public Maven 运行时坐标为 **265 个**。初次 OSV 审计覆盖 266 个坐标，其中 28 个匹配；升级并对齐传递依赖后覆盖 265 个坐标，其中 3 个匹配、涉及 4 个不同 advisory。**坐标匹配数不等于已证明可利用漏洞数；缺少适用触发路径也不等于已升级到修复版本。**

补丁升级包括：Spring AI 1.1.8（MCP 0.18.3）、AIGC 范围的 Jackson 2/3 BOM、Netty、OpenTelemetry、Log4j、Tomcat、Apache HTTP、commons-lang3、Rabbit 客户端、Bouncy Castle、Hutool、MySQL Connector 和 protobuf。精确解析版本见 `runtime-dependencies-patched.txt`，不能只凭 POM 推定有效版本。

统一回归实际复现了 OpenTelemetry 升级后的 `NoSuchMethodError: okio.Okio.socket`：新版 OkHttp 与旧版 Okio/Kotlin 混用。现已对齐 OkHttp 5.5.0、Okio 3.18.1、Kotlin 2.1.21，并通过本地 HTTP exporter/observability 回归；最终 OSV 扫描使用对齐后的解析依赖。

| 匹配组件 | Advisory / CVE | 当前代码适用性与未完成事项 |
|---|---|---|
| mcp-core 0.18.3 | GHSA-8jxr-pr72-r468 / CVE-2026-35568 | 影响服务端 DNS rebinding/Origin 检查。AIGC 为客户端；可选 tj-course-mcp 已拒绝所有带 Origin 请求，并要求 Bearer 认证。保留该防护且运行其回归；SDK 升级仍需后续兼容评估。 |
| spring-webmvc / spring-webflux 6.2.19 | GHSA-j9f9-w8pj-32f8 / CVE-2026-47890 | SSE view fragments 路径。检查 AIGC/common 未发现 FragmentsRendering，当前 SSE 使用 JSON DTO。保持告警记录，待框架升级后重扫。 |
| spring-webmvc 6.2.19 | GHSA-pc63-qcmh-9cmg / CVE-2026-47884 | XsltView / 隐式通配视图路径，检查范围未发现对应使用；未声称组件版本已修。 |
| spring-webflux 6.2.19 | GHSA-9qf2-26p9-2q2q / CVE-2026-47892 | WebFlux functional endpoint header predicate 相关；检查范围未发现 RouterFunctions/RequestPredicates。未为消除坐标告警直接跨至 Boot 4/Spring 7。 |

OSV 证据：`target/aigc-review-remaining/osv-audit-patched.json` 与 `osv-advisories-patched.json`；复现脚本 `audit-patched.py`。外部数据库收录有时间和覆盖限制。

新增 `security-audit` profile 的 OWASP Dependency Check 固定版本 13.0.0，verify 阶段 CVSS≥7 或扫描错误失败，可选读取 `NVD_API_KEY`。本次 `autoUpdate=false` 尝试因本地缺少漏洞数据库失败，因此**没有 OWASP 扫描通过证据**。常规 test 成功不能替代安全扫描门禁。

## 4. 发布前必须落实的条件

1. **L8：内部令牌。** AIGC 与机器人调用服务配置同一个 `TJ_AI_INTERNAL_TOKEN`。空配置会拒绝调用；灰度发布时先安排配置同步及调用方兼容。没有生成或写入真实令牌。
2. **M11：记忆密钥与回退。** 新写入密文无法由旧二进制解密。读写实例应协调升级，回退必须使用能读取新格式的版本；旧密钥通过 `tj.ai.long-term-memory.decryption-keys` 仅用于解密，旧数据迁完前保留。没有轮换实际密钥或重加密实际数据。
3. **L5：旧 Mongo 清理任务。** 迁库前清空原身份积压；迁库后连接原 Mongo 与原 SQL 队列核对重试。禁止把历史 storeId 批改为新库身份。原库不可访问时，积压仍未解决，保留队列与告警。
4. **L10：部署配置。** 明确生产 profile、ES TLS/认证/网络、Swagger、Nacos namespace，验证配置实际生效。当前修改不代表已部署。
5. **L11：剩余依赖。** 保留适用性证据，评估兼容的框架/SDK 升级，准备漏洞库后执行 OWASP 门禁并重新审计。
6. **L2：导入边界。** 已有分片超过 512 或枚举不完整时拒绝自动处理；不同 contentHash 的覆盖仍按首期策略拒绝。真实 ES/Redis 导入、并发导入和底层部分写入故障须在隔离服务中补充验收，本地 mock 不证明分布式原子性。

## 5. 验证与复现

### 实际测试结果

| 验证范围 | 执行 | 通过 | 跳过 | 失败 / 错误 | 证据 |
|---|---:|---:|---:|---:|---|
| AIGC，排除旧 AigcContractTest | 896 | 818 | 78 | 0 / 0 | `supported-regression.log`，102 个测试类 |
| 同轮 reactor 依赖（common / api / auth-resource-sdk） | 15 | 15 | 0 | 0 / 0 | 同上 |
| BotReplyFlowTest 调用侧 | 14 | 14 | 0 | 0 / 0 | `bot-caller-regression.log` |
| 可选 tj-course-mcp 模块 | 3 | 2 | 1 | 0 / 0 | `course-mcp-regression.log` |
| 兼容性聚焦回归（与 AIGC 回归重叠） | 103 | 102 | 1 | 0 / 0 | `compatibility-regression.log` |

AIGC 的 78 项跳过原因均为显式环境开关未开启：ES 4、课程同步 2、Gate A 真实模型 1、记忆 holdout 1、记忆 OTLP 流程 8、synthetic batch/model 各 1、记忆 live flow 9、记忆真实集成 7、重启流程 2、Mongo 8、MySQL 9、向量 Redis 6、Langfuse 在线导出 1、Redis 协调集成 18。MCP 的 1 项跳过是 `mcp.live.enabled` 未设置。逐项原始原因在汇总 JSON 中。

### 旧契约测试与全量结果限制

最初未排除任何测试的运行失败，日志保存在 `full-regression-before-compatibility.log`。其中本轮造成的依赖兼容和断言适配问题均已修正，并通过上面的聚焦及广域回归。

`AigcContractTest` 另有 **9 项上下文错误**：引用 `classpath:db/schema.sql`，而当前 HEAD 从未跟踪该脚本；该测试文件本轮无修改，还引用旧 `chat_record` 表与旧 `event:delta` / `event:done` 契约。缺失脚本是源码可证明的既有阻塞；未伪造建表脚本或删除测试来使构建转绿。完整旧契约的重建尚未完成，本轮只在命令行排除该类，没有添加禁用注解或修改 POM 排除规则。因此本报告仅主张所列回归通过，**不能主张无排除全量测试通过**。

### 复现命令

以下命令在 Java 17 下执行；测试前仅在当前进程清除真实服务测试开关，避免读取已有环境配置误连真实服务。完整命令和每类计数见 `validation-summary.json`。

```powershell
$env:JAVA_HOME='D:\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
Get-ChildItem Env: | Where-Object {
    $_.Name -match '^(TJ_AIGC_TEST_|TJ_AIGC_GATE_A_LIVE$|TJ_MEMORY_|TJ_TRACE_SYNTHETIC_)'
} | ForEach-Object { Remove-Item -LiteralPath "Env:$($_.Name)" }

mvn -s C:/Users/your-user/.m2/settings.xml -pl tj-aigc -am '-Dtest=*,!AigcContractTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -s C:/Users/your-user/.m2/settings.xml -pl tj-learning -am '-Dtest=BotReplyFlowTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -s C:/Users/your-user/.m2/settings.xml -Pmcp-pilot -pl tj-course-mcp -am test
python target/aigc-review-remaining/audit-patched.py
```


静态检查：YAML 已使用 SnakeYAML 2.4 严格解析并检查关键配置层级；已确认被删除的 controller 没有残留编译 class；`git -c core.whitespace=cr-at-eol diff --check` 检查空白；无已暂存改动。最终执行结果见汇总 JSON。

验证局限：未启动真实模型、生产 MySQL/Mongo/Redis/ES；关闭环境开关后的集成测试跳过不能当作通过。没有做生产 HTTP 端到端、部署接线、真实密钥迁移或线上数据清理。

## 6. 交付位置

### 本地功能提交

以下提交按顺序依赖；本表记录四个功能提交，审查资料另作文档提交。均未推送。

| Commit | 内容 |
|---|---|
| `10a58a7` | build(aigc): 升级安全依赖并对齐 Spring AI 兼容性 |
| `64feaf0` | fix(aigc): 隔离异常记忆任务并加固存储清理 |
| `e131571` | fix(aigc): 加固检索取消与索引分片维护 |
| `7b89132` | fix(aigc): 收紧路由工具预算与聊天接口边界 |

提交拆分前后已核对全部 72 个代码、测试和构建文件的内容一致（含删除项）；提交前仅恢复三个文件的原始 LF 换行，不改变逻辑。未重新执行同一批回归，测试结果仍以第 5 节的实际运行记录为准。

`target/aigc-review-remaining/` 下的日志、扫描原始数据和汇总 JSON 为本地验证产物，未随 Git 提交；本报告保留结果、复现命令和验证限制。

- 最终源码：`D:\Java\tianji`，分支 `Final`；源码由 Codex 与 Cursor 分工完成并由主线程整体验证，随后按功能创建本地提交。
- 汇总证据：`target/aigc-review-remaining/validation-summary.json`。
- AIGC 回归（排除旧契约类）：`target/aigc-review-remaining/supported-regression.log`；最初无排除失败记录：`full-regression-before-compatibility.log`。
- 跨模块：`course-mcp-regression.log`、`bot-caller-regression.log`。
- L2 复现与修正：`l2-boundary-before.log`（修正前失败）、`focused-final.log`（修正后）；最终 AIGC 回归包含适配器级索引测试。
- 分组交付记录：`retrieval-outcome.md`、`mcp-route-outcome.md`、`memory-outcome.md`、`parent-outcome.md`、`l2-followup.md`。这些是各组交付时的状态记录，测试结论以本报告和最终汇总为准。

原始 review 文档随审查资料归档；个人 `.cursor/settings.json` 留在本地。根目录中此前解压的 `BOOT-INF/` 已移至仓库外备份，不纳入提交。
