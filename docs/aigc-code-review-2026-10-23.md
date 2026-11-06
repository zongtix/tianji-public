# tj-aigc 修复清单 v4（可实施版）

- 版本：**v4**，2026-10-23。v1 / v2 / v3 均已作废，**只按本文件执行**。
- v4 的变更只有四点：A-1 措辞收紧 + 超时测试、A-2 状态语义重设计（`null` 而非 `0`）、A-3 验收改成按调用边界、§B 新增 B-10。
- 审查方式：静态只读。未修改代码、未提交、未运行测试、未核查服务器端口。
- **修复范围到此为止，不再扩大。**

---

## 0. 执行约束

1. 只改 §A 列出的位置，一条一个 commit（`fix(aigc): ...`）。
2. **§B 列出的十条禁止执行**——特别是：`afterId` 跳过坏项、`/ps/notify` 处改成始终覆盖来源头、用 `long stalled = 0` 表示未停滞、加 `.cache()`、改 `SimpleLoggerAdvisor` 构造参数、删 embedding 模型护栏、改 `tj.ai.memory.max`、给 `findConversationIds` 加 LIMIT、`profiles.active` 改占位符、历史接口加空消息过滤。
3. 不改三个 advisor 的 `getOrder()`（记忆 → 卡片 → 澄清 的顺序是刻意设计的）。
4. 不改 `ChatGenerationStore` 的 Lua 脚本。
5. 不改任何 `prompts/*.txt`。
6. 不引新依赖、不顺手格式化整文件、不重构 `AgentDispatcher` / `ChatServiceImpl` 结构。
7. 每条改完在 commit body 写：改了什么 / 为什么 / 影响面 / 怎么验证。

---

## A. 修复项（5 条，按优先级）

| ID | 级别 | 性质 | 一句话 | 位置 |
|---|---|---|---|---|
| A-4 | 🟡 | 正确性（**优先**） | 记忆读取失败被包装成"路由失败"，错误提示与排障方向都是错的 | `AgentDispatcher.java:56-60` |
| A-3 | 🟡 | 性能 | 语义检索 stream 内逐门课程 Feign 调用（N+1 RPC） | `CourseTools.java:82-86` |
| A-1 | 🟡 | 防御性 | 同步 branch 无超时 `block()` 遇到 `Mono.never()` 会永久挂线程 | `KnowledgeRetrievalAdvisor.java:49-52` |
| A-2 | 🟡 | 可观测性 | 巡检契约破坏后静默停滞，无人知晓（**不是漏数据，是看不见**） | `CourseIndexSyncTask.java:25-47` |
| A-5 | 🔵 | 维护性 | 服务层缺归属防御 + conversationId 拼接散在三处（**非漏洞**） | `ChatSessionServiceImpl.java:150,202-206` + `ChatService.java:18` |

**A-4 是唯一一处「一旦触发就必然给出误导性提示」的代码路径**——注意这是代码推理，不是"生产已经发生过"的结论（静态审查证明不了运行时是否触发过）。其余四项是潜在缺陷 / 性能 / 维护性，不要按 🔴 的紧迫度高估。

---

### A-4 🟡 记忆读取失败被伪装成路由失败【优先修】

**位置**：`tj-aigc/src/main/java/com/tianji/aigc/agent/AgentDispatcher.java:56-60`

**现状**
```java
return Mono.fromCallable(() -> context.withHistory(memory.get(context.conversationId())))
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorMap(RoutingException::new);      // ← 读 DB 失败也被归到路由
```

**根因**
`memory.get()` 失败（MySQL/Redis 抖动）会被 `ChatServiceImpl.java:92` 的 `isRoutingFailure` 命中，前端收到"暂时无法判断你的需求，请补充说明"——用户照提示重试永远失败，运维也只会去查模型路由。

**修复要求**
删掉 `readHistory` 的 `.onErrorMap(RoutingException::new)`，异常原样上抛，走 `ChatServiceImpl` 的兜底 `Flux.error`。不新增异常类型，不改 `ChatServiceImpl` 的错误分支。

**验收**
- `AgentWorkflowTest` 全绿。
- 单测：mock `ChatMemory.get` 抛 `RuntimeException` → 断言 `AgentDispatcher.isRoutingFailure(error) == false`。

**影响面**：该场景错误码由 `AGENT_ROUTING_FAILED` 变为系统错误。commit body 注明。

---

### A-3 🟡 语义检索 N+1 RPC（批量接口必须保留全部字段）

