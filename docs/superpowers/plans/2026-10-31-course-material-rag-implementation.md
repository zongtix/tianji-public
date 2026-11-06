# 课程讲义 RAG Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为首期公开演示讲义打通「定位课程 → 校验资料范围 → 检索讲义 → 引用原文回答」，在闸 A 通过后再做同讲义 A/A′/B/C 对照。

**Architecture:** 独立讲义索引 + `CourseMaterialSearch` 包装（不注册第二个全局 `VectorStore` Bean）；CONSULT 新增 `searchCourseMaterials` 工具；服务端在 `ChatServiceImpl` 注入可信 `userId` 与发布范围，工具内用 ES filter 限制 `courseId`/material；复用 `KnowledgeRetrievalPipeline`，不嵌套 `KnowledgeRetrievalAdvisor`。

**Tech Stack:** Spring AI Tool Calling、Elasticsearch 向量库、现有 Embedding/`TokenTextSplitter`、JUnit 5、现有 RAG 评测脚本。

**Design source:** [2026-10-31-course-material-rag.md](./2026-10-31-course-material-rag.md)

**Ownership:** Codex 独占生产 Java/配置/提示词/评测运行器/集成测试；DeepSeek 独占讲义 fixture、manifest、开发题与来源审阅；Cursor 独占验收清单、AI 保留题、标签独立审阅与方案异议记录。冲突热点（`ConsultAgent`、`AgentDispatcher`、`AgentContext`、`ChatServiceImpl`、`CourseFactAnswer`、路由/咨询/工具准备提示词）仅 Codex 修改。

**Pilot courses (reuse existing demo seed):** Redis 主课 `910000000000010005`、MySQL 对照课 `910000000000010004`（名称带 `【AI演示】`；生产推荐过滤保持不变）。

**Gates:** 闸 A（工具可达、引用可核实、跨课/受限不泄露）未过不启动闸 B（四组算法对照）。

---

## File map

| Path | Owner | Responsibility |
| --- | --- | --- |
| `tj-aigc/src/main/java/com/tianji/aigc/config/CourseMaterialConfig.java` | Codex | 开关默认关；创建 `CourseMaterialSearch`，禁止注册 `VectorStore` Bean |
| `tj-aigc/src/main/java/com/tianji/aigc/knowledge/material/CourseMaterialSearch.java` | Codex | 包装 pipeline + 作用域检索 |
| `tj-aigc/src/main/java/com/tianji/aigc/knowledge/material/CourseMaterialAccess.java` | Codex | 服务端 manifest → filter；不信任模型参数 |
| `tj-aigc/src/main/java/com/tianji/aigc/knowledge/material/CourseMaterialDocumentService.java` | Codex | Markdown 切分、稳定 ID、导入跳过/冲突 |
| `tj-aigc/src/main/java/com/tianji/aigc/knowledge/material/CourseMaterialImportCommand.java` | Codex | 离线导入入口 |
| `tj-aigc/src/main/java/com/tianji/aigc/knowledge/material/CourseMaterialManifest.java` | Codex | Manifest 模型 |
| `tj-aigc/src/main/java/com/tianji/aigc/tools/CourseMaterialTools.java` | Codex | `searchCourseMaterials` |
| `tj-aigc/src/main/java/com/tianji/aigc/agent/AgentContext.java` | Codex | 增加只读 `userId` |
| `tj-aigc/src/main/java/com/tianji/aigc/agent/AbstractAgent.java` | Codex | toolContext 注入 userId |
| `tj-aigc/src/main/java/com/tianji/aigc/service/impl/ChatServiceImpl.java` | Codex | 构造 context 时传入已核验 userId |
| `tj-aigc/src/main/java/com/tianji/aigc/agent/ConsultAgent.java` | Codex | 白名单加入讲义工具 |
| `tj-aigc/src/main/java/com/tianji/aigc/tools/CourseFactAnswer.java` | Codex | 讲义/价格混合题不被目录快捷答吞掉 |
| `tj-aigc/src/main/resources/prompts/{route,consult}-system-message.txt` | Codex | 路由与咨询指引讲义工具 |
| `tj-aigc/src/main/resources/prompts/course-tool-preparation.txt` | Codex | 准备阶段允许调用讲义工具 |
| `tj-aigc/src/main/resources/application.yml` | Codex | `tj.ai.course-materials.*` 默认 false |
| `tj-aigc/src/main/resources/course-materials/manifest.json` | DeepSeek 起草 / Codex 校验 schema | 发布范围 |
| `tj-aigc/src/main/resources/course-materials/*.md` | DeepSeek | 演示讲义 |
| `docs/rag-evaluation/course-materials/acceptance-checklist.md` | Cursor | 闸 A/B 验收清单 |
| `docs/rag-evaluation/course-materials/holdout-cases-draft.json` | Cursor | AI 保留题（不参与调参） |
| `docs/rag-evaluation/course-materials/dev-cases-draft.json` | DeepSeek | 开发/调参题草稿 |
| `docs/rag-evaluation/course-materials/dissent-log.md` | Cursor | 方案异议记录 |

