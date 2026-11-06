# 公共规则按需检索 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 普通会话按需检索积分/优惠券规则（单 Tool + domain），前端去掉知识库独立入口；旧 knowledgeBaseId 会话钉住兼容。

**Architecture:** 新增 `PublicRuleTools.searchPublicRules`，挂 KNOWLEDGE（仅 `knowledgeFilter==null`）；复用 `KnowledgeRetrievalPipeline` + `public-bases` + `.keyword` filter；修 `CourseToolPreparationAdvisor` 的 AgentType 守卫。前端只删入口、新会话不传 knowledgeBaseId。

**Tech Stack:** Java 17 / Spring AI / tj-aigc；Vue 3 tj-protal；ES `tj_aigc_knowledge_v4_1024`。

**权威设计：** `docs/superpowers/specs/2026-10-31-on-demand-public-rules-design.md`  
**DeepSeek 商定：** AGREE_VARIANT（单 Tool、挂 KNOWLEDGE、旧会话钉住、CourseToolPreparationAdvisor 守卫）。

**纪律：** 默认停 Final；不合/不推除非用户明确说。仓库：`D:/Java/tianji` + 前端 `D:/Java/tj-protal`。

---

## File map

| 路径 | 职责 |
| --- | --- |
| `tj-aigc/.../knowledge/KnowledgeBaseAccess.java` | 新增无 ThreadLocal 的 `filterFor(baseId)` |
| `tj-aigc/.../tools/PublicRuleTools.java` | `searchPublicRules` + 调用预算 + 证据白名单 |
| `tj-aigc/.../config/KnowledgeStoreConfig.java` | `KnowledgeRetrievalPipeline` 提成 Bean；注册工具 |
| `tj-aigc/.../agent/AbstractAgent.java` | `tools(AgentContext)`；toolContext 加 userId / 调用计数 |
| `tj-aigc/.../agent/KnowledgeAgent.java` | 注入工具；旧会话返回空工具集 |
| `tj-aigc/.../agent/ConsultAgent.java` | 仅签名适配 |
| `tj-aigc/.../advisor/CourseToolPreparationAdvisor.java`（或现路径） | AGENT_TYPE 守卫 |
| `tj-aigc/.../prompts/knowledge-system-message.txt` | 规则题先调工具 |
| `tj-aigc/.../prompts/route-system-message.txt` | 规则 → KNOWLEDGE |
| `tj-aigc/.../application.yml` | `tj.ai.public-rules.*` |
| `tj-protal/.../AssistantWorkspace.vue` | 删按钮与 knowledgeBaseId 新建路径 |
| `tj-protal/.../api/assistant.js` | 新建会话不再强制透传 KB（保留可选兼容若需） |

---

### Task 1: KnowledgeBaseAccess.filterFor + 测试

**Files:**
- Modify: `tj-aigc/src/main/java/com/tianji/aigc/knowledge/KnowledgeBaseAccess.java`
- Test: `tj-aigc/src/test/java/com/tianji/aigc/knowledge/KnowledgeBaseAccessTest.java`

- [x] **Step 1:** 写失败测试：`filterFor("tianji-points")` → `documentId in ['tianji-points-guide']`；未知 base → null；不依赖 UserContext。
- [x] **Step 2:** 实现 `filterFor`；`requireFilter` 委托它并保留登录/启用校验。
- [x] **Step 3:** 跑通 `KnowledgeBaseAccessTest`。

### Task 2: CourseToolPreparationAdvisor AgentType 守卫

**Files:**
- Modify: 现有 `CourseToolPreparationAdvisor`（先 Grep 定位）
- Test: 对应测试或新增断言 — KNOWLEDGE + toolCallbacks 时不进入课程准备流程

- [x] **Step 1:** 写失败测试：AGENT_TYPE=KNOWLEDGE 时 advisor 直接放行。
- [x] **Step 2:** 仅当 `AGENT_TYPE ∈ {CONSULT, RECOMMEND}` 时启用准备流程。
- [x] **Step 3:** 确认 CONSULT 讲义路径仍绿。

### Task 3: 抽出 KnowledgeRetrievalPipeline Bean

**Files:**
- Modify: `KnowledgeStoreConfig.java`

- [x] **Step 1:** 将 `knowledgeAdvisor` 内 pipeline 构造提为 `@Bean`（条件与知识库 ES 一致）。
- [x] **Step 2:** Advisor 注入同一 Bean；现有知识库单测不回归。

### Task 4: PublicRuleTools（TDD）