**位置**：`tj-aigc/src/main/java/com/tianji/aigc/tools/CourseTools.java:82-86`

**现状**
```java
List<SemanticCandidate> candidates = ids.stream().filter(courses::containsKey).map(id -> {
    var detail = courseClient.getCourseBaseInfoById(id);          // ← stream 内同步 Feign
    if (detail == null || !id.equals(detail.getId())) return null;
    return new SemanticCandidate(card(courses.get(id), null), CourseInfo.of(detail));
}).filter(java.util.Objects::nonNull).toList();
```

**根因**
一次 `searchCoursesByGoal` 调用 = 1 次向量检索 + 1 次 `getSimpleInfoList` + **最多 5 次 `getCourseBaseInfoById`**，串行阻塞在模型工具调用线程。

**⚠️ 批量时不能只 `selectBatchIds` 主表**

`tj-course/src/main/java/com/tianji/course/service/impl/CourseServiceImpl.java:483-496` 的 `getBaseInfoById` 实际组装了三部分：
```java
Course course = baseMapper.selectById(id);
CourseBaseInfoDTO result = BeanUtils.toBean(course, CourseBaseInfoDTO.class);
CourseContent content = courseContentMapper.selectById(id);
if (content != null) {
    result.setUsePeople(content.getUsePeople());
    result.setDetail(content.getCourseDetail());
}
result.setCatalogue(queryOutlineTitles(List.of(id)).getOrDefault(id, List.of()));
```
只查主表 → `usePeople`（适用人群）、`detail`（正文详情）、`catalogue`（正式目录）全为空 → 即使 DTO 类型没变，**模型拿到的推荐依据会变少**，卡片质量下降且不易察觉。

**修复要求**
1. `tj-course` 增加 `POST /courses/base-info/batch`（入参 `List<Long>`，出参 `List<CourseBaseInfoDTO>`），内部**批量**组装三部分：
   - `baseMapper.selectBatchIds(ids)` → 主表字段
   - `courseContentMapper.selectBatchIds(ids)` → `usePeople` / `detail`（`course_content.id` 即 courseId）
   - `queryOutlineTitles(ids)` → `catalogue`（该方法**已接受 `List<Long>`**，直接复用，不要新写）
2. `CourseClient` 加对应 Feign 方法；`CourseTools` 改调批量，**保持 `SemanticCandidate` 结构与 `@Tool(description=...)` 一字不改**。
3. **按 `ids` 重建顺序**：批量查询返回的是 DB 顺序（通常按 id），而 `ids` 是向量相似度顺序。用 `Map<Long, DTO>` 回填后按 `ids` 遍历，否则候选顺序被打乱、推荐结果悄变。
4. 入参为空时返回空列表，不抛异常。

**验收**
- **字段对照单测（必做）**：同一批 courseId，分别调 `getBaseInfoById` 与批量接口，断言 `usePeople`、`detail`、`catalogue` 逐字段相等（`catalogue` 比章节标题与顺序，不比对象引用）。
- **顺序单测（必做）**：断言返回的 `SemanticCandidate` 顺序与入参 `ids` 的相似度顺序一致，而不是按 courseId 升序。
- `CourseToolsTest`、`CourseSemanticToolsTest` 全绿。
- **RPC 次数按调用边界断言，不要写绝对总数**（`recommendCourses` 之后还会再核实一次课程状态，总数随链路变化）：
  - 在一次 `searchCoursesByGoal` 内：`getSimpleInfoList` ×1、`getBaseInfoBatch` ×1、`getCourseBaseInfoById` **×0**
- 卡片数断言 **≤ 3**（上限 3，候选不足时更少，**不要断言"一定 3 张"**）。

**影响面**：跨 `tj-api` + `tj-course`，commit body 写清接口契约与三表组装说明。

---

### A-1 🟡 同步 branch 永久阻塞

**位置**：`tj-aigc/src/main/java/com/tianji/aigc/knowledge/KnowledgeRetrievalAdvisor.java:49-52`

**现状**
```java
@Override
public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    return prepare(request, () -> isActive(request)).block();     // 无超时
}
```

`prepare()` 内共三处 `Mono.never()`（`:58`、`:60`、`:67`），同步与流式路径**共用** `prepare()`。

**能证明什么、不能证明什么**
- ✅ **代码可证**：当前业务聊天只经 `adviseStream`，`before()` 没有调用方。
- ❌ **静态审查不可证**：生产从未发生线程池耗尽。部署后是否有人走过非流式路径、是否有其他调用方，要看监控或日志，不能从代码断言。
- **语义提醒**：`Mono.never()` 本身永不结束；所谓"静默停止"是外层 `takeUntilOther` **取消**它的结果，不是 `never` 自己完成。

