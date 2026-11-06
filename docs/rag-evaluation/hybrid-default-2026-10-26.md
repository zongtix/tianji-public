# 知识库默认启用混合检索（2026-10-26）

后续更新：用户明确授权后，本地local配置已进一步启用模式C（混合检索 + 专用rerank），真实追踪验证成功，见[rerank接入记录](rerank-connection-2026-10-26.md)。本文保留此前模式B交付过程；通用配置仍默认B。

将 `application.yml` 和 `KnowledgeRagProperties` 的默认模式从 A 改为 B：向量检索 + BM25 + RRF 融合。模式 A 继续可显式配置，用于回退。保留原向量 topK=3、阈值0.6、最终上下文上限3和 knowledge-v1 回答模板。

依据为已归档的 `results/summary.json`：32个积分知识问答样例中，29个有证据标注的样例平均证据召回率由83.3%变为98.9%，回答来源初审失败由6例变为1例，总耗时中位数由2157ms变为2388ms。独立人工复核仍待完成，这些数字不能推广为课程导购准确率或生产性能保证。

本次不把未执行的 C/D 实验算作成果：专用 rerank 仍未启用；额外上下文筛选也未启用。Langfuse 沿用现有接入。课程语义检索不经过知识库检索管线，本次没有变更课程检索。

新增默认双路检索测试，验证向量和关键词候选融合、授权过滤、相同证据去重排名以及不调用 rerank；纯向量路由和模板测试显式固定 A，避免将不支持关键词检索的测试存储当成真实混合检索。相关7个测试类共41项通过，0失败、0错误、0跳过。命令：

```powershell
$env:JAVA_HOME='D:\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -s "$env:USERPROFILE/.m2/settings.xml" -o -pl tj-aigc -am '-Dtest=KnowledgeRetrievalPipelineTest,KnowledgeAnswerPromptTest,KnowledgeKeywordSearchTest,KnowledgeContextSelectorTest,KnowledgeChatRoutingTest,RagPreparationLifecycleTest,RagObservabilityTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

回归日志：`target/rag-hybrid-default-20261026.log`。本次未重跑全量外部模型评测，以上质量数字来自既有归档结果。

新增启动日志输出实际模式、模板版本和 rerank 开关，不输出凭据。日常实例运行核对记录见 `target/langfuse-local/hybrid-default-20261026-*.json/log`。

本地8096已从原PID 24072更新为PID 26512，启动时间为2026-10-26 11:53:42；启动日志确认 `mode=B, promptVersion=knowledge-v1, rerankEnabled=false`，健康状态UP。沿用现有Langfuse启动脚本，启动时从已有用户环境变量继承所需凭据，不将凭据写入仓库；未修改Nacos。回退需要将两处默认模式恢复A并重启，单改磁盘配置不会切换现有Advisor实例。

实际发送一个知识库问题：“课程学习当天已经拿满50分，还能通过签到获得积分吗？”回答明确可以，并引用资料中按类型分别计算上限的规则；额外提及到账延迟，仍有表达冗余。只做这一个真实问题，不等同于全量质量验收。首次脚本将删除会话的空响应解析为JSON而未写报告，后从该请求的应用日志恢复回答，并单独确认专用测试身份会话总数为0。记录见 `target/rag-hybrid-default-20261026-live.json`。没有重跑模型请求来补报告。
