# 核心设计与源码阅读路线

[返回项目首页](../README.md) · [全部文档](README.md) · [运行配置](public-setup.md)

本文聚焦本项目扩展和改进的三个方向。每条路线按业务问题、实现取舍、源码和测试组织，适合先了解设计，再沿调用链阅读代码。

## 1. AI 学习助手与课程导购

### 业务问题

同一个聊天入口需要处理学习咨询、课程推荐和指定知识库问答。课程名称、价格和有效期来自业务服务；模型需要在可用事实和用户上下文中作答。

### 实现与取舍

1. 普通会话通过分发器进入课程推荐、咨询、知识或澄清分支，每轮选择一个业务分支；显式课程事实和平台规则问题有服务端规则辅助判断。
2. 分支只挂载对应工具。课程工具准备和最终回答分阶段执行，内部路由及工具准备文字不直接进入可见回答。
3. 推荐提交前核实课程详情，返回文字和结构化课程卡片；后续提问结合会话历史处理课程指代。
4. 指定知识库会话的文档范围由服务端绑定，直接进入知识分支。检索链路支持查询改写、向量与关键词检索、RRF 融合、可选 rerank 和上下文预算。

课程工具、知识检索与用户长期记忆承担不同职责：工具读取课程事实，RAG 检索文档，长期记忆保存用户明确表达的背景和偏好。具体功能是否启用以有效配置为准。

### 源码阅读顺序

| 顺序 | 文件 | 阅读重点 |
| --- | --- | --- |
| 1 | [ChatController](../tj-aigc/src/main/java/com/tianji/aigc/controller/ChatController.java) | 对话入口、会话检查与 SSE 响应 |
| 2 | [AgentDispatcher](../tj-aigc/src/main/java/com/tianji/aigc/agent/AgentDispatcher.java) | `processStream`、路由解析、知识库强制分支与取消检查 |
| 3 | [CourseToolPreparationAdvisor](../tj-aigc/src/main/java/com/tianji/aigc/tools/CourseToolPreparationAdvisor.java) | 工具准备、执行预算和最终回答衔接 |
| 4 | [KnowledgeRetrievalAdvisor](../tj-aigc/src/main/java/com/tianji/aigc/knowledge/KnowledgeRetrievalAdvisor.java) | 授权范围校验、查询改写、检索编排与上下文预算 |
| 5 | [KnowledgeRetrievalPipeline](../tj-aigc/src/main/java/com/tianji/aigc/knowledge/KnowledgeRetrievalPipeline.java) | 向量与关键词召回、RRF 融合和可选重排 |

### 测试与验证入口

- [AgentWorkflowTest](../tj-aigc/src/test/java/com/tianji/aigc/agent/AgentWorkflowTest.java)：业务分发、工具隔离、历史、取消与异常分支。
- [CourseToolPreparationTest](../tj-aigc/src/test/java/com/tianji/aigc/agent/CourseToolPreparationTest.java)：课程工具准备及回答约束。
- [KnowledgeRetrievalPipelineTest](../tj-aigc/src/test/java/com/tianji/aigc/knowledge/KnowledgeRetrievalPipelineTest.java)：检索链路与候选组织。
- [课程工作流评测](course-workflow-evaluation/README.md)、[RAG 评测](rag-evaluation/README.md)：评测方法和历史实验入口。

模型替身测试用于验证控制流与数据边界，真实模型的回答质量需另做场景验收。课程导购目前未接入代用户下单、支付及学习进度查询。接口与能力说明见 [AI 模块文档](../tj-aigc/README.md)。

## 2. Redis 跨实例生成协调与停止

### 业务问题

同一会话的两次生成可能到达不同实例，停止请求也可能落到另一实例。旧请求结束时，还需要避免删除新一轮生成的占位。

### 实现与取舍

`ChatGenerationStore` 以会话为粒度共享生成状态，每轮生成有独立 `requestId`：

| 操作 | 原子条件与状态变化 |
| --- | --- |
| 获取占位 | Lua 检查键不存在后写入请求归属、运行状态和 30 秒租约 |
| 续租 | 仅当前请求且仍为运行状态时延长 TTL；本地已过期租约不恢复 |
| 停止 | 写入停止状态并保留占位及 TTL，由执行实例检查并取消；可按请求 ID 限定目标 |
| 完成 | 仅当前运行请求可以标记完成，与停止在同一 Redis 键上竞争 |
| 释放 | 请求 ID 匹配才删除占位，避免旧请求误删新占位 |

Redis 保存协调状态，SSE 连接和正在执行的模型流仍由当前进程持有。取消、断连和正常完成各有保存边界；进程崩溃时，尚未落库的输出不保证恢复。

### 源码与测试入口

- [ChatGenerationStore](../tj-aigc/src/main/java/com/tianji/aigc/service/ChatGenerationStore.java)：`acquire`、`renew`、`stop`、`complete`、`release` 及对应 Lua。
- [ChatServiceImpl](../tj-aigc/src/main/java/com/tianji/aigc/service/impl/ChatServiceImpl.java)：把生成状态接入模型流、心跳、停止及清理。
- [ChatRedisCoordinationTest](../tj-aigc/src/test/java/com/tianji/aigc/service/ChatRedisCoordinationTest.java)：两套服务与 Redis 连接间的占位、停止、租约过期及旧请求释放检查。需设置 `TJ_AIGC_TEST_REDIS_PORT`，并准备带测试标记的独立 Redis；未设置时跳过。
- [ChatStopMemoryTest](../tj-aigc/src/test/java/com/tianji/aigc/service/ChatStopMemoryTest.java)：停止、部分回答和生成状态相关回归。
- [AgentWorkflowTest](../tj-aigc/src/test/java/com/tianji/aigc/agent/AgentWorkflowTest.java)：路由期间停止或断连后不继续启动业务分支。

