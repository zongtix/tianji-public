# 知识库 RAG 固定评测与运行说明

2026-10-30 新增积分＋优惠券的独立评测批次，冻结资料、实际片段映射、A/A′/B/C 配对运行方式见 [多领域评测协议](multi-domain-protocol.md)。该批使用独立 `tj_aigc_eval_` 索引，不修改原积分知识库或正式聊天的授权配置。优惠券问题来自 AI 依据源码设计，独立人工复核仍待完成；不能将其称为真实用户数据或独立盲测集。历史单文档结果保留原有口径。

本批 [44 题、四组真实对照结果](points-coupons-2026-10-30.md) 已归档：候选证据召回从 A 的 87.28% 到 A′/B/C 的 100%，B/C 最终标注证据覆盖为 99.12%。AI 审阅仍发现 C 的一条越界推断；不将检索覆盖等同于回答准确率。

2026-10-31 课程讲义闸 B：[results/materials-20261031/](results/materials-20261031/) 在隔离索引 `tj_aigc_eval_materials_20261031` 上完成同讲义 A/A′/B/C；20 道有标注题的最终上下文覆盖约为 A 74% / A′ 93% / B 86% / C 91%。这是讲义语料上的算法对照，不能用积分+优惠券成绩背书，也不能替代闸 A 工具路径验收。

**最终收口（2026-10-26）：本轮RAG优化结束，保留本地C＋knowledge-v1，候选模板不默认启用，不再追加模型评测。** 完整结论、各批结果、配置和遗留项见[最终优化报告](../aigc-rag-optimization-report-2026-10-26.md)。以下按时间保留阶段记录，阶段中的“待继续”描述不构成重新启动本轮优化。

日期：2026-10-24。实施依据是 `../aigc-rag-optimization-plan.md`。本目录的案例来自已核实的积分规则，不是线上用户日志，也没有以演示课程补造业务事实。

2026-10-26后续8次真实对照：[原版v1与精确候选v5](precision-2026-10-26.md)。本批确定程度、引用和无答案边界初审通过；表达聚焦由1/4变为3/4，points-04仍主动补充未问资料缺口。候选输出Token减少但输入增加、首字未改善；候选不默认启用，C＋knowledge-v1保持。64项相关测试通过，结果单独归档至`results/precision-2026-10-26/`。

2026-10-26回答修正与耗时分析：[6题原版/聚焦候选、共12次真实对照](focus-and-latency-2026-10-26.md)已完成。候选减少未问资料缺口及输出Token，但仍有无关补充、过强风险描述及一处章节引用错误，暂不默认启用，回答质量调优尚未收口。分类模型计时显示本批耗时主要在回答生成，暂不改检索并发；C＋knowledge-v1保持。

2026-10-26后续验收：[当前C模式32题真实验收](c-acceptance-2026-10-26.md)已完成，31题实际重排成功、1题直接澄清，无调用错误或降级。核心结论来源初审32/32通过，仍有4题表达警告，独立人工复核待完成。保留C、knowledge-v1和3片段上限，本轮只归档结果，没有修改业务代码或默认配置。新增结果在独立的`results/c-acceptance-2026-10-26/`，不覆盖原历史实验。

2026-10-26最新状态：本地local配置已启用模式C（混合检索＋专用rerank），真实追踪确认执行成功。新增[6题B/C真实对比](rerank-comparison-2026-10-26.md)：核心结论来源初审通过数5/6→6/6，耗时中位数1993→2562.5ms，保留C；小样本结果不能当作全量准确率。通用配置默认B，回答模板仍为knowledge-v1。评测脚本独立指定模式，不切换日常服务。

2026-10-25历史记录：两例质量缺口的后续本地修正及三组单变量实验见 [本地修正记录](local-fixes-2026-10-24.md)，6例真实对照结论见 [2026-10-25复测记录](focused-live-2026-10-25.md)。当时真实复测未证明v2稳定获益，并出现缺少关键依据时的肯定推断，源码默认恢复为 `knowledge-v1`；`knowledge-v2-conditions` 及context4保留为显式实验选项。当时检索默认A且未部署本轮修正。原128例结果仍属于当时的v1运行。

## 资料与实验边界

`cases.json` 固定 32 例，前 8 例标为 tuning，其余为 validation。每例包含问题、必要历史、授权文档、版本化参考片段、预期要点、禁止事实和无答案要求。数据很小、同源，结果只能说明这份积分资料的表现；不能外推到全部课程或通用业务知识库。权限越界、版本冲突和服务异常另用标明的合成技术夹具验证。

