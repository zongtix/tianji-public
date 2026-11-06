# 当前工作区分批提交方案（2026-10-26）

状态：按用户明确授权执行4批本地提交，执行记录见文末，不推送。基线为feature-aigc / 0686f84；原提交备份分支为codex/backup-hermes-20261026。

整理前共有117个变更文件。本方案覆盖全部文件：其中1份无对应本轮代码的音频规划暂留工作区，其余按4批处理。方案文件自身归第4批。AgentWorkflowTest跨第2、3批按差异块处理，所以批次文件数不能直接相加。

## 执行规则

- 当前授权覆盖本方案的4批本地暂存和提交，不包括推送。
- 按1→2→3→4顺序处理。每批仅暂存本节明确列出的文件或差异块，检查暂存差异后再提交，不使用git add .。
- 提交时需用每批累积内容的隔离快照检查编译和相关测试；仅在包含后续改动的当前完整工作区跑绿，不能证明中间提交可编译。规划阶段未执行这些批次隔离检查。
- 不重跑真实模型或浏览器验收；复用已归档证据。现有26项RAG回归和6题B/C对照属于完整工作区的既有验证。
- Windows CRLF文件使用git -c core.whitespace=cr-at-eol diff --check复核行尾；避免为提交整理批量改换行符。

## 分块归属与脱敏

1. `tj-aigc/src/test/java/com/tianji/aigc/agent/AgentWorkflowTest.java`：第2批只纳入课程历史、详情核实和澄清场景变更；第3批纳入boundKnowledgeSessionBypassesRouterAndRetainsScope中的knowledgeAdvisor工厂签名适配。已按target/commit-plan-20261026/batch-2-AgentWorkflowTest.patch完成第2批分块，第3批纳入剩余差异。
2. `docs/aigc-project-state-2026-10-25.md:61`：已删除文档中的本地控制台登录凭据，改为从本机私有配置获取；没有修改实际账号或密码。第4批检查时再次复核。

## 批次明细

### 第1批：fix(auth): 修复本地学员站同域令牌刷新

文件数：2（共享文件按块归属）。

提交说明草稿：

```text
fix(auth): 修复本地学员站同域令牌刷新

按协议、主机和18082端口识别本地学员站，正确选取学员Cookie；补充对应刷新回归。
```

提交前检查：AccountRefreshTest。

精确文件清单：

- `tj-auth/tj-auth-service/src/main/java/com/tianji/auth/controller/AccountController.java`
- `tj-auth/tj-auth-service/src/test/java/com/tianji/auth/controller/AccountRefreshTest.java`

### 第2批：fix(aigc): 核实课程推荐依据并改善多轮选课

文件数：16（共享文件按块归属）。

提交说明草稿：

```text
fix(aigc): 核实课程推荐依据并改善多轮选课

推荐提交前要求本轮取得对应课程详情；按用户问题限制模型资料范围，卡片仍使用业务事实。
路由携带最近课程卡片以处理指代，调整选课澄清与最终回答规则，并补充对应测试。
```

提交前检查：AgentWorkflowTest,CourseToolPreparationTest,CourseRecommendationEvidenceTest,CourseRecommendationToolsTest,CourseToolsTest,CourseBrowseToolsTest,CourseSemanticToolsTest,CourseCardStreamingTest,AgentPromptConfigTest。

精确文件清单：