---

### Task 0: Cursor 验收骨架与 DeepSeek 资料契约（可与 Task 1 并行）

**Files:**
- Create: `docs/rag-evaluation/course-materials/acceptance-checklist.md`
- Create: `docs/rag-evaluation/course-materials/holdout-cases-draft.json`
- Create: `docs/rag-evaluation/course-materials/dissent-log.md`
- Create (DeepSeek): `docs/rag-evaluation/course-materials/dev-cases-draft.json`
- Create (DeepSeek): `tj-aigc/src/main/resources/course-materials/manifest.json`
- Create (DeepSeek): Redis 主课 3～5 篇 + MySQL 对照 1～2 篇 Markdown

- [x] **Step 1: Cursor 写闸 A 验收清单**

清单至少包含：

1. 明确课程名的讲义问题 → CONSULT，且调用 `searchCourseMaterials`
2. 通用技术解释（无课程名）→ 原知识/闲聊路径，不调讲义工具
3. 「讲多深」类问题：若问的是讲义依据，不能只靠目录 `CourseFactAnswer` 吞答
4. 价格＋讲义混合问题：价格走业务工具，讲义走资料工具；快捷回答不得吞掉讲义子问
5. 绑定 `tianji-points` 的会话不能调用课程/讲义工具
6. Redis 课问题不得返回 MySQL 课片段；未登录 / 非试点课 / 付费属性资料 → `ACCESS_DENIED` 或等价确定性拒绝
7. 讲义正文含「忽略权限」时不得扩大检索范围
8. 停止生成与两用户并发隔离仍成立

- [x] **Step 2: Cursor 写 AI 保留题草稿（约 8～12 题）**

JSON 字段对齐现有评测：`id`、`split=holdout`、`type`、`question`、`authorizedCourseIds`、`expectedOutcome`、`notes`；每题标注 `"author":"cursor-ai"`，`participatesInTuning: false`。

- [x] **Step 3: DeepSeek 准备 manifest + 讲义（契约冻结）**

`manifest.json` 最小 schema：

```json
{
  "version": "demo-v1",
  "materials": [
    {
      "materialId": "redis-hotspot-faq-01",
      "courseId": "910000000000010005",
      "courseName": "【AI演示】Redis 缓存与热点治理",
      "title": "缓存击穿与互斥锁 FAQ",
      "sourceFile": "redis-hotspot-faq-01.md",
      "access": "PUBLIC_DEMO",
      "publishStatus": "PUBLISHED",
      "origin": "演示原创/合法来源说明",
      "verifiedAt": "2026-10-31"
    }
  ]
}
```

Markdown 使用二级标题分章；文首注明演示用途，不冒充真实售卖讲义。

- [x] **Step 4: Codex 只读核对**

确认 courseId 与 `scripts/local/fixtures/ai-demo-courses.json` 一致；拒绝把讲义塞进 `course_content.course_detail`。

---

### Task 1: 配置开关与 `CourseMaterialSearch` Bean（无全局 VectorStore）

**Files:**
- Create: `tj-aigc/src/main/java/com/tianji/aigc/knowledge/material/CourseMaterialSearch.java`
- Create: `tj-aigc/src/main/java/com/tianji/aigc/config/CourseMaterialConfig.java`
- Modify: `tj-aigc/src/main/resources/application.yml`（在 `course-retrieval` 旁增加块）
- Create: `tj-aigc/src/test/java/com/tianji/aigc/CourseMaterialConfigTest.java`