`configs.json` 冻结 A、A′、B、C、D 和 B_CONTEXT 的参数。A 保留原 topK=3、阈值 0.6 与原片段拼接；A′ 仅改为 topK=6、阈值 0.45。B 当前冻结继承 A，增加 BM25 和应用层 RRF，最终选择上限为 3。C 在 B 上增加专用 rerank；D 在 C 上增加完整片段预算选择。B_CONTEXT 是 B 加上下文处理的独立补充实验。普通 rerank 故障回退不计为 C/D 正常实验。

公共的权限、有效性检查、准备超时及最终上下文超限保护适用于所有模式。A/A′ 不应用新去重或预算选片算法；放不下原始完整请求时明确报错，不悄悄删历史或截断问题。

## 运行

使用 JDK 17，并先运行本轮针对性测试。评测入口直接调用生产 `KnowledgeRetrievalAdvisor`，不另写检索逻辑。每个案例使用新 conversation ID 和一份独立的固定历史；不写聊天记忆，不写业务数据库。默认只读已有隔离索引；仅 `-InitializeCorpus` 可向**全新** `tj_aigc_eval_*` 索引写入语料。首次访问时确认目标索引已经存在。

**数据集陷阱：** 不带 `-Dataset` 时默认读 `cases.json`，其中的生产 index 名会被拒绝，不会静默落到 Nacos 生产索引。正式跑必须 `-Dataset` 指向冻结的 `tj_aigc_eval_*` 数据集（如 `prepare.ps1` 输出或 `results/<batch>/dataset.json`）。

```powershell
./scripts/rag-evaluation/run.ps1 -Mode A -Answers -Dataset target/rag-optimization/<batch>/dataset.json -RunGroup <batch>
./scripts/rag-evaluation/run.ps1 -Mode APRIME -Answers -Dataset target/rag-optimization/<batch>/dataset.json -RunGroup <batch>
./scripts/rag-evaluation/run.ps1 -Mode B -Answers -Dataset target/rag-optimization/<batch>/dataset.json -RunGroup <batch>
./scripts/rag-evaluation/run.ps1 -Mode C -Answers -Dataset target/rag-optimization/<batch>/dataset.json -RunGroup <batch>
```

不带 `-Answers` 只评检索；有必要历史时仍会实际调用生产查询改写模型，不能把它当成完全离线命令。`-Only points-01,points-31` 可选指定案例。程序从现有 Nacos 与受支持的系统环境读取配置，凭据只经子进程标准输入传递；Java 参数文件只有类路径和公开文件路径。临时结果位于 `target/rag-optimization/`，请不要把原始运行日志或配置复制进版本管理。

新运行写入 `target/rag-optimization/runs/<experiment>-<time>/`，保留原始结果。`-Experiment control/conditions/context4` 选择 `local-fix-experiments.json` 中的固定B模式快照；默认current使用当前源码默认设置。完全离线的固定候选回放使用 `./scripts/rag-evaluation/replay.ps1`，不调用模型或重新搜索。两种运行入口的证据含义不同。

2026-10-24 用户已明确批准：向 `https://maas.qianwenaiapi.com/compatible-mode/v1/embeddings` 发送固定案例检索问题，向 `https://api.deepseek.com/chat/completions` 发送冻结系统提示词、必要案例历史及授权积分片段，A/A′/B/B_CONTEXT 每种最多 32 例。本轮许可不包含把任意新资料或普通用户会话上传到其他服务。重新扩大样本、改变服务地址或发送范围前应单独核对授权。

## 专用 rerank

配置 `tj.ai.knowledge.rag.rerank.enabled/endpoint/api-key/model/timeout/input-limit/output-limit/max-item-tokens/max-request-tokens`。默认接口为 `https://maas.qianwenaiapi.com/api/v1/services/rerank/text-rerank/text-rerank`，模型为 `qwen3-rerank`。优先使用独立的 `TJ_AI_RERANK_API_KEY`，未设置时复用 `DASHSCOPE_API_KEY`；该同服务商凭据已于2026-10-26用公开通用示例验证返回200。endpoint 使用供应商原生文本排序完整地址，返回 `output.results[].index/relevance_score`，正文始终从输入候选取回。默认不设相关性阈值。

本地 `application-local.yml` 已在用户授权后配置模式C与重排开关true，用local配置重启即可保持混合检索后执行专用重排。其他环境默认仍为B且重排关闭，可用 `TJ_AI_KNOWLEDGE_RAG_MODE=C` 与 `TJ_AI_RERANK_ENABLED=true` 显式启用；不能将本机的数据发送授权推广到其他环境。真实知识库激活和Langfuse成功证据见[接入记录](rerank-connection-2026-10-26.md)。评测脚本仍要求显式提供 `TJ_AI_RERANK_API_KEY`、`TJ_AI_RERANK_ENDPOINT`、`TJ_AI_RERANK_MODEL` 才运行 C/D，运行前可在进程环境中填入已验证的同服务商配置，不向文件写入凭据。Token 限制使用估算并可按实际模型调整；超限不会截断正文，会在请求仍有效且预算允许时回退融合候选。原 C/D 实验未运行的历史记录保留。