- `tj-aigc/src/main/java/com/tianji/aigc/agent/AbstractAgent.java`
- `tj-aigc/src/main/java/com/tianji/aigc/agent/AgentDispatcher.java`
- `tj-aigc/src/main/java/com/tianji/aigc/agent/RouteAgent.java`
- `tj-aigc/src/main/java/com/tianji/aigc/tools/CourseRecommendations.java`
- `tj-aigc/src/main/java/com/tianji/aigc/tools/CourseToolPreparationAdvisor.java`
- `tj-aigc/src/main/java/com/tianji/aigc/tools/CourseTools.java`
- `tj-aigc/src/main/resources/prompts/consult-system-message.txt`
- `tj-aigc/src/main/resources/prompts/course-final-answer.txt`
- `tj-aigc/src/main/resources/prompts/course-tool-preparation.txt`
- `tj-aigc/src/main/resources/prompts/recommend-system-message.txt`
- `tj-aigc/src/main/resources/prompts/route-system-message.txt`
- `tj-aigc/src/test/java/com/tianji/aigc/agent/AgentWorkflowTest.java`（仅课程相关差异块）
- `tj-aigc/src/test/java/com/tianji/aigc/agent/CourseToolPreparationTest.java`
- `tj-aigc/src/test/java/com/tianji/aigc/tools/CourseRecommendationEvidenceTest.java`
- `tj-aigc/src/test/java/com/tianji/aigc/tools/CourseRecommendationToolsTest.java`
- `tj-aigc/src/test/java/com/tianji/aigc/tools/CourseToolsTest.java`

### 第3批：feat(aigc): 接入混合检索、专用重排与Langfuse追踪

文件数：35（共享文件按块归属）。

提交说明草稿：

```text
feat(aigc): 接入混合检索、专用重排与Langfuse追踪

增加向量与关键词召回、RRF融合、可选专用重排及上下文预算保护，保留授权范围、取消与超时控制。
接入Langfuse追踪与内容捕获；通用配置默认B，本地local配置启用C与qwen3-rerank，凭据由环境读取。
```

提交前检查：CourseRetrievalConfigTest,KnowledgeChatClientTest,KnowledgeChatRoutingTest,KnowledgeAnswerPromptTest,KnowledgeContextSelectorTest,KnowledgeKeywordSearchTest,KnowledgeRetrievalPipelineTest,KnowledgeRerankerTest,RagPreparationLifecycleTest,RagObservabilityTest,RagLangfuseExporterTest,KnowledgeRetrievalAdvisorTimeoutTest,KnowledgeQueryRewriterTest。

精确文件清单：

- `tj-aigc/pom.xml`
- `tj-aigc/src/main/java/com/tianji/aigc/config/CourseRetrievalConfig.java`
- `tj-aigc/src/main/java/com/tianji/aigc/config/KnowledgeRagProperties.java`
- `tj-aigc/src/main/java/com/tianji/aigc/config/KnowledgeStoreConfig.java`
- `tj-aigc/src/main/java/com/tianji/aigc/config/RagObservabilityConfig.java`
- `tj-aigc/src/main/java/com/tianji/aigc/knowledge/KnowledgeAnswerPrompt.java`
- `tj-aigc/src/main/java/com/tianji/aigc/knowledge/KnowledgeContextSelector.java`
- `tj-aigc/src/main/java/com/tianji/aigc/knowledge/KnowledgeKeywordSearch.java`
- `tj-aigc/src/main/java/com/tianji/aigc/knowledge/KnowledgeReranker.java`
- `tj-aigc/src/main/java/com/tianji/aigc/knowledge/KnowledgeRetrievalAdvisor.java`
- `tj-aigc/src/main/java/com/tianji/aigc/knowledge/KnowledgeRetrievalPipeline.java`
- `tj-aigc/src/main/java/com/tianji/aigc/knowledge/RagCandidate.java`
- `tj-aigc/src/main/java/com/tianji/aigc/knowledge/RagFusion.java`
- `tj-aigc/src/main/java/com/tianji/aigc/knowledge/RagRequestGuard.java`
- `tj-aigc/src/main/java/com/tianji/aigc/knowledge/RagTelemetry.java`
- `tj-aigc/src/main/java/com/tianji/aigc/service/impl/ChatServiceImpl.java`
- `tj-aigc/src/main/resources/application-local.yml`
- `tj-aigc/src/main/resources/application.yml`
- `tj-aigc/src/main/resources/prompts/knowledge-retrieval-v1.txt`
- `tj-aigc/src/main/resources/prompts/knowledge-retrieval-v2-conditions.txt`
- `tj-aigc/src/main/resources/prompts/knowledge-retrieval-v3-evidence.txt`
- `tj-aigc/src/test/java/com/tianji/aigc/CourseRetrievalConfigTest.java`
- `tj-aigc/src/test/java/com/tianji/aigc/KnowledgeChatClientTest.java`
- `tj-aigc/src/test/java/com/tianji/aigc/KnowledgeChatRoutingTest.java`
- `tj-aigc/src/test/java/com/tianji/aigc/KnowledgeStoreIntegrationTest.java`
- `tj-aigc/src/test/java/com/tianji/aigc/agent/AgentWorkflowTest.java`（仅剩余RAG工厂签名差异）
- `tj-aigc/src/test/java/com/tianji/aigc/knowledge/KnowledgeAnswerPromptTest.java`
- `tj-aigc/src/test/java/com/tianji/aigc/knowledge/KnowledgeContextSelectorTest.java`
- `tj-aigc/src/test/java/com/tianji/aigc/knowledge/KnowledgeKeywordSearchTest.java`
- `tj-aigc/src/test/java/com/tianji/aigc/knowledge/KnowledgeRerankerTest.java`
- `tj-aigc/src/test/java/com/tianji/aigc/knowledge/KnowledgeRetrievalPipelineTest.java`
- `tj-aigc/src/test/java/com/tianji/aigc/knowledge/RagLangfuseExporterTest.java`
- `tj-aigc/src/test/java/com/tianji/aigc/knowledge/RagObservabilityTest.java`
- `tj-aigc/src/test/java/com/tianji/aigc/knowledge/RagPreparationLifecycleTest.java`
- `tj-aigc/src/test/java/com/tianji/aigc/knowledge/course/CourseIndexSyncIntegrationTest.java`