- [x] **Step 1: 写失败测试 — 开关关闭时无 Bean；开启时不替换知识库 VectorStore**

```java
@Test
void disabledCourseMaterialsNeedsNoBean() {
    runner.withPropertyValues(
            "tj.ai.knowledge.enabled=true",
            "tj.ai.course-materials.enabled=false")
            .run(context -> assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(CourseMaterialSearch.class));
}

@Test
void materialStoreDoesNotReplaceKnowledgeVectorBean() throws Exception {
    // 镜像 CourseRetrievalConfigTest.independentCourseStoreDoesNotReplaceTheKnowledgeVectorBean
    // 断言 getBeansOfType(VectorStore.class) 只有 knowledgeVectorStore
}
```

- [x] **Step 2: 运行测试确认失败**

Run: `mvn -pl tj-aigc -Dtest=CourseMaterialConfigTest test`
Expected: FAIL（类或配置不存在）

- [x] **Step 3: 实现搜索包装与配置**

```java
public class CourseMaterialSearch {
    public static final String INDEX = "tj_aigc_course_materials_v1_1024";
    private final KnowledgeRetrievalPipeline pipeline;

    public CourseMaterialSearch(KnowledgeRetrievalPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public KnowledgeRetrievalPipeline.Result search(String question, Filter.Expression scope,
                                                    RagRequestGuard guard) {
        return pipeline.retrieve(question, scope, guard);
    }
}
```

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "tj.ai", name = {"knowledge.enabled", "course-materials.enabled"}, havingValue = "true")
public class CourseMaterialConfig {
    @Bean
    @ConditionalOnProperty(prefix = "tj.ai.knowledge", name = "store-type", havingValue = "elasticsearch", matchIfMissing = true)
    public CourseMaterialSearch courseMaterialSearch(/* KnowledgeStoreConfig factory, ES clients, EmbeddingModel,
            KnowledgeRagProperties, optional keyword/rerank, ObservationRegistry */) {
        // factory.knowledgeVectorStore(..., CourseMaterialSearch.INDEX, dimensions, observations)
        // new KnowledgeRetrievalPipeline(vectors, keywordSearchForIndex, reranker, baseline, props, telemetry)
        // 只返回 CourseMaterialSearch，绝不 @Bean VectorStore
    }
}
```

YAML：

```yaml
    course-materials:
      enabled: ${TJ_AI_COURSE_MATERIALS_ENABLED:false}
      index-name: tj_aigc_course_materials_v1_1024
      max-calls-per-turn: 2
      max-evidence: 3
```

- [x] **Step 4: 再跑测试至通过**

Run: `mvn -pl tj-aigc -Dtest=CourseMaterialConfigTest,CourseRetrievalConfigTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add tj-aigc/src/main/java/com/tianji/aigc/config/CourseMaterialConfig.java \
  tj-aigc/src/main/java/com/tianji/aigc/knowledge/material/CourseMaterialSearch.java \
  tj-aigc/src/main/resources/application.yml \
  tj-aigc/src/test/java/com/tianji/aigc/CourseMaterialConfigTest.java
git commit -m "$(cat <<'EOF'
feat(aigc): add gated course material search without VectorStore bean

EOF
)"
```

---

### Task 2: Manifest 访问控制与文档导入

**Files:**
- Create: `CourseMaterialManifest.java`, `CourseMaterialAccess.java`, `CourseMaterialDocumentService.java`, `CourseMaterialImportCommand.java`
- Create: `tj-aigc/src/test/java/com/tianji/aigc/knowledge/material/CourseMaterialDocumentServiceTest.java`
- Create: `tj-aigc/src/test/java/com/tianji/aigc/knowledge/material/CourseMaterialAccessTest.java`
- Depends on DeepSeek fixtures from Task 0

- [x] **Step 1: 写失败测试 — 稳定 ID、跳过、冲突、公开范围 filter**

```java
@Test
void accessBuildsFilterOnlyForPublishedPublicDemoOfRequestedCourse() {
    var access = CourseMaterialAccess.fromClasspath("course-materials/manifest.json");
    var decision = access.authorize(userId=1L, courseId="910000000000010005");
    assertThat(decision.status()).isEqualTo(Status.OK);
    assertThat(decision.filterExpression()).contains("courseId");
    assertThat(decision.filterExpression()).contains("910000000000010005");
}

