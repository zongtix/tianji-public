# @天机小助理

问答页输入 `@` 并选择天机小助理（或点击输入框下的 `@天机小助理`），再填写问题并提交。
普通评论、单纯粘贴昵称均不会触发模型。第一版支持回答和回答下的评论，不在“新建问题”后自动回复。

## 启用

1. 在 `tj_learning` 库执行同目录 `schema.sql`，只创建机器人任务表。
2. 确认 `tj_user.user` 中的 `9999` 是专用机器人账号；若不是，使用实际机器人账号 ID 配置 `TJ_AI_REPLY_USER_ID`。
   请勿把普通学员账号指定为机器人。模型调用身份使用发起评论的学员，发布回复的作者使用机器人账号。
3. 给 learning-service 设置 `TJ_AI_REPLY_ENABLED=true` 并重启；也可在该服务的 Nacos 配置中设置 `tj.ai.reply.enabled=true`。
4. 重启使用此代码构建的 learning-service、aigc-service，更新配套 `D:\Java\tj-protal` 前端。
   DeepSeek 继续使用 aigc 已有的地址、模型与密钥。

默认关闭，避免旧数据库尚未建表时影响现有问答。关闭后普通评论正常保存，已提交任务保留。
本目录未自动执行数据库迁移，也未发布运行中的服务。

## 流程与边界

- `POST /replies` 增加可选 `mentionedUserIds`，最多一个 ID；响应增加 `{id, botStatus}`。
- 评论与 `bot_reply_task` 在同一事务提交，后台每 2 秒扫描已提交的 PENDING 任务。
- 独立调度线程处理模型请求；数据库原子领取确保同一 source_reply_id 只调用一次。
- `POST /chat/reply` 仅供 Feign 内部调用。复用现有 DeepSeek ChatModel，独立 JSON 上下文，不使用会话记忆、RAG 或工具。
  系统提示词独立保存在 `tj-aigc/src/main/resources/prompts/bot-system-message.txt`，按 UTF-8 加载。
- 上下文最多包含：原问题标题 254 字符、原问题描述 2000、所属回答 3000、目标评论 2000、本次评论 2000。
  保留 Java 泛型、HTML 示例等代码原文；回答以纯文本安全展示。
- 模型输出最多 768 tokens，最长等待 30 秒；Feign 最长读取 40 秒，不自动重试。
- 机器人作为原回答下的新评论，target_reply_id 指向本次召唤评论；不发学生积分，不递归触发生成。
- 当前用户只能查询自己的 `GET /replies/bot/status?sourceReplyId=...` 任务；隐藏/删除讨论不返回回答正文。
- 生成前后都会检查问题、回答和被引用评论的归属与可见性。
- 状态：PENDING → PROCESSING → COMPLETED / RATE_LIMITED / FAILED / SKIPPED。
  进程中断遗留的 PROCESSING 在 5 分钟后标记 FAILED，**不自动重试**，因为无法确认远端是否已计费。
  PENDING 在服务重启后继续处理。需要再次提问时，由用户重新明确 @，创建新的评论。

默认预算（可通过 `tj.ai.reply` 配置修改）：每用户 60 秒一次、每用户每天 20 次、全站每天 200 次，日期按上海时区。
Redis Lua 原子检查并占用三项额度。Redis 不可用时停止模型调用；调用失败仍占额度，避免故障重试产生不受控费用。
这是调用次数和输出长度限制，**不是人民币金额的精确预算**。开启前可根据真实使用量调整。

## 验证

使用 JDK 17 和项目 Maven settings：

```powershell
mvn -s "$env:USERPROFILE/.m2/settings.xml" -pl tj-learning,tj-aigc -am `
  "-Dtest=BotReplyFlowTest,BotTextServiceTest,BotReplyHttpTest,ReplyLikeRegressionTest,ReplyExistenceHttpTest,PointsMessageTransactionTest,ChatSessionHistoryTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

配套前端运行 `node --test scripts/bot-mention.test.mjs` 和 `npm run build`。
H2/模型替身测试验证功能与事务边界，不代表已经验证真实 DeepSeek 回答质量或线上部署。

本次本地验证（2026-10-22）：上述 7 个测试类共 62 项通过；前端 5 项提及逻辑检查、Vite 构建通过。
Playwright 隔离接口验证通过：粘贴昵称不触发、明确选择后携带机器人 ID 与正确讨论位置、
轮询完成并显示回复、删除提及后清除 ID、提交失败显示原因并保留草稿、快捷按钮和 Enter 选择。
构建只输出到后端仓库的 `target/qa-bot-final-frontend-dist`，未覆盖原部署产物。