**根因**
`before()` 用无参 `.block()` 等一个可能永不完成的 `Mono` → 一旦走 `adviseCall`（`ChatClient.call()`），线程永久挂死，挂的是 `Schedulers.boundedElastic()`，池耗尽后所有 `redisCall()` 和记忆读写一起阻塞。

**修复要求**

**不要改 `prepare()` 里的任何一处 `Mono.never()`** —— 改任一处都会改变流式路径语义（原本静默结束，会变成错误信号）。只在同步入口加超时，且超时值必须**可注入**，否则无法测：

```java
private static final Duration DEFAULT_SYNC_TIMEOUT = Duration.ofSeconds(30);
private final Duration syncTimeout;

// 原有构造保持不变，委托到新构造
public KnowledgeRetrievalAdvisor(VectorStore vectorStore, SearchRequest searchRequest,
                                 PromptTemplate promptTemplate, KnowledgeQueryRewriter rewriter) {
    this(vectorStore, searchRequest, promptTemplate, rewriter, DEFAULT_SYNC_TIMEOUT);
}

// 包级可见，仅供测试注入短超时
KnowledgeRetrievalAdvisor(VectorStore vectorStore, SearchRequest searchRequest,
                          PromptTemplate promptTemplate, KnowledgeQueryRewriter rewriter,
                          Duration syncTimeout) {
    // ... 原有五个字段赋值 ...
    this.syncTimeout = syncTimeout;
}

@Override
public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    return prepare(request, () -> isActive(request))
            .timeout(syncTimeout)
            .block();
}
```
确认 `java.time.Duration` 已 import，没有就加。

**验收**
- 原有测试全绿：`KnowledgeChatRoutingTest`、`KnowledgeChatClientTest`、`AgentWorkflowTest`。
- 流式路径行为零变化（停止生成时仍静默结束，不发错误事件）。
- **同步取消场景的超时测试（必做，不能只靠原有测试全绿）**：
  - 用包级构造注入 `Duration.ofMillis(50)`；
  - 构造 `GENERATION_ACTIVE` 对应的 `BooleanSupplier` 返回 `false`（即进入 `Mono.never()` 分支）；
  - 调用 `before(...)`，断言：① 在合理时间内返回、不阻塞；② 抛出的异常**链中**含 `TimeoutException`（`block()` 会包装成 `ReactiveException`，用 `Exceptions.unwrap` 或遍历 `getCause()` 判断，不要直接断言顶层类型）；
  - **不要用 30 秒真实等待**。

**影响面**：仅同步调用分支；新增一个包级构造，不影响 `KnowledgeStoreConfig` 的现有调用。

---

### A-2 🟡 巡检契约破坏后静默停滞

**位置**：`tj-aigc/src/main/java/com/tianji/aigc/knowledge/course/CourseIndexSyncTask.java:25-47`

**现状**
```java
long last = afterId;
for (var item : page) {
    Assert.state(item != null && item.getId() != null && item.getId() > last, "课程分页未按 ID 递增");
    last = item.getId();
}
...
} catch (Exception failure) {
    log.warn("课程索引巡检分页失败 afterId={}", afterId, failure);   // afterId 不动
}
```

**根因（已修正 v1/v2 的判断）**

上游是 **keyset 分页**：
```java
// tj-course/src/main/java/com/tianji/course/mapper/CourseMapper.java:27
@Select(INDEX_SNAPSHOT + "WHERE c.id>#{afterId} ORDER BY c.id LIMIT #{size}")
List<CourseIndexInfoDTO> selectSemanticIndexPage(long afterId, int size);
```

- 正常情况乱序**不可能发生**（`WHERE id > afterId ORDER BY id` 已保证严格递增）。
- 一旦出现乱序，是**上游契约被破坏**（改了 SQL、加了联表排序、DTO 映射串了 ID），不是"某条数据坏了"。
- ⚠️ v2 的"跳过坏项保证前进"是错的：跳过 ID=10 后游标走到 20，之后只查 `>20`，课程 10 **永久不会再被同步**。
- v1 的"catch 里 `afterId = 0`"也不对：重置会重扫全表，坏页仍在，等于空转。

**真正的问题是"停滞不可见"，不是"停滞"本身。** 契约破坏时正确行为就是停止推进（不写错误数据），但必须让人知道。

**修复要求**