**Files:**
- Create: `tj-aigc/src/main/java/com/tianji/aigc/tools/PublicRuleTools.java`
- Create: `tj-aigc/src/test/java/com/tianji/aigc/tools/PublicRuleToolsTest.java`
- Modify: `application.yml`（`tj.ai.public-rules.enabled` 默认 true、`max-calls-per-turn: 3`、`max-evidence: 3`）

- [x] **Step 1:** 写失败测试：domain 映射、无 userId → ACCESS_DENIED、未知 domain → ACCESS_DENIED、旧会话标志拒绝、证据 documentId 二次校验丢弃越权、第 4 次调用 → TEMPORARILY_UNAVAILABLE。
- [x] **Step 2:** 实现 `searchPublicRules`：复用 pipeline；filter 来自 `filterFor`；`McpCallScope` + `RagRequestGuard(25s)`；citation 前缀 `POINTS-`/`COUPONS-`。
- [x] **Step 3:** 单测全绿。

### Task 5: KnowledgeAgent 挂载工具

**Files:**
- Modify: `AbstractAgent.java`、`KnowledgeAgent.java`、`ConsultAgent.java`（签名）
- Test: 普通会话有工具 / `knowledgeFilter!=null` 无工具

- [x] **Step 1:** 写失败测试覆盖工具挂载矩阵。
- [x] **Step 2:** `tools(AgentContext)`；toolContext 放入 userId 与 turn 调用计数。
- [x] **Step 3:** 单测绿。

### Task 6: 提示词 + 可选确定性路由

**Files:**
- Modify: `knowledge-system-message.txt`、`route-system-message.txt`
- Optional: `PlatformRuleQuestion` + `AgentDispatcher` 直判 KNOWLEDGE
- Test: 路由/提示词相关现有测试更新

- [x] **Step 1:** 更新提示词（规则先工具；单域不引用另一域；空证据如实说）。
- [x] **Step 2:**（推荐）确定性规则问句 → KNOWLEDGE。
- [x] **Step 3:** `AgentPromptConfigTest` / workflow 测试更新并通过。

### Task 7: 前端去入口

**Files:**
- Modify: `D:/Java/tj-protal/src/components/assistant/AssistantWorkspace.vue`
- Modify: `D:/Java/tj-protal/src/api/assistant.js`（按需）
- Test: `scripts/assistant-pilot.test.mjs` 若有 KB 断言则更新

- [x] **Step 1:** 删「积分与签到问答」按钮与孤立样式。
- [x] **Step 2:** `newConversation()` 无 KB；创建会话不传 knowledgeBaseId。
- [x] **Step 3:** 副标题/推荐题：新会话走普通；旧会话带 knowledgeBaseId 的展示可保留兼容或简化为普通副标题（以设计为准：副标题固定普通文案，历史仍可打开）。
- [x] **Step 4:** 本地快速点验：侧栏无积分按钮；新会话请求无 knowledgeBaseId。

### Task 8: 隔离冒烟 + 提交

- [x] **Step 1:** 后端单测包：PublicRule / Access / Advisor 守卫 / Agent 挂载。
- [x] **Step 2:**（服务可用时）HTTP：普通会话问积分无券原文；问券无积分原文；旧 points 会话无跨库。证据：`target/aigc-es/knowledge/public-rules-http-smoke.json`（`allOk=true`，2026-11-01）；脚本 `scripts/local/public-rules-http-smoke.py`。
- [x] **Step 3:** 更新验收备注到 `docs/rag-evaluation/` 或本计划末节勾选。
- [ ] **Step 4:** 用户同意后再 commit（Final）；不合不推除非另说。

---

## 验收对照（执行时勾选）

- [x] 普通会话积分隔离 — 单测 + HTTP `ordinary_points`
- [x] 普通会话优惠券隔离 — 单测 + HTTP `ordinary_coupons`（无积分语料泄漏）
- [x] 混合题双域分标 — 提示词约束 + 每域≤1；HTTP 券题第二域曾 TEMPORARILY_UNAVAILABLE 时如实说明
- [x] 旧 tianji-points 会话无工具、无券原文 — HTTP `legacy_points_no_coupon`
- [x] 闲聊不检索 — PlatformRuleQuestion 负例 + 提示词「普通问答可不调工具」
- [x] CourseToolPreparationAdvisor 不劫持 KNOWLEDGE — 单元测试通过
- [x] 前端无独立 KB 按钮 — AssistantWorkspace 已删入口

## 回滚

- 前端恢复按钮；后端关 `tj.ai.public-rules.enabled` 或去掉工具 Bean；旧 Advisor 路径始终可用。
