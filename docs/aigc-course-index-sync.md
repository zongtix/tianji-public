# 课程语义索引自动维护

2026-10-23：索引快照和正文归一化已补充正式章节标题；目录变更也参与版本计算，详见 [目录检索与回答](aigc-course-outline.md)。以下同步机制继续沿用。

课程数量增加后，不需要逐门填写导入 ID。启用后，首次按正式课程主键分页扫描，后续通过课程变更消息和循环巡检维护同一个语义索引。此功能默认关闭，本轮只用隔离索引和队列验证，未在日常服务启用。

## 同步范围

- 内部接口为 `/course/semantic-index?afterId=0&size=50` 和 `/course/semantic-index/{id}`。每页最多 200 条，按长整型 ID 递增；包含正式课程软删除标记，只关联未删除的正式正文，不读取草稿。
- 状态为上架或已完结（2、4）、处于购买时间范围、未删除且名称和介绍或正文非空的课程才有可检索向量。未开始售卖、下架、过期、删除、正文缺失均改为禁用记录。
- 继续使用既有 `text-embedding-v4`、1024 维、正文归一化和内容摘要。价格等交易字段仍由推荐工具实时读取，不纳入向量。正文摘要未变时不调用 Embedding。
- 已有文档沿用原 ES ID，新课程采用按课程 ID 生成的稳定 ID。更新覆盖原记录，不因正文变化新增文档；发现同一课程有重复记录时拒绝写入。
- 正文非空不等于资料准确，本功能不自动识别事实冲突或占位文案。上轮记录的资料缺口、回答措辞和目录召回问题仍需分别处理。

## 消息、巡检与并发

独立持久化队列 `aigc.course.index.sync.queue` 订阅 `course.topic` 的 `course.up/down/expire/delete`，不会抢占搜索服务的队列。消息只提供课程 ID，每次处理都重读当前正式状态。现有删除入口只删草稿时，仍在售的正式课程会保留。

上架/重新发布和下架通知通过 `CourseEventPublisher` 在数据库事务提交后发送，回滚不发。发送异常记录错误，不把已经提交的课程操作报告为失败；语义索引由巡检补偿。这不是事务 Outbox，也没有承诺消息与数据库原子提交。

单次同步最多尝试三次，间隔 200 毫秒；每次先读 ES 版本，再读课程资料。更新使用 `if_seq_no` / `if_primary_term`，创建使用 `op_type=create`。冲突后从头重读，避免旧向量覆盖较新的禁用记录。禁用记录移除向量，`documentType=course_unavailable`，保留版本；既有检索条件 `documentType=course` 自动排除它。

消息处理耗尽重试后拒绝重新入队，由 broker 转入 `aigc.course.index.sync.failed` 交换机及 `aigc.course.index.sync.failed.queue`。失败队列不会自动无限重放；先修复依赖或资料问题，再按需重放。巡检中单门课程失败则记录并继续，下一轮再试，分页接口失败则保持游标。

默认启动五秒后扫描第一页；每次处理最多 50 门，处理结束再等 60 秒，短页或空页回到起点。重启从头扫描，未变正文跳过向量生成。课程量和模型耗时决定一轮扫描用时，不能把默认间隔当作所有课程的同步时限。

周期扫描能补偿漏消息、软删除、正文变化和购买时间变化。物理删除且通知也丢失时，业务表扫描不能发现已消失的 ID，需要按已知 ID 重放删除通知或另行核对索引；推荐工具仍会复核课程是否存在且可用。不要手动删除禁用版本记录，也不要同时使用旧手工导入器改写同一课程。

并发原理参考 [Elasticsearch 8.19 的乐观并发控制](https://www.elastic.co/guide/en/elasticsearch/reference/8.19/optimistic-concurrency-control.html)。失败队列原理参考 [Spring AMQP 的异常恢复机制](https://docs.spring.io/spring-amqp/reference/amqp/resilience-recovering-from-errors-and-broker-failures.html)；项目具体 API 以当前依赖编译及真实 broker 验证为准。

## 启用方式

先运行包含新增快照接口的课程服务，再运行包含同步功能的 AIGC。AIGC 通过可选 Nacos `shared-mq.yaml` 读取现有 RabbitMQ 连接资料，凭据继续放在配置中心或环境变量中。

```powershell
$env:TJ_AI_KNOWLEDGE_ENABLED = 'true'
$env:TJ_AI_KNOWLEDGE_STORE_TYPE = 'elasticsearch'
$env:TJ_AI_COURSE_RETRIEVAL_ENABLED = 'true'
$env:TJ_AI_COURSE_INDEX_SYNC_ENABLED = 'true'
```

这些设置不会自动改变已经运行的进程。可通过 `tj.ai.course-retrieval.sync.page-size`、`initial-delay-ms`、`fixed-delay-ms` 调整扫描节奏。关闭同步开关停止本功能的监听和巡检，已有向量不受影响。知识库关闭、课程检索关闭或存储为 Redis 时，本功能也不会建立。

本轮没有更新日常运行进程或配置中心开关。部署前需审查当前工作区中其他尚未交付的 Audio/Bot 改动，不能将本轮验证视为那些功能的部署验收。

## 复验与结果（2026-10-23）

```powershell
# 不连接真实 ES/MQ、不调用真实模型
./scripts/local/test-course-index-sync.ps1

# 使用现有 19201 ES 和 5672 MQ 隧道；读取 Nacos 连接资料
./scripts/local/test-course-index-sync.ps1 -WithInfrastructure
```

真实基础设施用例只使用随机名称的 `tj_aigc_sync_test_*` 索引和 `aigc.course.sync.test.*` 队列/交换机，并在结束后清理。课程资料和向量是测试替身，不调用付费模型，不向业务交换机发布测试通知，不改动现有课程索引。

最终执行带基础设施的脚本：课程服务 12 项、AIGC 46 项，共 58 项全部通过，无失败、错误或跳过，JDK 17 下构建成功。日志为 `target/course-sync-verification.log`，JUnit 报告在各模块 `target/surefire-reports/`。

- 接口边界与真实 Mapper SQL：主键游标、长 ID、正式内容、软删除标记。
- 同步边界：首次写入、接管旧文档、正文更新、重复跳过、禁用/恢复、读取失败保留旧数据、并发冲突重读、非法向量拒绝。
- 真实 ES：旧 ID/摘要兼容、禁用后检索排除、恢复、软删除、旧更新和旧创建均收到 HTTP 409、重复记录拒写。
- 真实 RabbitMQ：同一消息处理三次后进入独立死信队列，原队列没有无限重入；测试资源已清理。
- 事务与配置：提交后发送、回滚不发、发送失败不误报已提交事务、默认关闭、启用配置可组装、巡检循环与失败隔离。

初次真实 ES 验证的失败来自测试只接受 `ElasticsearchException`，实际客户端以 `ResponseException` 返回相同的 HTTP 409；已改为核对真实冲突状态后复验。这里验证的是索引维护，不代表真实模型推荐质量或购买转化率通过验收。