保持"不推进、不跳过、不重置"，补可观测性。**两个语义陷阱必须先解决**：

1. **不能用 `0` 表示"未停滞"**：`afterId` 初始值就是 0，第一页失败时 `stalled = afterId` 恰好也是 0 → 最该被抓住的第一页停滞永远显示"正常"。必须用 `Long` + `null`。
2. **时间戳不能叫 `lastSuccessAt`**：单门课程失败被内层 `catch` 吞掉，整页全部失败也会执行到它。它测的是"整页处理完成"，不是"索引同步成功"。

```java
/** null = 本轮未停滞；非 null = 停滞在该游标（**可能为 0L**，不能用 0 判断）。 */
private volatile Long stalledAfterId;

/** 最近一次整页处理完成时间；null = 从未完成。⚠️ 不代表课程索引已同步成功。 */
private volatile LocalDateTime lastPageCompletedAt;

/** 最近一页中同步失败的课程数；用于区分"分页成功"与"索引全部失败"。 */
private volatile int lastPageSyncFailures;

private int consecutivePageFailures;

@Scheduled(...)
public synchronized void reconcile() {
    try {
        var page = courses.querySemanticIndexPage(afterId, pageSize);
        Assert.state(page != null && page.size() <= pageSize, "课程分页响应无效");
        long last = afterId;
        for (var item : page) {
            Assert.state(item != null && item.getId() != null && item.getId() > last,
                    "课程分页违反 id 递增契约，停止推进以避免漏同步，afterId=" + afterId);
            last = item.getId();
        }
        int failed = 0;
        for (var item : page) {
            try { sync.synchronize(item.getId()); }
            catch (Exception failure) {
                failed++;
                log.warn("课程索引同步失败 courseId={}", item.getId(), failure);
            }
        }
        afterId = page.size() < pageSize ? 0 : last;
        lastPageCompletedAt = LocalDateTime.now();
        lastPageSyncFailures = failed;
        stalledAfterId = null;                  // ← null 表示正常
        consecutivePageFailures = 0;
        log.info("课程索引巡检完成 count={}, failed={}, nextAfterId={}", page.size(), failed, afterId);
    } catch (Exception failure) {
        consecutivePageFailures++;
        stalledAfterId = afterId;               // ← 可能为 0L，靠 null/非 null 区分
        // 首次必打，同状态每 10 次再打；避免每 60 秒刷屏，但保证看得见
        if (consecutivePageFailures == 1 || consecutivePageFailures % 10 == 0) {
            log.error("课程索引巡检停滞 afterId={}，连续失败 {} 次；持续出现请检查 CourseMapper.selectSemanticIndexPage 的排序契约",
                    afterId, consecutivePageFailures, failure);
        }
    }
}

public Long stalledAfterId() { return stalledAfterId; }
public LocalDateTime lastPageCompletedAt() { return lastPageCompletedAt; }
public int lastPageSyncFailures() { return lastPageSyncFailures; }
```

**若之后要做"索引健康度"，用 `lastPageSyncFailures` 与索引文档数对比，不要用 `lastPageCompletedAt`。**

**验收**
- 单测 1（**关键**）：**第一页失败**（`afterId` 初始为 0）→ 断言 `stalledAfterId` 等于 `0L` 而**不是 `null`**，`afterId` 不变。
- 单测 2a：**从未成功完成过任何一页**（刚启动，`afterId == 0`）遇到分页失败 → `lastPageCompletedAt` 为 null（没有任何一次完成记录）。
- 单测 2b：**已完成过至少一页**后再遇到乱序 → `afterId` 不变、`consecutivePageFailures == 1`、`stalledAfterId` 等于旧 `afterId`、`lastPageCompletedAt` **保持上一次的值、不被清空**（停滞不清除历史完成时间）。
- 单测 3：连续 10 次失败 → 断言 `log.error` 只被调用 2 次（第 1 次和第 10 次）。
- 单测 4：正常一轮 → `stalledAfterId == null`、`lastPageCompletedAt` 非空、`lastPageSyncFailures == 0`。
- 单测 5：整页课程全部同步失败 → `lastPageCompletedAt` **仍然更新**、`lastPageSyncFailures == page.size()`（明确"分页成功 ≠ 索引成功"）。
- **不要**写"跳过坏项后后续课程被处理"的用例。

**影响面**：巡检行为不变（仍然停住），新增三个只读状态字段。不要把 `stalledAfterId != null` 做成自动恢复开关。

---

