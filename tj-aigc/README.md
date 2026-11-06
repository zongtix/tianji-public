# AI 学习助手与课程导购

Java 17 / Spring Boot 3.5.16 / Spring AI 1.1.8。通过 DeepSeek 兼容接口提供多轮文本对话、课程导购、知识库问答、会话管理和停止生成；语音能力见 [音频说明](audio.md)。公开版首次配置见 [配置说明](../docs/public-setup.md)。

## 能力与边界

- 普通会话由内部 ROUTE 选择 RECOMMEND、CONSULT、KNOWLEDGE 或必要澄清。路由结果不进入可见回答或聊天历史。
- 课程分支使用白名单工具查找、浏览和核实课程，工具准备最多执行八轮；准备阶段文字不展示，最终回答单独生成。纯适用条件和教学深度询问在命中既有规则时由已查询事实生成正文。价格、月单位有效期和可售状态来自课程服务，推荐提交前重新核实。
- 知识库会话由服务端绑定授权文档范围，强制进入知识分支。支持查询改写、向量/BM25 检索、RRF、可选 rerank 和上下文预算；检索失败不退回无依据回答。普通 KNOWLEDGE 分支不等于已挂载 RAG。
- 课程卡片支持详情入口；未接入代用户下单、支付、订单或学习进度查询，不应声称已完成这些操作。
- MCP 课程单详情来源默认关闭、使用 local；不向模型自动注册远程工具。两门课程的限定验收不代表全平台或浏览器验收，见 [MCP 计划与记录](../docs/aigc-mcp-integration-plan.md)。

课程语义索引与导入见 [课程检索](course-retrieval.md)，知识库授权、检索和模板见 [知识库](knowledge.md)，提示词资源及 Nacos 覆盖见 [提示词维护](prompts.md)。

## 配置与启动

使用 JDK 17，以 local profile 启动的依赖和检查入口见 [本地环境说明](../scripts/local/README.md)。默认端口 8096，服务名 aigc-service；网关 /ais/** 剥离一级前缀，外部请求沿用登录鉴权。内部端口不应绕过网关公开。

会话元数据使用 tj_aigc MySQL 数据库；消息存储支持 MySQL/MongoDB 配置选择，窗口默认最多 100 条消息，见 [消息存储](memory-storage.md)。建表与迁移按相应文档操作，应用不会自动创建业务表。NACOS_SERVER_ADDR、NACOS_NAMESPACE 和模型凭据使用现有环境配置，凭据不写入仓库。

公开版通用及 local RAG 配置均为 B、rerank 默认关闭，模板为 knowledge-v1。这是文件中的配置，不代表运行进程已加载；环境变量、Nacos 和 profile 的有效值必须在验证时核对。模型请求默认不自动重试。

## 会话、并发和取消

生成占位、续租和停止状态使用 Redis Lua/租约协调，同一会话同一时刻只允许一轮生成。停止信号可由其他实例写入，生成实例定期检查；执行工具和后续阶段也检查当前请求是否有效。删除会话与生成共用占位，权限由服务端检查。

这些机制支持跨实例协调，不等于完成了多实例容量、Redis 高可用或故障切换验收。SSE 连接和正在接收的文字仍属于当前进程，进程崩溃时尚未落库的片段不保证恢复。正常回答结束后发送卡片并保存元数据；停止、断连或文字生成失败不补发未发送的卡片。主动停止/断连保存仍有归属的非空部分回答；异常保存边界见相关测试与实施记录。

## HTTP 与 SSE

除流式和明确标注的接口外，普通响应沿用项目 R 外壳。以下为网关路径：

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| POST | /ais/session?n=3&knowledgeBaseId=... | 创建会话；知识库参数可省略 |
| GET | /ais/session?pageNo=1&pageSize=10 | 分页查询自己的会话，单页最多 50 条 |
| GET | /ais/session/{sessionId} | 查询窗口内的用户/助手消息与卡片 |
| PUT | /ais/session/{sessionId}/title | JSON { "title": "..." }，标题 1–40 字 |
| DELETE | /ais/session/{sessionId} | 删除自己的非生成中会话及消息 |
| GET | /ais/session/hot?n=3 | 获取示例问题，不创建会话 |
| POST | /ais/chat | JSON { "sessionId": "...", "question": "..." }，问题 1–3000 字 |
| POST | /ais/chat/stop?sessionId=... | 停止指定会话当前生成 |

聊天返回 text/event-stream，每个 data: 为含 eventType、eventData 的 JSON：

| eventType | 含义 |
| --- | --- |
| 1001 | 文字片段，eventData 为字符串 |
| 1002 | STOP 终止事件，正常结束、停止和已映射错误路径均使用 |
| 1003 | 参数事件枚举；当前聊天实现未发送标题事件，标题可从会话列表读取 |
| 1004 | 课程卡片，eventData 为 { "cards": [...] } |
| 1005 | 已分类业务错误，包含 code/message，之后发送 STOP |

未知异常可能终止流，不能把“所有错误都有 1005/STOP”当作既有保证。当前请求没有客户端 requestId 幂等协议，不能沿用早期 README 中的 ready/delta/done 或重复 requestId 约束。

## 故障边界与验证

| 情况 | 当前行为 |
| --- | --- |
| 路由超时/解析失败 | AGENT_ROUTING_FAILED；不自动切成无课程依据的问答 |
| 课程语义检索失败 | COURSE_RETRIEVAL_FAILED；不作为查无课程 |
| 知识改写/检索失败或上下文超限 | 返回对应专用错误，不自由补写事实 |
| MCP 错误 | 返回分类后的 MCP 错误，不静默改来源 |
| 工具准备轮数耗尽 | COURSE_PREPARATION_INCOMPLETE 后发送 STOP，不自动提交推荐或自由作答 |
| 标题生成失败 | 保留原标题，已经完成的回答不受影响 |

本地回归覆盖路由、工具事实、卡片、SSE、记忆、租约和取消；测试替身不代表真实模型内容通过。RAG 与 Langfuse 结果及限制见 [评测说明](../docs/rag-evaluation/README.md)。课程导购后续阶段、已知问题和验收口径见 [优化执行计划](../docs/aigc-optimization-plan-2026-10-27.md)。
