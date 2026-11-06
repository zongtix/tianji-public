# 第一份 RAG 知识资料

首份资料选择天机学堂的积分与签到规则，而不是历史部署说明。正文位于
`src/main/resources/knowledge/tianji-points.md`，参考现有积分模块阅读报告，并核对了
2026-10-17 的 `PointsRecordType`、`LearningPointsListener`、`PointsRecordServiceImpl`
和 `SignRecordServiceImpl`。它是项目规则资料，未虚构在售课程 ID。

## 导入链路

`KnowledgeDocumentService` 读取 UTF-8 Markdown，按二级标题分章节，再用
`TokenTextSplitter` 按600 token 切分。每个片段保留文档标题和章节上下文。
`VectorStore.add` 使用已配置的百炼模型生成1024维向量并写入正式索引。

元数据包含 `documentId`、`title`、`source`、`chapter`、`chunkIndex`、`contentHash`
和 `embeddingModel`。`chunkIndex` 从1开始。正文的 SHA-256 用于内容版本识别；
文档 ID、内容哈希和片段序号生成稳定的 UUID，重复执行不会新增相同片段。

首次已导入 `tianji-points-guide`，共7个片段（文档说明和6个主题章节）。
原有其他文档不会被清空。相同内容完整导入后再次执行会跳过 Embedding 调用；
同内容的部分写入可以通过稳定 ID 重试补齐。已有不同内容版本时拒绝覆盖，
首期不实现文档更新、删除、并发发布和任意文件上传。

## 执行

先构建最新 jar，再显式执行一次性命令。它使用独立的最小 Spring 上下文，
不会启动 Web 服务、注册 Nacos，也不会随正常 AIGCApplication 启动自动执行。
密钥沿用已有环境变量；ES 密码读取被 Git 忽略的本机凭据文件。

```powershell
mvn -pl tj-aigc -am '-DskipTests' package
powershell -File scripts/aigc-es/import-points-guide.ps1
```

结果文件：`target/aigc-es/knowledge/points-import.json`；日志同目录。
脚本会检查相同文档的重复导入，并用3个真实问题验证检索结果。

本次三条问题的第一条命中均为预期章节：

| 问题 | 命中章节 |
| --- | --- |
| 一天学习课程最多能获得多少积分？ | 课程学习积分与每日上限 |
| 连续签到第七天一共能拿多少分？ | 每日签到与连续签到奖励 |
| points_message 的 sent 等于1就表示积分到账了吗？ | 完成学习后为什么积分可能稍后到账 |

在 Kibana Dev Tools 中可只查看来源元数据：

```http
GET tj_aigc_knowledge_v4_1024/_search
{
  "_source": ["content", "metadata"],
  "size": 20,
  "query": { "term": { "metadata.documentId.keyword": "tianji-points-guide" } }
}
```

## 聊天接入

配置层只创建检索 Advisor，`ChatClient` 不再默认挂载 RAG。
`ChatServiceImpl` 读取当前用户拥有的会话，根据创建时绑定的 `knowledgeBaseId`
决定是否检索。旧会话和未传知识库的新会话继续普通聊天，不调用百炼或 ES。

先执行一次可重复的增量迁移，再重启 AIGC 服务：

```powershell
python scripts/aigc-es/prepare-session-knowledge.py
```

迁移只新增 `chat_session.knowledge_base_id VARCHAR(64) NULL`，保留旧会话。
知识库会话在创建时绑定，后续聊天请求只传 `sessionId` 和 `question`；
不允许通过每条问题切换范围，历史列表返回绑定字段，重新打开会话时沿用。

```http
POST /session?knowledgeBaseId=tianji-points
```

外部网关调用路径为 `/ais/session?knowledgeBaseId=tianji-points`。
不传 `knowledgeBaseId` 创建普通会话。前端新增“积分与签到问答”入口，
“开启新会话”仍创建普通会话。

首期只有登录用户可读的公共库，没有开放私人知识库。
服务端 `tj.ai.knowledge.public-bases` 将 `tianji-points` 映射到
`tianji-points-guide` 文档。未知、空字符串或未发布的库直接拒绝；
每次提问都重新检查会话归属、启用状态及发布配置。
检索过滤表达式由服务端构建，客户端不能传文档过滤表达式。
未带授权范围的检索 Advisor 也会拒绝执行。
现有无元数据的 `/embedding` 写入内容不属于这个已发布知识库，不会被该库召回。

百炼将问题向量化后查询 ES，最多取3个相似度不低于0.6的片段，
追加到本次提示词交给原来的 DeepSeek。
参数可用 `tj.ai.knowledge.retrieval.top-k` 和 `similarity-threshold` 调整。
记忆 Advisor 先保存原始问题，检索资料不写入用户消息历史。
检索在 boundedElastic 执行，课程工具及流式停止流程沿用现有实现。
多轮请求沿用知识库范围；当前检索查询仍为本轮原始问题，未实现基于历史的问题改写。

- 没有相关片段：直接返回资料不足的固定提示，保存到聊天历史，不调用聊天模型补写。
- 百炼或 ES 检索失败：返回 `1005` 错误事件，包含
  `code=KNOWLEDGE_RETRIEVAL_FAILED` 和可展示的 `message`，随后发送 `1002` 结束。
  不向用户暴露底层异常，不调用聊天模型自由回答；前端读到结束事件后展示错误。
- 知识库未启用或未发布：请求被明确拒绝，不降级成普通聊天。

运行中的服务需要重启，前端需要加载最新构建，才能使用本次修改。
