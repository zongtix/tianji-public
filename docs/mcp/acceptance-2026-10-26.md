# MCP 真实模型验收（2026-10-26）

后续资料投影修正、回答复验及候选范围失误记录见 [内容修正记录](content-fix-2026-10-26.md)。本文保留首次工程验收时的结果。

结论：**两门课程受控范围内的 MCP 工程闭环通过；整体内容验收未通过，不启用到日常客服。** 已修复实测发现的连接状态复用、预算检查开销和停止竞态，实际 Redis 协调回归已全部通过。原全目录推荐被白名单拒绝的失败样本保留，不改写为正向成功。

## 环境与范围

- 同一份 AIGC 构建启动 local（18096）和 MCP（18098）实例，MCP Server 为 18097，18100 为本机验收代理/OTLP 接收器。均监听回环地址，两个 AIGC 实例不注册到 Nacos；检查结果仅有原 8096 实例。
- 真实模型为系统配置的 DeepSeek `deepseek-flash`。真实 Key 仅提供给验收代理进程；应用使用临时代理凭据。MCP 服务 Token 随启动生成，不写文件或命令行。
- 两侧使用相同 Nacos 提示词来源和资源模板。知识库、课程语义检索和索引同步在两侧均关闭，以隔离单详情来源；关键词搜索、课程服务、聊天记忆、SSE、路由与工具准备均走实际实现。这不等于已重验 RAG 或浏览器/网关。
- 两门批准课程为 `1589888774267072513`、`1589905661084430337`。快照及输出卡片与真实课程服务核对，价格为分、有效期为月。未修改课程资料或索引。
- 初始化/发现/调用共享整轮预算。实测原 10 秒在冷连接或双课查询时不足；整轮预算调整为 20 秒，单操作仍为 5 秒。新功能仍默认关闭。该数值是试点默认值，不代表容量或 P95 验收。
- 两个 JVM 的工具数组枚举顺序曾不同，Schema 定义集合相同；保留顺序记录，比较前仅对 Schema 集合规范排序，没有改模型收到的工具定义。单例对照不用于宣称速度或 Token 优势。

## 实际结果

| 场景 | 结果及证据边界 |
| --- | --- |
| 指定课程详情，local/MCP 各一次 | 工程检查通过；MCP 实际调用 1 次；卡片与历史一致。回答内容有下述目录缺失误述 |
| 两门课比较，local/MCP 各一次 | 工程检查通过；MCP 实际调用两门详情；两张卡片与真实服务一致。MCP 回答有下述前置要求弱化 |
| 指定试点课程的关键词推荐，local/MCP 各一次 | 两侧均真实执行 `searchCourses → queryCourseById → recommendCourses`，卡片、价格、有效期、历史通过 |
| 问候，local/MCP 各一次 | 均无详情工具、无 MCP 调用，没有声称使用 MCP |
| MCP 调用延迟 7 秒 | 使用真实模型选工具，返回 `MCP_TOOL_TIMEOUT` 与 STOP，无答案、无卡片；总请求耗时还包含模型路由和准备 |
| MCP 等待中用户停止 | 最终构建只返回 STOP，无迟到答案/卡片；从停止请求到流结束约 0.724 秒 |
| 停止后另一请求 | 实际再次调用详情 MCP 并成功出卡，证明未为停止一个请求关闭共享 Client |
| 输出中停止 | 部分回答与历史一致，历史 `generationStatus=stopped` |
| 真实停止本次 MCP Server | MCP 分支返回安全 `MCP_TOOL_UNAVAILABLE` 与 STOP；同一环境 local 分支仍成功查询 |
| 超出课程白名单 | 原较宽泛推荐问题实际选到白名单外课程，被 `MCP_TOOL_FORBIDDEN` 拒绝；不扩大权限。该问题不计正向推荐通过 |

正向推荐后来明确限定《Java程序员的前端课程》，local/MCP 用同一修改后的问题成对验证。首次宽泛推荐的失败和修改后的受控正向是不同实验，不合并为“全平台推荐已通过”。

字段缺失、明确不存在、下架/过期不出卡片仍有协议或本地工具回归覆盖；本轮没有为这些场景修改真实课程数据，也没有补跑对应的受控模型样本。它们不计入本轮 8 条受控范围正向真实模型记录。

## 本轮修复

1. 已验证的共享连接不再每次详情都重复进入初始化/发现等待与观测；`remainingNanos()` 仅计算时间，不额外重复执行 Redis 归属检查。调用和状态写入边界仍检查 active/ownsGeneration，累计预算不重置。
2. 为 MCP 本地取消使用专用 `CancelledException`。实测停止标记先于原心跳生效时，原异常会穿出 SSE 首包并导致 HTTP 500；现在识别该生命周期信号，经原有占位清理后输出 STOP，不把它作为业务失败。只识别该本地信号，不吞掉其他服务异常。
3. 两项历史 Redis 失败在独立 Redis 6.2.6 上重现。原因是测试在模型订阅前就阻塞/破坏了 `renew`，却等待尚不存在的模型订阅被取消。测试改为模型已订阅后注入心跳故障；不改变生产 Redis 算法。