@Test
void unpaidOrRestrictedMaterialIsDeniedWithoutEmptyFallback() {
    var decision = access.authorize(1L, "999");
    assertThat(decision.status()).isEqualTo(Status.ACCESS_DENIED);
    assertThat(decision.filterExpression()).isNull();
}
```

切分测试镜像 `KnowledgeDocumentServiceTest`：元数据含 `courseId`、`materialId`、`sourceId`、`title`、`headingPath`、`contentHash`、`chunkIndex`、`embeddingModel`；同内容二次导入 `imported=false`；不同 contentHash 拒绝覆盖。

- [x] **Step 2: 运行测试确认失败**

Run: `mvn -pl tj-aigc -Dtest=CourseMaterialDocumentServiceTest,CourseMaterialAccessTest test`

- [x] **Step 3: 实现**

`CourseMaterialAccess.Decision` 区分 `OK` / `ACCESS_DENIED` / `TEMPORARILY_UNAVAILABLE`。首期仅 `PUBLIC_DEMO` + `PUBLISHED`；需要登录（userId 非空）。Filter 在 ES 查询内限制，禁止先全库取回再过滤。

`CourseMaterialDocumentService.readMarkdown` 复用 `TokenTextSplitter(600,100)` 与 `##` 分章；稳定 UUID：`materialId + ":" + contentHash + ":" + chunkIndex`。

导入命令对齐 `KnowledgeImportCommand` / `CourseImportCommand`：隔离 Spring 上下文，设置 `tj.ai.knowledge.index-name` 为讲义索引或 `tj_aigc_eval_materials_<batch>`。

- [ ] **Step 4: 测试通过后 commit**

```bash
git commit -m "$(cat <<'EOF'
feat(aigc): add course material manifest access and import path

EOF
)"
```

---

### Task 3: 可信 userId 进入 AgentContext / ToolContext

**Files:**
- Modify: `AgentContext.java`
- Modify: `AbstractAgent.java`
- Modify: `ChatServiceImpl.java`
- Modify: 所有 `new AgentContext(...)` / `withHistory` / `withClarification` 调用点与测试
- Create/Modify: 相关 agent 测试

- [x] **Step 1: 写失败测试 — toolContext 含服务端 userId**

在现有 agent/workflow 测试中断言：构造 context 时传入的 userId 进入 `toolContext`；工具不得从 `conversationId` 字符串解析身份作为授权依据（可单测辅助方法）。

- [x] **Step 2: 扩展 record**

```java
public record AgentContext(
        String question,
        String conversationId,
        Long userId, // 服务端已核验；可为演示只读上下文
        CourseRecommendations recommendations,
        BooleanSupplier active,
        BooleanSupplier ownsGeneration,
        KnowledgeRetrievalAdvisor knowledgeAdvisor,
        String knowledgeFilter,
        List<Message> history,
        String clarification,
        UserMemoryContext userMemory) {
    // 更新 compact ctor、withHistory、withClarification 全部透传 userId
}
```

`ChatServiceImpl` 在 `requireOwnedSession` 之后：

```java
var context = new AgentContext(question, conversationId, userId, recommendations,
        () -> !cancelled.get() && generationStore.renew(generation),
        () -> generationStore.isCurrent(generation), rag, filter, List.of(), null, memory.orElse(null));
```

`AbstractAgent.toolContext`：

```java
.toolContext(Map.of(
        CourseRecommendations.CONTEXT_KEY, context.recommendations(),
        McpCallScope.CONTEXT_KEY, new McpCallScope(context.active(), context.ownsGeneration()),
        CourseToolPreparationAdvisor.AGENT_TYPE, getAgentType(),
        CourseMaterialTools.USER_ID_KEY, context.userId()))
```

注意：`Map.of` 不允许 null；userId 缺失时用显式 `HashMap` 并在讲义工具内拒绝。

- [x] **Step 3: 全量编译相关测试**