### A-5 🔵 服务层归属防御 + conversationId 规则去重（维护性）

**这不是安全漏洞，也不会产生用户可见的错误。**

`conversationId = userId + "_" + sessionId`，A 用户传 B 的 `sessionId` 拼出的是 `A_Bsession`（空键），读不到 B 的数据；而且 `controller/SessionController.java:62` 已经先调 `requireOwnedSession`，请求到不了服务层就会 400。所以**不存在"越权"或"前端拿到空历史"**（v1/v2 的说法不成立）。

真实价值只有两条：
1. **未来防御**：服务层自己不校验，一旦有内部调用（导出、管理后台、定时清理）绕过 Controller，就没人兜底。
2. **去重**：`conversationId` 拼接散在三处（`ChatService.java:18` 隐式 `UserContext.getUser()`、`ChatSessionServiceImpl.java:150` 手写、`ChatServiceImpl.java:57` 隐式调用），规则一改就静默错位（删了会话历史还在）。

**修复要求**
```java
// ChatService：新增显式重载，保留原方法委托（controller 与 ChatServiceImpl 仍在用）
static String conversationId(Long userId, String sessionId) {
    return userId + "_" + sessionId;
}

static String getConversationId(String sessionId) {
    return conversationId(UserContext.getUser(), sessionId);
}
```
- `ChatSessionServiceImpl:150` → `chatMemory.clear(ChatService.conversationId(session.getUserId(), sessionId))`
- `ChatSessionServiceImpl.queryBySessionId` → 开头加 `ChatSession session = requireOwnedSession(sessionId);`，改用新方法
- `ChatServiceImpl:57` → 改用 `conversationId(session.getUserId(), sessionId)`

**验收**
- `ChatSessionHistoryTest` 全绿。
- 单测：直接调 `chatSessionService.queryBySessionId(B 的 sessionId)`（不走 HTTP）→ 抛 `BadRequestException`。

**影响面**：`queryBySessionId` 多一次索引查询，可忽略。

---

## B. 已撤回 / 禁止执行（十条，附核验证据）

| # | 错误建议 | 核验证据 | 为什么禁止 |
|---|---|---|---|
| B-1 | `findConversationIds`"全表扫描"→ 加 `LIMIT 1000`；`max` 100→30 | `db/chat-memory.sql:8` 有 `UNIQUE KEY uk_conversation_position (conversation_id, message_index)` | 索引判断错误；`LIMIT` 会截断枚举；**`max` 会经 `saveAll` 全量替换物理删除超窗消息**，直接减少 `queryBySessionId` 能查到的历史（不只模型上下文） |
| B-2 | 给 `stopped` 加 `.cache()` | `ChatServiceImpl.java:73` 用 `concatWith` 串行；`takeUntilOther` 在主流完成时取消 other | 双循环不存在；`.cache()` 反而让轮询在无订阅者时存活 |
| B-3 | `profiles.active` → `${SPRING_PROFILES_ACTIVE:dev}` | Spring Boot 外部配置优先级高于 `application.yml` | 环境变量本来就覆盖；占位符默认值仍是 dev，不解决"忘配"。归部署清单 |
| B-4 | `new SimpleLoggerAdvisor(1)` 只记 1 条 | `spring-ai-client-chat-1.1.2-sources.jar`：`public SimpleLoggerAdvisor(int order)` | 参数是**执行顺序**不是条数。日志暴露的顾虑仍存在，但原修法无效；正确做法需另行验证 |
| B-5 | 删 `CourseIndexSyncService.java:29` 的模型断言 | 同维度 ≠ 兼容向量空间，混用会静默错误召回；`CourseRetrievalConfig.java:25`、`scripts/aigc-es/configure-knowledge.py:65` 有同款限制 | 护栏必须保留。换模型必须重建索引 |
| B-6 | 历史接口过滤 `content=""` 消息 | `CourseCardMetadataAdvisor.java:52` 是唯一空内容来源且必带 `COURSE_CARDS` | 等于没修，且前端靠这条消息渲染卡片 |
| B-7 | "任意登录用户可伪造 Feign 头绕过鉴权" | `RequestIdRelayFilter.java:30-32` 除 `/ps/notify` 外一律 `b.header(...)` **覆盖** | 经网关无法伪造。风险转部署侧（§C） |
| B-8 | v2 的"跳过坏项保证游标前进" | `CourseMapper.java:27` 是 keyset 游标（`WHERE c.id>#{afterId} ORDER BY c.id`） | **跳过低 ID 会使该课程永久不再被同步**。乱序即上游契约破坏，不能靠丢弃数据"恢复" |
| B-9 | v2 的"`/ps/notify` 处改成始终覆盖来源头" | `WrapperResponseBodyAdvice.java:24` 用同一 header 判断是否包装响应；该分支为支付回调**保留原始响应格式** | 覆盖会破坏支付回调协议。刻意设计，不要改 |
| **B-10** | **v3 的 A-2 用 `long stalledAt = 0` 表示"未停滞"** | `afterId` 初始值就是 0，第一页失败时 `stalledAt = afterId` 也等于 0 | 状态恒为"未停滞"，**第一页停滞永远看不见**——正是最需要告警的场景。必须用 `Long` + `null`，并把时间戳命名为 `lastPageCompletedAt` 而非 `lastSuccessAt`（整页所有课程同步失败时它仍会更新） |