协议核对参考：[当前千问服务商的 DashScope 重排序 API](https://platform.qianwenai.com/docs/api-reference/rerank/dashscope-rerank)。文档与地域模型会变化，启用前需以实际获授权工作空间为准；聊天或向量模型可用不代表 rerank 可用。

## Langfuse

2026-10-26 新增[首字/回答完成计时、健康 trace 过滤与评分基线](langfuse-baseline-2026-10-26.md)。固定合成场景的协议检查、真实耗时和来源初审已接入原生 Scores；独立人工复核仍待完成，自动模型裁判未启用。

接入复用 Spring AI 1.1.2、Micrometer 和 Boot 的 OTel bridge。手工 embedding、ES/Redis 向量存储均接入可用的 ObservationRegistry。业务阶段只补一次观测，不重复包装模型调用。Reactor 自动传播通过 `spring.reactor.context-propagation=auto` 启用；整轮聊天由 `chat.request` 覆盖路由、工具准备、RAG、回答和标题生成，通过 `langfuse.session.id` 关联同一会话。每轮 RAG 另记录自己的请求 ID。高基数候选身份和实验信息只放 trace 属性。

通用配置使用以下环境变量，指向已有、获授权实例：

```text
TJ_AI_LANGFUSE_ENABLED=true
LANGFUSE_OTLP_TRACES_ENDPOINT=https://<instance>/api/public/otel/v1/traces
LANGFUSE_PUBLIC_KEY=<outside-repository>
LANGFUSE_SECRET_KEY=<outside-repository>
```

通用配置默认关闭 exporter。本机 `local` profile 还会自动读取用户目录的 `.tianji/aigc-langfuse.properties`，配置和迁移步骤见[日常启动说明](../../scripts/local/README.md#aigc-的-langfuse-本机配置)。环境变量可覆盖文件中的默认值，无需依赖临时 BAT 脚本。启用时使用 OTLP/HTTP、Basic Auth 和 `x-langfuse-ingestion-version: 4`。Boot 的异步 exporter 队列上限为 1024、批量上限 128、发送间隔 5s、导出超时 2s。SSE 输出不等待 flush。Actuator HTTP 仅暴露 health。

当前仓库已开启 `spring.ai.chat.observations.log-prompt/log-completion`。`RagObservabilityConfig` 由 `log-prompt` 控制，将模型实际 prompt/completion 写入 `langfuse.observation.input/output`，每项最多 8000 字符；这覆盖模型消息文本、assistant 的 tool_calls 及实际工具返回，不是完整 HTTP 请求体，长内容会截断。业务阶段仍只传身份、排名、估算 Token、降级及状态，错误埋点不带原始异常响应体。框架已有模型标识、耗时和实际返回用量继续使用，拿不到的用量标为 unavailable。关闭自定义内容捕获可设置 `log-prompt=false`，关闭框架回答日志还需设置 `log-completion=false`。

2026-10-26 已只读核对本机 WSL 自托管实例：六个容器运行，健康接口 HTTP 200，版本 4.70.0。真实 trace `d4a69d789c8631c52c930893b5d0e0cc` 可查询到改写、关键词、向量、融合、rerank、上下文和生成；重排成功，请求 `ON_COMPLETE`，生成节点包含真实输入输出、模型和用量，见[真实接入记录](rerank-connection-2026-10-26.md)。检查时发现标题等非 RAG 节点没有会话属性，本次以整轮聊天观测补齐；新代码的跨阶段关联及成功、错误、取消生命周期由本地测试验证，尚未据此声称新版本真实模型的全部状态验收通过。导出器初始化、本机 HTTP 协议测试或内存 span 测试不能代替完整真实验收。

此前配置及关联修复的10个相关测试类共105项通过，0失败、0错误、0跳过，包含默认用户目录加载、环境覆盖、缺文件保持关闭、跨请求隔离、标题阶段取消及路由错误。已将本机既有凭据迁移到用户目录，使用该文件实际导出一条合成服务调用链：trace `bf72eba185c33bca8a96dffe44aea8d2` 的7个节点（含 route、answer、title）已在 Langfuse ClickHouse 查到，共享同一会话且全部结束，根节点 `ON_COMPLETE`。该验收运行真实 ChatService/路由/标题流程，但外部模型与业务接口为测试替身，不调用付费模型、不读写真实聊天或课程数据。记录在 `target/langfuse-improvements-tests.log` 和 `target/langfuse-config-smoke/ingestion.json`。该轮验收时未重启日常实例；后续已通过既有入口加载本轮计时、过滤和工具返回捕获代码，最新实例及真实复测结果见[评分基线记录](langfuse-baseline-2026-10-26.md)。

接入参考：[Spring AI 与 Langfuse](https://langfuse.com/integrations/frameworks/spring-ai)、[Langfuse OTLP/HTTP](https://langfuse.com/integrations/native/opentelemetry)。保持当前 Spring AI 与 Boot 版本，不照搬文档中的升级示例。

## 指标和复核

逐例结果记录选入上下文的证据身份/内容哈希/片段号、各路排名及原始分数、RRF 分数、rerank 状态、降级原因、估算资料 Token、各阶段耗时、总耗时、首条文字等待和可获得的模型用量。`evidenceRecall` 是对本例标注的必要片段在最终上下文中的覆盖；`reciprocalRank` 是首个标注片段在最终上下文的倒数排名。它们不等同于答案充分性或置信度。无答案/澄清样本不参与这些相关片段指标的分母，应另看回答。

回答质量需对照 `expectedPoints/forbiddenFacts/expectedOutcome` 逐例复核，特别关注数字、条件、否定和例外。不能用字面命中替代语义判断；自动匹配出的禁止词也可能是在正确否定该事实。`answerReview=pending_manual_review` 不是质量通过。脱敏汇总使用 `allAnnotatedEvidence` 表示全部标注片段命中，不将它解释为事实充分性。小样本 p50/p95 仅描述本次运行，不证明生产性能或泛化收益。

离线运行 `python -X utf8 scripts/rag-evaluation/summarize.py` 可根据固定原始输出与 `reviews.json` 重建本轮脱敏逐例和汇总报告，不访问外部服务。当前 `reviews.json` 是 Codex 来源初审，独立人工复核与模型裁判校准仍待完成。v1真实运行器的多次聊天调用用量覆盖、Prompt哈希缺失等限制见 `implementation-report-2026-10-24.md`；后续运行器已补阶段区分和版本字段，不能据此追溯声称本次已完整采集。

本轮以完整片段为选择单位，重复身份才去除；不同版本或来源不按相似文本合并。`cl100k_base` 是近似 Token 估算，不是 DeepSeek 精确计费。预算包括系统提示、所有必要消息、原问题、资料、已存在且已解析的工具 schema、输出预留和安全余量；片段放不下则不截断，必要输入本身超限就走 `KNOWLEDGE_CONTEXT_TOO_LONG` 错误协议。

## 回退与工程位置

运行配置将 `tj.ai.knowledge.rag.mode` 设为 `A` 即回到原向量参数与原上下文算法；保留公共权限、生命周期和超限保护。把 `tj.ai.knowledge.observation.langfuse.enabled` 设为 false 可停止外部 trace 导出，rerank 也有独立 enabled 开关。本轮不改 Mapping、不重建索引、不改变切分或 embedding 配置，没有业务数据回退步骤。配置切换何时生效沿用现有启动/配置机制，本轮不自行重启服务。

回答模板由 `tj.ai.knowledge.rag.prompt-version` 独立选择，支持 `knowledge-v1`、`knowledge-v2-conditions` 和 `knowledge-v3-evidence`，版本标签对应实际资源；默认保持 `knowledge-v1`。[v3的6例真实复测](evidence-v3-live-2026-10-25.md)未通过：缺依据时能够保守回答，但有明确依据时仍拒答，并混淆提示词与资料，保持实验状态。证据边界问题仍未解决，优先继续排查最终请求的消息边界；不能以提示词失败为由收口或转做其他功能来替代验收。当前没有追加版本或真实调用。新增模板的Token开销同样进入最终预算。

`KnowledgeRetrievalPipeline` 管检索、融合及局部恢复；`RagFusion` 实现稳定排名；`KnowledgeReranker` 管原生 HTTP 协议和映射校验；`KnowledgeContextSelector` 管完整片段和最终预算；`KnowledgeRetrievalAdvisor` 串联原问题增强与现有 SSE 流程；`RagRequestGuard` 保护取消、归属与准备预算；`RagObservabilityConfig/RagTelemetry` 管框架注册与业务阶段。

授权范围仍由请求线程上 `KnowledgeBaseAccess` 与会话归属核实，随后作为可信过滤表达式跨阶段传递，不在 worker 上重读 UserContext。每个新阶段前后都检查本轮有效性；即使阻塞客户端忽略中断，取消或超时后的迟到结果也不启动下一路、回退、回答或保存。RRF 累加同一片段在各路的排名贡献，rerank 再按原候选重排；两者都只能改变证据顺序，不能证明事实正确。最后由固定评测决定收益是否足以支付额外延迟，未证明时保留能力但关闭策略。
