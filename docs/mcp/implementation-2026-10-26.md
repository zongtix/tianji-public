# MCP 实施与验证记录（2026-10-26）

实施范围为计划 P0 和 P1 的代码及可执行验收入口，MCP 默认关闭。本次没有改生产配置、启动日常 MCP 服务、替换日常 AIGC 进程、部署或推送；P2 未实施。

最新进展：已完成 [真实模型验收与后续修复](acceptance-2026-10-26.md)，最终相关回归 198 项全部执行通过，包含真实 Redis 6.2.6 协调测试；先前两项失败已修正测试注入时机。受控范围工程闭环通过，内容验收未通过，保持默认关闭。以下保留初期实施的历史记录。

## 已实现

- 使用 BOM 管理的 Spring AI MCP Client/Server 1.1.2、SDK 0.17.0；根 `mcp-pilot` profile 管理可选 `tj-course-mcp` 模块。
- `CourseDetailReader` 保留原单详情读取作为默认分支；mcp 分支完成初始化、发现、固定来源/工具/Schema 检查和标准工具调用。模型工具 Schema、Agent 白名单和 SSE 结构保持原有行为。
- `CourseDetailContract` 位于 `tj-api`，没有 SDK 依赖；版本化文本 JSON、字符串 ID、分/月单位、null 和真正 NOT_FOUND。Server 用没有 fallback 的单 ID 批量接口区分成功空列表与失败。
- 独立只读 Server 使用服务 Token、课程 ID 白名单、回环地址、Origin 拒绝及有界并发；排除共享 API 的全量 Feign 注册、缓存和无关基础设施自动配置。
- `McpCallScope` 在每次 Agent 订阅创建；累计预算从第一次远程操作开始，仅留在本地。远程结果返回后，以及后续本地可售读取返回后检查停止、归属和预算，避免迟到核实和卡片。
- 初始化/发现共享但不保存用户状态；单请求取消订阅不关闭共享 Client。没有失败自动 fallback 或同次自动重试。
- 添加安全 MCP ERROR 分类及初始化/发现/调用 Observation。现有模型内容采集仍仅收到课程展示字段；没有把凭据或 ToolContext 放入远程结果/meta。
- 完整工具链测试证实默认 `ToolCallingManager` 会把工具异常转为模型文本，因此在现有准备 Advisor 中仅将 MCP 受控异常和取消列入重抛名单，让其到达 SSE 映射并阻止后续回答；其他本地工具继续使用框架原默认处理。
- [运行说明](README.md)、[验收脚本](../../scripts/mcp-evaluation/run.ps1) 与 [Agent 待执行问题集](../../scripts/mcp-evaluation/cases.json)。

## 实际验证

使用 `D:\Java\jdk-17`，没有升级框架。Client 与可选 Server 已分别编译；实际依赖树见根 `target/mcp-dependency-tree.log`。

可选 Server 已成功打包为 `tj-course-mcp/target/tj-course-mcp-1.0.0.jar`，打包记录见根 `target/mcp-package.log`；已核对可执行归档入口为 `CourseMcpApplication`，未部署。

定向测试覆盖真实 SDK 与本机 HTTP 测试 Server 的 initialize/list/call、长 ID、单位/null、明确不存在、版本/ID/Schema/JSON/类型错误、响应过大、超时、来源准入、共享初始化、取消、并发上限、初始化失败后独立请求恢复、后续本地读取时停止，以及模型替身驱动的 CONSULT 和关键词推荐完整工具调用链。独立 WebMVC Server 测试使用真实 MCP + Feign HTTP 和课程替身，校验认证/Origin/白名单及上游异常。

最终汇总以根 `target/mcp-evaluation/client-regression.log`、`server-protocol.log`、`live-service.log` 为准。先前一次并行 Maven 构建误复用了同一 target，出现测试 ClassNotFound；这份失败日志不作为业务结果，最终验收改为串行执行。临时 Redis 的检查单独记录于根 `target/mcp-redis-test.log`，不把缺少环境而跳过的用例算作通过。

最终串行验收：AIGC 定向回归 197 项，执行 180 项全部通过，17 项未在该进程配置测试 Redis 而跳过；独立 WebMVC Server 协议/认证测试 2 项通过；真实课程服务 MCP 对照测试 1 项通过（覆盖两门现场课程）。以上共 183 项实际执行通过；另行执行的 Redis 结果如下，不并入“全部通过”。工具异常传播修复后，额外的 56 项定向回归也已通过，最终串行验收包含该修复。

补跑独立临时 Redis（本机二进制 3.2.100）时，17 项中 15 项通过、2 项等待条件超时：`oldStreamCleanupCannotRemoveNewStreamAfterLeaseExpires` 和 `redisRenewalFailureCancelsModelAndReleasesSlot`。进一步将本次涉及聊天取消路径的 `AbstractAgent`、`ChatServiceImpl` 替换为 HEAD 源码编译的隔离字节码，仅对这两项运行对照，仍在相同行超时。见根 `target/mcp-redis-baseline/baseline-tests.log`。此结果表明这两项在未包含本次相关路径变更时也失败；不是完整 HEAD 工程或 Redis 8 的全面基线验收。本次未扩展修复其既有取消/测试时序问题。对照后恢复了全部临时替换的构建类，临时 Redis 已关闭，工作区源码未被基线对照替换。

## 真实服务证据

2026-10-26 首轮 17:25、最终复验 19:15（Asia/Shanghai）通过临时随机端口的 WebMVC MCP Server → 实际 Feign → 本机 `course-service:8086`，与同源单详情和批量详情逐字段对照，两门课程均一致：

| 课程 ID | 现场名称 | 目录标题数 | MCP 文本字节数 |
| --- | --- | --- | --- |
| `1589888774267072513` | Java程序员的前端课程 | 11 | 1441 |
| `1589905661084430337` | 可能是史上最全的微服务技术栈课程 | 5 | 1253 |

证据位于 `tj-course-mcp/target/mcp-evaluation/live-service.json`，重复执行会更新最新时间与耗时。测试凭据在进程内随机生成，结束后临时 Server 关闭；没有改课程资料。样本不足，不据此声称稳定性能、成本收益或全目录容量。

## 完成边界

协议层和上述真实服务字段对照已验证。Agent 调用链采用模型替身，不能标记真实模型验收完成。本轮检查了进程/用户环境中的模型凭据及当前 Nacos `tj.ai.api-key` 可解析性，未取得可用凭据，因此未发起真实模型请求，也未完成 local/mcp 的真实模型对照或 Langfuse 实际导出样本检查。

后续前置检查更正：此前遗漏了 Machine 环境作用域；现已找到系统级 `DEEPSEEK_API_KEY`，使用它只读访问 `https://api.deepseek.com/models` 认证成功，配置中的 `deepseek-flash` 在返回列表中。可以只为测试进程临时加载，用户无需重新配置 Key。此检查不包含计费生成请求或余额校验，真实模型端到端验收仍待执行。现有 Nacos、搜索、课程和日常 AIGC 端口均在监听，候选隔离端口 18096/18097 当前空闲；端口检查不等于完整依赖健康验收。

取消保证是停止本地等待和后续状态处理；未证明远程方法立即中断或发送协议取消。客户端字节上限在已解码 JSON 文本层执行，未给 SDK 网络接收额外增加总字节硬上限；本期仅连接受控 Server。并发上限 4、5 秒操作超时和 10 秒共享预算是保守试点默认值，未进行容量压测。

回退仅需关闭 MCP、选择 local 并重启；不涉及数据迁移或索引重建。当前代码可审阅和运行受控试点，生产启用与真实模型验收仍需按计划完成。