### 第4批：chore(aigc): 整理验收脚本与阶段记录

文件数：65（共享文件按块归属）。

提交说明草稿：

```text
chore(aigc): 整理验收脚本与阶段记录

整理课程导购、资料同步、页面与RAG实验的验收脚本和证据，区分历史未通过项、最新单题接通与6题对照结果。
保留原始实验结果，不将初审通过等同全量人工验收。
```

提交前检查：PowerShell AST解析；Python语法编译；JSON解析及版本/题号/汇总一致性核对；交接文档凭据脱敏复核。。

精确文件清单：

- `docs/aigc-course-dialogue-review-2026-10-25.md`
- `docs/aigc-course-guide-browser-2026-10-25.md`
- `docs/aigc-course-guide-demo.md`
- `docs/aigc-course-guide-progress-2026-10-25.md`
- `docs/aigc-course-materials-2026-10-24.json`
- `docs/aigc-course-materials-2026-10-24.md`
- `docs/aigc-course-ui-fixes-2026-10-25.md`
- `docs/aigc-observability-2026-10-26.md`
- `docs/aigc-project-state-2026-10-25.md`（已脱敏）
- `docs/aigc-rag-optimization-plan.md`
- `docs/codex-handoff-2026-10-25-course-dialogue.md`
- `docs/commit-plan-2026-10-26.md`
- `docs/learning-question-context-fix-2026-10-24.md`
- `docs/rag-evaluation/README.md`
- `docs/rag-evaluation/cases.json`
- `docs/rag-evaluation/configs.json`
- `docs/rag-evaluation/environment.json`
- `docs/rag-evaluation/evidence-boundary-experiments.json`
- `docs/rag-evaluation/evidence-v3-live-2026-10-25.md`
- `docs/rag-evaluation/focused-live-2026-10-25.md`
- `docs/rag-evaluation/hermes-independent-review-2026-10-25.md`
- `docs/rag-evaluation/hermes-review-2026-10-25.md`
- `docs/rag-evaluation/hybrid-default-2026-10-26.md`
- `docs/rag-evaluation/implementation-report-2026-10-24.md`
- `docs/rag-evaluation/local-fix-experiments.json`
- `docs/rag-evaluation/local-fixes-2026-10-24.md`
- `docs/rag-evaluation/rerank-comparison-2026-10-26.md`
- `docs/rag-evaluation/rerank-connection-2026-10-26.md`
- `docs/rag-evaluation/results/A.json`
- `docs/rag-evaluation/results/APRIME.json`
- `docs/rag-evaluation/results/B.json`
- `docs/rag-evaluation/results/B_CONTEXT.json`
- `docs/rag-evaluation/results/C.json`
- `docs/rag-evaluation/results/D.json`
- `docs/rag-evaluation/results/evidence-v3-2026-10-25/boundary-context4.json`
- `docs/rag-evaluation/results/evidence-v3-2026-10-25/boundary-control.json`
- `docs/rag-evaluation/results/evidence-v3-2026-10-25/boundary.json`
- `docs/rag-evaluation/results/evidence-v3-2026-10-25/runtime-manifest.json`
- `docs/rag-evaluation/results/evidence-v3-2026-10-25/summary.json`
- `docs/rag-evaluation/results/focused-2026-10-25/conditions.json`
- `docs/rag-evaluation/results/focused-2026-10-25/context4.json`
- `docs/rag-evaluation/results/focused-2026-10-25/control.json`
- `docs/rag-evaluation/results/focused-2026-10-25/post-run-defaults.json`
- `docs/rag-evaluation/results/focused-2026-10-25/runtime-manifest.json`
- `docs/rag-evaluation/results/focused-2026-10-25/summary.json`
- `docs/rag-evaluation/results/local-fixes-v2-replay.json`
- `docs/rag-evaluation/results/rerank-focused-2026-10-26/B.json`
- `docs/rag-evaluation/results/rerank-focused-2026-10-26/C.json`
- `docs/rag-evaluation/results/rerank-focused-2026-10-26/reviews.json`
- `docs/rag-evaluation/results/rerank-focused-2026-10-26/summary.json`
- `docs/rag-evaluation/results/summary.json`
- `docs/rag-evaluation/reviews.json`
- `docs/rag-evaluation/source-manifest-local-fixes-v2.json`
- `docs/rag-evaluation/source-manifest.json`
- `docs/superpowers/plans/2026-10-22-course-detail-and-real-materials.md`
- `docs/superpowers/plans/2026-10-22-course-index-auto-sync.md`
- `docs/superpowers/plans/2026-10-22-published-course-pilot.md`
- `scripts/local/README.md`
- `scripts/local/check-course-guide.ps1`
- `scripts/local/verify-published-course-guide.py`
- `scripts/rag-evaluation/RagCandidateReplay.java`
- `scripts/rag-evaluation/RagEvaluation.java`
- `scripts/rag-evaluation/replay.ps1`
- `scripts/rag-evaluation/run.ps1`
- `scripts/rag-evaluation/summarize.py`