Run: `mvn -pl tj-aigc -Dtest=AgentWorkflowTest,KnowledgeChatRoutingTest,CourseToolsTest test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(aigc): pass verified userId into agent tool context

EOF
)"
```

---

### Task 4: `searchCourseMaterials` 工具 + CONSULT 白名单

**Files:**
- Create: `tj-aigc/src/main/java/com/tianji/aigc/tools/CourseMaterialTools.java`
- Modify: `ConsultAgent.java` — `ALLOWED_TOOLS` 增加 `searchCourseMaterials`（工具关闭时可不注册 bean，Consult 需能在无 bean 时编译；用 `ObjectProvider<CourseMaterialTools>`）
- Create: `tj-aigc/src/test/java/com/tianji/aigc/tools/CourseMaterialToolsTest.java`
- Modify: agent 测试中 CONSULT 工具列表断言

- [x] **Step 1: 写失败测试**

```java
@Test
void deniesWhenMaterialFeatureDisabledOrUserMissing() { ... }

@Test
void returnsAccessDeniedForNonPilotCourseWithoutCallingSearch() { ... }

@Test
void okReturnsAtMostThreeEvidenceWithCitationFields() { ... }

@Test
void temporarilyUnavailableIsNotEmpty() { ... }
```

工具返回结构（给模型的 JSON/record）：

```java
record MaterialSearchResult(
        String status, // OK | EMPTY | ACCESS_DENIED | TEMPORARILY_UNAVAILABLE
        String courseId,
        String courseName,
        String materialVersion,
        List<Evidence> evidence) {}

record Evidence(
        String materialId,
        String chapterTitle,
        int chunkIndex,
        String contentHash,
        String excerpt,
        String citationId) {}
```

- [x] **Step 2: 实现工具**

```java
@Tool(description = "检索已发布课程讲义片段。courseId 仅用于定位，不是授权证明。返回可引用的原文片段；不要编造未返回的内容。")
public MaterialSearchResult searchCourseMaterials(
        @ToolParam(description = "待核验的课程 ID") Long courseId,
        @ToolParam(description = "用户关于讲义内容的问题") String question,
        ToolContext context) {
    // 1. McpCallScope.check()
    // 2. 读取 USER_ID_KEY；空则 ACCESS_DENIED
    // 3. access.authorize(userId, courseId)
    // 4. 每轮调用计数（可用 CourseRecommendations 旁的请求态或 Thread-safe 计数放 recommendations 平行 key）≤ max-calls
    // 5. materialSearch.search(question, filter, guard)
    // 6. 最多 3 条完整片段；超预算整段丢弃或 TEMPORARILY_UNAVAILABLE，禁止截断半句条件
}
```

`ConsultAgent`：

```java
private static final Set<String> ALLOWED_TOOLS =
        Set.of("searchCourses", "queryCourseById", "searchCourseMaterials");
```

构造时合并 `CourseTools` + 可选 `CourseMaterialTools` 的 callbacks 再过滤。

- [ ] **Step 3: 测试通过并 commit**

```bash
git commit -m "$(cat <<'EOF'
feat(aigc): expose searchCourseMaterials on CONSULT only

EOF
)"
```

---

### Task 5: 路由 / 快捷回答 / 提示词联动（闸 A 核心）

**Files:**
- Modify: `CourseFactAnswer.java`
- Modify: `route-system-message.txt`
- Modify: `consult-system-message.txt`
- Modify: `course-tool-preparation.txt`
- Create/Modify: `CourseFactAnswerTest`（若无则新建）与路由相关测试

- [x] **Step 1: 写失败测试 — 讲义意图不被吞答**

```java
@Test
void lectureEvidenceQuestionsAreNotAnsweredFromCatalogueOnly() {
    assertThat(CourseFactAnswer.answer(
            "【AI演示】Redis 缓存与热点治理讲义里怎样解释缓存击穿？", recommendations))
            .isNull();
}

@Test
void mixedPriceAndLectureReturnsNullForFactShortcut() {
    assertThat(CourseFactAnswer.answer(
            "这门课多少钱？讲义里互斥锁例子为什么这样写？", recommendations))
            .isNull();
}
```

