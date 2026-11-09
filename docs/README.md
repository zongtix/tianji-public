# 文档导航

[项目首页](../README.md) · [核心设计与源码](project-highlights.md) · [首次配置](public-setup.md)

## 从哪里开始

| 阅读目的 | 入口 |
| --- | --- |
| 了解项目重点与实现取舍 | [核心设计与源码阅读路线](project-highlights.md) |
| 在自己的环境构建、配置和启动 | [公开版配置说明](public-setup.md)、[环境变量示例](../.env.example) |
| 查看公开副本实际执行过的检查 | [公开版本验证记录](public-release-validation.md) |
| 了解 AI 功能、HTTP/SSE 接口 | [AI 模块说明](../tj-aigc/README.md) |

## 功能与配置

| 主题 | 文档 |
| --- | --- |
| AI 分发与课程工具 | [Agent 工作流](aigc-agent-workflow.md)、[课程语义检索](../tj-aigc/course-retrieval.md)、[课程索引同步](aigc-course-index-sync.md) |
| 知识库与 RAG | [知识库和授权范围](../tj-aigc/knowledge.md)、[RAG 评测入口](rag-evaluation/README.md) |
| 会话与用户记忆 | [消息存储](../tj-aigc/memory-storage.md)、[长期记忆设计](aigc-long-term-memory-v1.md) |
| 跨实例生成与停止 | [协调机制、源码与测试入口](project-highlights.md#2-redis-跨实例生成协调与停止) |
| 提示词与观测 | [提示词维护](../tj-aigc/prompts.md)、[Langfuse 观测说明](aigc-langfuse-memory-tracing.md) |
| 积分与排行榜补偿 | [持久化待办、幂等与补偿](project-highlights.md#31-积分持久化待办与排行榜补偿) |
| 优惠券 | [异步领取与兑换](coupon-async.md)、[优惠券推荐方案](coupon-recommendation.md)、[用户券分页](user-coupon-page.md) |
| 可选 MCP | [MCP 记录索引](mcp/README.md)、[课程 MCP 计划与记录](aigc-mcp-integration-plan.md) |
| 本机开发工具 | [本地脚本说明](../scripts/local/README.md) |

## 演示与评测路线

| 想查看的场景 | 阅读入口 |
| --- | --- |
| 课程推荐、课程卡片与详情追问 | [课程导购演示说明](aigc-course-guide-demo.md) |
| 跨会话记忆查看、纠正与删除 | [长期记忆演示记录](aigc-long-term-memory-demo.md) |
| 课程工作流用例与评测方法 | [课程工作流评测](course-workflow-evaluation/README.md) |
| 积分和优惠券知识问答 | [RAG 同批对照记录](rag-evaluation/points-coupons-2026-10-30.md)、[优惠券知识库验收](rag-evaluation/coupons-online-2026-10-31-acceptance.md) |
| 优惠券后端完整异步链路 | [MySQL/RabbitMQ 开发环境验收](coupon-live-verification-2026-10-30.md) |

演示与评测文档包含原开发环境的历史操作步骤。公开仓库未包含前端、业务数据库和全部原始输出，文中的本机地址、临时截图及报告路径需结合日期阅读；复现配置以[公开版说明](public-setup.md)为准。

## 历史记录

以下按主题归档阶段报告、验收和交接材料。原文件路径保留，便于已有链接和提交引用继续定位；当前能力先查阅对应模块文档与源码。

<details>
<summary>AI 课程导购、交互与迭代</summary>

- [2026-10-23 课程导购收口](aigc-course-guide-closing-2026-10-23.md)
- [2026-10-24 课程浏览](aigc-course-browse-2026-10-24.md)
- [2026-10-24 课程内容资料](aigc-course-materials-2026-10-24.md)
- [2026-10-25 项目阶段状态](aigc-project-state-2026-10-25.md)
- [2026-10-25 导购推进记录](aigc-course-guide-progress-2026-10-25.md)
- [2026-10-25 浏览器验收](aigc-course-guide-browser-2026-10-25.md)
- [2026-10-25 对话复核](aigc-course-dialogue-review-2026-10-25.md)
- [2026-10-25 前端交互修复](aigc-course-ui-fixes-2026-10-25.md)
- [2026-10-26 优化待办](aigc-optimization-backlog-2026-10-26.md)
- [2026-10-27 优化计划](aigc-optimization-plan-2026-10-27.md)与[执行记录](aigc-optimization-execution-2026-10-27.md)
- [2026-10-27 内容修复](aigc-course-content-fixes-2026-10-27.md)
- [2026-10-27 七轮验收](aigc-seven-turn-acceptance-2026-10-27.md)与[复测](aigc-seven-turn-retest-2026-10-27.md)
- [2026-10-27 交付记录](aigc-delivery-2026-10-27.md)
- [2026-10-28 长期记忆后续记录](aigc-long-term-memory-v1-followup-2026-10-28.md)
- [课程大纲](aigc-course-outline.md)、[演示数据说明](aigc-course-demo-data.md)、[正式课程试点](aigc-published-course-pilot.md)、[真实课程复核](aigc-real-course-review.md)

</details>

<details>
<summary>RAG、MCP 与专项实验</summary>

- [RAG 实验与验收索引](rag-evaluation/README.md)
- [RAG 优化计划](aigc-rag-optimization-plan.md)与[2026-10-26 优化报告](aigc-rag-optimization-report-2026-10-26.md)
- [2026-10-26 可观测性记录](aigc-observability-2026-10-26.md)
- [MCP 试点与沙箱支付记录索引](mcp/README.md)
- [课程资料检索验收清单](rag-evaluation/course-materials/acceptance-checklist.md)
- [历史设计规格](superpowers/specs/)与[实施计划](superpowers/plans/)

</details>

<details>
<summary>优惠券、学习互动与页面验收</summary>

- [2026-10-24 问答上下文修复](learning-question-context-fix-2026-10-24.md)
- [2026-10-30 异步领券开发环境验收](coupon-live-verification-2026-10-30.md)
- [2026-10-30 优惠券页面验收](coupon-browser-verification-2026-10-30.md)
- [2026-10-30 用户券分页验收](user-coupon-page-verification-2026-10-30.md)

</details>

<details>
<summary>平台升级、安全复核与开发交接</summary>

- [平台升级计划](platform-upgrade-plan.md)、[执行记录](platform-upgrade-execution.md)、[部署说明](platform-upgrade-deployment.md)
- [2026-10-23 AI 代码复核](aigc-code-review-2026-10-23.md)与[验证记录](aigc-code-review-validation-2026-10-23.md)
- [2026-11-03 复核记录](review-2026-11-03.md)与[安全整改验证](aigc-security-remediation-2026-11-03.md)
- [2026-10-25 对话交接](codex-handoff-2026-10-25-course-dialogue.md)
- [2026-10-26 范围交接](codex-handoff-2026-10-26-projection-scope.md)与[提交计划](commit-plan-2026-10-26.md)

</details>