最终相关回归 **198 项执行、198 项通过、0 跳过**，包含实际 Redis 6.2.6 的 17 项协调测试。证据：根 `target/mcp-evaluation/redis-before.log`、`redis-after.log`、`redis-final.log`。匹配业务版本的临时容器、测试标记、SSH 隧道均单独创建并清理，未对业务 Redis 设置测试标记。

## Trace、用量与内容边界

本机接收器收到实际应用 exporter 的 OTLP。成功、超时、取消、不可用场景的 MCP 子观测均已结束，父链可追溯到各自 `chat.request`。只记录到本轮实际出现的用量，不把缺失数据估算为费用。

又将采集的专用测试 trace 重放到现有配置的 Langfuse，并通过 Observations API v2 读取验证：

- 成功详情 trace `8cd5d038a4e84de2f12a86ea55a6803c`：23 个观测，包含 3 个 MCP 子观测。
- 停止 trace `f1b08fab48278655602da4c54a729fe0`：11 个观测，包含 1 个 MCP 子观测。

这是“应用 exporter → 本机接收器”和“同一 OTLP 内容重放 → 配置中的 Langfuse”两段验证，没有修改日常 exporter 的目标。首次使用旧 trace 读取接口得到 404；依据 [Langfuse 官方 API 说明](https://langfuse.com/docs/api-and-data-platform/features/public-api) 改用 v2、带 traceId 和时间范围后已读到实际观测。没有据 HTTP 200 接收状态直接宣称可查询。

本轮未观察到 `notifications/cancelled`；延迟的只读 MCP 请求仍可能在本地停止后完成。已验证本地停止、迟到结果丢弃及共享 Client 隔离，不能声称远程方法被即时中断。MCP meta 未携带用户身份、推荐状态或完整 ToolContext。

## 内容审阅：未通过

以下为 Codex 对照服务资料的来源初审，不是独立人工复核：

1. **目录被裁剪却说资料缺失。** 详情问题只问适用人群，工具按原范围规则省略目录；真实课程仍有 11 条目录。local 回答“目录也未提供”，MCP 回答“没有提供目录内容”，均会把投影省略误说成资料缺失。
2. **微服务前置要求被弱化。** 真实适用人群要求 Java 基础，并熟练使用 SpringBoot、MyBatis。MCP 比较回答虽先列了两项框架，结尾又说“如果你已会 Java 和 SpringBoot，两门都符合人群要求”，遗漏 MyBatis。
3. 推荐回答仍主动展开 Vue/React/jQuery 的未问说明。当前课程原介绍确有这些声明，因此不直接记为虚构；但与用户 HTML/CSS/JavaScript 目标的表达范围不符。测试问题中的“完整课程名”也会触发已有 `DetailScope` 的宽泛“完整”匹配，值得独立修正后复验。

这些问题在课程资料投影/提示词/最终回答层，不作为 MCP 传输收益或损害的因果结论。本轮不改提示词来凑验收通过。下一步优先修正省略字段语义、明确前置要求的不可弱化约束及目标范围，再用相同资料与问题集复验；之后决定日常启用。

## 证据与清理

根 `target/mcp-evaluation/live/` 保存：

- `core-report.json`：原正向详情/比较及宽泛推荐失败；`recommend-report.json`：限定试点范围推荐和问候的成对记录。
- `faults-report.json`、`down-report.json`：最终构建的故障、停止、保存和真实停机结果。
- `compare-before-guard-fix.json`、`cold-before-budget-tuning.json`、`stop-race-before-fix.json`：修复前失败，不覆盖为通过。
- `model-calls.jsonl`、`mcp-starts.jsonl`、`mcp-calls.jsonl`、`spans.jsonl`、`trace-audit.json`：实际调用、供应商用量和父子链。
- `langfuse-mcp-detail.json`、`langfuse-mcp-stop-wait.json`：后端实际可查询证据。
- `cleanup-report.json`、`isolation-check.json`、`credential-scan.json`、`final-state.json`：会话、注册、凭据和进程状态。

早期验收器沿用了旧删除接口，已修正为当前 `DELETE /session/{sessionId}`，并补清理遗漏会话。累计核对本次记录的 25 个专用会话，最终专用用户会话为 0。所有隔离进程和测试端口已关闭；Nacos 中日常 AIGC 仍只有 8096，未部署、推送或启用 MCP。