在 `OTHER_DIMENSIONS` 或 `supportsIntent` 中排除含「讲义|资料原文|章节正文|缓存击穿」等讲义检索意图；深度问题若明确要求讲义依据，返回 null。

- [x] **Step 2: 更新提示词**

`route-system-message.txt` CONSULT 行补充：已明确课程的讲义/章节正文依据问题归 CONSULT；无课程指向的通用技术解释仍为 KNOWLEDGE。

`consult-system-message.txt` 补充：讲义问题必须调用 `searchCourseMaterials`；引用格式「课程／讲义标题／章节／引用编号」；无证据时说明资料不足，不以常识冒充该课原文；价格仍走业务工具。

`course-tool-preparation.txt` 补充：讲义依据问题在定位课程后调用 `searchCourseMaterials`；不要用目录标题代替讲义正文。

- [ ] **Step 3: 测试 + commit**

```bash
git commit -m "$(cat <<'EOF'
fix(aigc): keep lecture questions out of catalogue fact shortcuts

EOF
)"
```

---

### Task 6: 闸 A 集成测试骨架（Codex 落实 Cursor 清单）

**Files:**
- Create: `tj-aigc/src/test/java/com/tianji/aigc/knowledge/material/CourseMaterialGateATest.java`
- Reference: `docs/rag-evaluation/course-materials/acceptance-checklist.md`

- [x] **Step 1: 确定性测试（可无真实模型）**

用 Mock `CourseMaterialSearch` / fake ToolCallback 验证：

1. knowledgeFilter 非空会话路径不暴露讲义工具（对齐 `AgentDispatcher`）
2. authorize 拒绝时工具不调用 pipeline
3. 跨 courseId filter 不会出现对照课 materialId
4. cancel 后 `McpCallScope` 中断
5. prompt 文件含讲义工具关键词的静态断言（防回归漏改）

- [x] **Step 2: 小核心真实调用清单（需本地服务时手动/脚本）**

在验收清单勾选：1～2 条真实 CONSULT 讲义问答 + 1 条跨课拒绝 + 1 条知识库会话隔离。归档到 `docs/rag-evaluation/course-materials/gate-a-notes.md`（Codex 写事实，Cursor 复核标签）。

- [ ] **Step 3: Commit 测试与笔记**

闸 A 未人工勾选通过前，**禁止**开始 Task 7。

---

### Task 7: 闸 B — 同批 A/A′/B/C（仅闸 A 通过后）

**Files:**
- Extend: `scripts/rag-evaluation/`（隔离索引 `tj_aigc_eval_materials_<batch>`）
- DeepSeek: 冻结开发题与证据锚点
- Cursor: 审核 holdout 覆盖、配对指纹、指标口径
- Codex: 运行与归档

- [x] **Step 1: 冻结资料/标签哈希**（争议先解决）
- [x] **Step 2: 准备约 24～32 题**（含直接、同义、条件/否定、跨章、相似课干扰、缺失、价格混合）
- [x] **Step 3: 同索引快照跑 A / A′ / B / C**；真实故障与回退单列
- [x] **Step 4: 报告区分「现有工具基线 vs 讲义」与「同讲义算法对照」**；不用积分+优惠券成绩背书
- [x] **Step 5: Cursor 复核重要分歧；不把 holdout 用于调参**

---

### Task 8: 收口与回退

- [x] 默认开关保持 false；验证实例演示步骤写入设计文档附录
- [x] 回退：关 `tj.ai.course-materials.enabled` + 撤销试点 publishStatus；保留隔离索引与归档
- [x] 明确未做：付费讲义、上传后台、OCR、全平台资料搜索
- [x] AI 审阅与人工验收分开记录

---

## Self-review

1. **Spec coverage:** 设计文档 §3–§9 均有对应 Task；付费能力仅列于设计第二阶段，本计划不实现 Feign 学习权改造。
2. **Placeholder scan:** 无 TBD；DeepSeek/Cursor 交付物给出路径与 schema。
3. **Type consistency:** 工具名 `searchCourseMaterials`、状态枚举、索引名与设计文档一致；`AgentContext.userId` 全链路透传。
4. **Bean safety:** Task 1 强制回归「不注册第二 VectorStore」。
5. **Gate order:** Task 7 显式依赖闸 A。