---

## C. 待部署侧验证（不是代码问题）

**C-1 aigc-service 端口是否对公网开放**

若 `aigc-service:8096` 绑定 `0.0.0.0` 且安全组放行，攻击者可绕过网关直连，`x-request-from: feign` 即可绕过 `/chat/reply` 校验。

- 检查：`ss -lntp | grep 8096` + 安全组入站规则。
- 期望：只监听 `127.0.0.1` / Docker 内网，或安全组只放行 80/443。
- 需实际核对环境，代码判断不了。

---

## D. 已确认是刻意设计、不要"修"的地方

| 位置 | 结论 |
|---|---|
| `RequestIdRelayFilter.java:30` 的 `/ps/notify` 分支 | 为支付回调保留原始响应格式（`WrapperResponseBodyAdvice` 用同一 header 判断是否包装）。**刻意设计，保持原样** |
| `CourseIndexSyncService.java:29` 的模型断言 | 防跨模型向量混用，必要护栏 |
| `ChatGenerationStore` 全部 Lua 脚本 | 单 key 原子操作，停止/接管语义正确 |
| `KnowledgeRetrievalAdvisor.getOrder()=0` vs 记忆 advisor | 记忆在外层先加载历史，rewriter 才能看到历史 |
| `CourseCardMetadataAdvisor` 的空内容消息 | 卡片持久化载体，配合历史接口返回卡片数据 |
| `application.yml` 的 `api-key` | **当前工作副本**中为 `${...}` 占位符（已核对）。git 历史只做过一次 `git log -S'sk-'` 的单路径查询，**未做全量扫描，不能断言"从未泄露"**；如需该结论请单独执行完整历史扫描 |
| `AudioService.validateWav` / BUDGET 脚本 | 边界校验与三键原子限流均正确 |
| `CourseIndexSyncService.synchronizeOnce` | "先取版本再读快照 + CAS 重读"顺序正确 |

---

## E. 验收

```bash
mvn -q -pl tj-aigc -am -DskipTests compile
mvn -pl tj-aigc test
# A-3 涉及跨模块
mvn -q -pl tj-api,tj-course -am -DskipTests compile && mvn -pl tj-course test
```

必须全绿：
```
KnowledgeChatRoutingTest      KnowledgeChatClientTest
AgentWorkflowTest             ChatSessionHistoryTest
CourseToolsTest               CourseSemanticToolsTest
CourseIndexSyncTaskTest       ChatStopMemoryTest
```

新增必做测试（本次审查提出的）：
```
A-1 同步分支超时测试（注入 50ms，断言异常链含 TimeoutException）
A-2 第一页失败时 stalledAfterId == 0L（非 null）
A-2 整页同步失败时 lastPageSyncFailures == page.size()
A-3 批量接口字段对照（usePeople / detail / catalogue）
A-3 候选顺序与 ids 一致
```

---

## F. 结论

**v4 为可实施版本，5 条成立，范围不再扩大。**

- **A-4** 唯一一处一旦触发就必然给出误导性提示的代码路径，优先修（1 行改动）。是否在生产触发过需查日志，静态审查不作断言。
- **A-3** 唯一有明确性能收益，跨模块，需要新建批量接口。
- **A-1 / A-2 / A-5** 防御性 / 可观测性 / 维护性，可攒在一起。

四轮共判错 10 条（B-1 ~ B-10）：前 7 条是把刻意设计当成缺陷，B-8 / B-9 是上一版引入的新错误，B-10 是状态哨兵值冲突。v4 已修正 A-2 的状态语义、A-1 的可测性、A-3 的验收边界。

**v1 / v2 / v3 已作废，只按 v4 执行。**