这套机制的设计目标是跨实例协调。当前公开版本的检查范围未包含多实例容量、Redis 故障切换或高可用验收。

## 3. 积分与优惠券的可靠处理

### 3.1 积分：持久化待办与排行榜补偿

**问题：** 积分明细写入数据库后，Redis 排行榜更新可能失败；消息重复投递也可能造成重复入账。

**实现：** 积分明细和排行榜同步待办在同一数据库事务中保存。定时任务处理未完成待办，重新计算用户当月积分并更新榜单，成功后标记；失败保留待重试。积分消费使用业务键及数据库唯一约束去重。签到等 MQ 待办通过持久化消息发送，在 broker 确认且未发生路由退回后标记完成。

这里有两种待办处理方式：排行榜同步直接写 Redis，签到消息发布到 RabbitMQ。重复执行与重复投递均需由业务逻辑承接。

| 阅读入口 | 重点 |
| --- | --- |
| [PointsMessageServiceImpl](../tj-learning/src/main/java/com/tianji/learning/service/points/impl/PointsMessageServiceImpl.java) | `savePointsRecord` 将明细和榜单同步待办放进同一事务 |
| [PointsMessagePublishTask](../tj-learning/src/main/java/com/tianji/learning/task/PointsMessagePublishTask.java) | 待办分流、失败保留、MQ 确认与签到位恢复 |
| [PointsRecordServiceImpl](../tj-learning/src/main/java/com/tianji/learning/service/points/impl/PointsRecordServiceImpl.java) | 用户锁、业务幂等、按月汇总和排行榜更新 |
| [积分迁移脚本](../tj-learning/src/main/resources/db/points-message.sql) | 待办表及积分业务唯一键 |

测试入口：[PointsMessageTransactionTest](../tj-learning/src/test/java/com/tianji/learning/service/points/PointsMessageTransactionTest.java)、[PointsMessagePublishTaskTest](../tj-learning/src/test/java/com/tianji/learning/task/PointsMessagePublishTaskTest.java)、[PointsFlowRegressionTest](../tj-learning/src/test/java/com/tianji/learning/service/points/PointsFlowRegressionTest.java)。

### 3.2 优惠券：可恢复的异步领取与兑换

**问题：** 请求超时后的重试、MQ 重复投递和处理进程重启，都需要继续沿用同一请求结果；发券与结果更新之间也需要事务一致性。

**实现：** 先将请求以 `PENDING` 状态持久化，再由定时任务向 RabbitMQ 发送请求 ID。消费者锁定请求行，在同一事务中完成校验、发券或兑换以及结果更新。成功后为 `SUCCEEDED`，业务拒绝或重试耗尽后为 `FAILED`。

- `(user_id, idempotency_key)` 唯一约束识别重复提交；同键改参数会拒绝，查询只允许请求所属用户。
- 未完成请求按调度时间补投，消费者处理已完成请求时直接结束。
- 请求行和券模板行锁协调重复处理、库存和限领校验；兑换码核销与发券在事务中提交或回滚。
- `PENDING` 表示已受理，库存尚未预留，最终结果仍可能因库存、限领或发放窗口变化而失败。

| 阅读入口 | 重点 |
| --- | --- |
| [CouponClaimController](../tj-promotion/src/main/java/com/tianji/promotion/controller/CouponClaimController.java) | 异步提交和结果查询接口 |
| [CouponClaimService](../tj-promotion/src/main/java/com/tianji/promotion/service/claim/CouponClaimService.java) | 请求持久化、幂等重放和用户隔离 |
| [CouponClaimPublishTask](../tj-promotion/src/main/java/com/tianji/promotion/task/CouponClaimPublishTask.java) | 扫描到期请求和 MQ 补投 |
| [CouponClaimProcessor](../tj-promotion/src/main/java/com/tianji/promotion/service/claim/CouponClaimProcessor.java) | 请求行锁、事务处理、终态和重试退避 |
| [请求表 DDL](../tj-promotion/src/main/resources/db/coupon-claim-request.sql) | 唯一键及待处理请求索引 |

测试入口：[CouponClaimTest](../tj-promotion/src/test/java/com/tianji/promotion/service/impl/CouponClaimTest.java)、[CouponClaimListenerTest](../tj-promotion/src/test/java/com/tianji/promotion/service/impl/CouponClaimListenerTest.java)。运行配置见[异步优惠券说明](coupon-async.md)，历史真实 MySQL/RabbitMQ 检查见[开发环境验收记录](coupon-live-verification-2026-10-30.md)。

该功能默认关闭，使用前需建表并显式启用。当前方案仍在数据库行锁处协调热门券发放；容量与吞吐需要单独验证。

## 验证记录如何阅读

本文链接的测试是可定位的行为说明，不表示本次文档整理重新执行了这些测试。

- [公开版本验证记录](public-release-validation.md)：公开副本实际执行过的构建、定向测试与静态检查。
- 各专题的历史联调报告：仅说明记录日期、环境和样本范围内的结果；部分原始输出未包含在公开仓库中。
- 后续在自有环境验证时，先按[公开版配置说明](public-setup.md)准备基础设施和测试身份，再选择对应模块的检查。