## 暂留工作区

- `docs/superpowers/plans/2026-10-22-bailian-audio.md`：音频规划与本轮四批代码无直接对应，保留文件，不自动纳入。

## 设计阶段已核对

- 原117个文件都有去向，除明确的共享测试文件外无跨批重复。
- RAG工厂与课程检索的ObservationRegistry参数迁移同属第3批，相关工厂配置和测试一起处理。
- 设计时索引为空、HEAD保持0686f84。仅生成本方案及target下清单/核对补丁，没有修改原117个文件的内容；后续执行阶段另行进行了上述文档凭据脱敏。

## 执行记录

- 第1批：`87de6f4`，认证隔离快照14项测试通过，0失败、0错误、0跳过。
- 第2批：`f3dee36`，课程隔离快照116项测试通过，0失败、0错误、0跳过。
- 第3批：隔离快照78项测试通过，0失败、0错误、0跳过。首次验证发现KnowledgeChatClientTest仅模拟纯向量存储却继承模式B，将该测试夹具显式固定为A后重新验证通过；没有为此修改业务实现。日志分别为target/commit-plan-20261026/batch-3-tests.log与batch-3-final-tests.log。
- 第4批为本文件所在的归档提交，纳入前检查PowerShell/Python语法、两个Java评测工具编译、JSON数据一致性及文档凭据脱敏。验证记录和四批完整提交号保存在target/commit-plan-20261026/execution.json。
- 没有重跑模型或浏览器验收，原始6题B/C证据保持不变；没有推送。音频规划保留在工作区。
