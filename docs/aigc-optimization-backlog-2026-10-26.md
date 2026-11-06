# 智能客服（tj-aigc）优化清单

> 生成时间：2026-10-26　｜　来源：本会话对 tj-aigc 全链路审查（入口 → 路由 → 工具循环 → RAG → 记忆 → 观测 → 并发）
> 范围：仅本文档新增，**未改动任何既有代码/配置**
> 结论依据：全部来自源码 + Maven 仓库 jar 源包 + 服务器/容器实测，每条都带 `文件:行号`

---

## 零、先记三个已核对的事实（面试可直接引用）

| 问题 | 结论 | 关键证据 |
|---|---|---|
| 天机学堂 Redis 用主从了吗 | **没有**，单机 | Nacos `shared-redis.yaml`（public + tianji-local-boot3 两个命名空间）只有 `host`/`port`；服务器 `info replication` → `role:master`、`connected_slaves:0`；无 sentinel 容器；全项目 `readFrom` 0 命中 |
| 星辰 WMS 用主从了吗 | **没有**，单机 6380 | `application-dev.yml:192-205` 单机；`RedissonConfiguration.java:57-84` 三支分支只走单机；`ShiroConfig.java:315-326` 哨兵是**预留分支未启用**；`docker exec wms-redis` → `connected_slaves:0` |
| 智能客服有 ReAct 吗 | **有，且是手写循环** | `CourseToolPreparationAdvisor.java:115-141`（for 循环 8 轮上限 + `executeToolCalls` 回灌 + 无 tool_calls 终止）、`:101` 关闭框架内部执行、`:163-180` 工具级可取消 |

补充：`jeecg.redisson.type: STANDALONE`（`application-dev.yml:322`）**没有任何代码读取**，实际模式由 `RedisProperties` 的 cluster/sentinel 是否存在决定 —— 这是死配置。

---

## 一、P0：半天内可完成，收益最高

### P0-1　路由失败无降级，整轮报废

- **位置**：`AgentDispatcher.java:86-87`、`ChatServiceImpl.java:113-118`
- **现状代码**

```java
// AgentDispatcher.java:86-87
.timeout(Duration.ofSeconds(15))
.onErrorMap(RoutingException::new);
```

```java
// ChatServiceImpl.java:113-118
if (AgentDispatcher.isRoutingFailure(error)) {
    return Flux.just(ChatEventVO.builder().eventType(ChatEventTypeEnum.ERROR.getValue())
            .eventData(Map.of("code", "AGENT_ROUTING_FAILED",
                    "message", "暂时无法判断你的需求，请补充说明或稍后重试。")).build());
}
```

- **根因**：路由是每轮聊天的串行前置调用，它超时（15s）/解析失败/模型抖动，用户一个字都拿不到。而它只是一个三分类。
- **改法**：

```java
.timeout(Duration.ofSeconds(8))
// 路由失败不报废整轮：降级到 KNOWLEDGE（无工具、无副作用、最安全）
.onErrorResume(err -> {
    log.warn("意图路由失败，降级为知识问答分支", err);
    return Mono.just(new Decision(AgentTypeEnum.KNOWLEDGE, null));
});
```

- **例外**：知识库会话不走路由（`AgentDispatcher.java:48-53` 已强制 KNOWLEDGE），不受影响。
- **工作量**：≤ 1 小时　**验收**：断掉 DeepSeek 路由调用（或 mock 抛异常），用户仍能收到正常回答。

### P0-2　README 与代码互相矛盾（最伤可信度）

- **位置**：`tj-aigc/README.md:3`、`README.md:19`
- **冲突点**

| README 写的 | 代码实际的 |
|---|---|
| `:3`「首批不接入课程检索、RAG、工具调用或语音」 | 全部已实现（`CourseToolPreparationAdvisor`、`KnowledgeRetrievalAdvisor`、`AudioService`） |
| `:19`「只运行一个 AI 实例，正在生成的会话保存在本机内存……未实现跨实例取消、并发协调」 | `ChatGenerationStore.java:14` 注释即「多个 AIGC 实例共享生成状态」，Redis Lua 租约 + renew + stop 全在 Redis |

- **根因**：README 停在第一版；后续能力都补进了 `knowledge.md` / `course-retrieval.md` / `memory-storage.md` / `audio.md`，唯独 README 没回头改。
- **改法**：重写三段 —— ①能力清单 ②拓扑与容量（Redis 租约支持多实例，但仍有未解决部分要写清）③已知限制与降级矩阵。
- **工作量**：≤ 1 小时　**验收**：随机抽 3 个功能，README 描述与代码一致。

---

## 二、P1：约一天，决定「效果能不能被证明」

> 说明：`docs/rag-evaluation/` 下**已有**一套扎实的评测（32 条标注、7 个 Langfuse Score Config、发布与回读脚本、A/B 实验留档）。
> 本节的改动**不是新建评测**，而是把已有评测扩到另两条链 + 给它红绿灯。

### P1-1　评测覆盖面：从「只有 RAG」扩到「路由 + 工具」

- **现状**：`docs/rag-evaluation/cases.json` 32 条全部是积分/签到知识库（单文档 7 片段），字段是 RAG 视角（`expectedPoints`/`referenceEvidence`）。
  路由与工具选择只有**不变量检查**（`verify-agent-workflow.py:125` 路由不进历史、`:133` 卡片有界、`:177` 知识会话不能切课程工具），**没有准确率指标**。
- **改法**：给 schema 增加两个可选字段，一套 runner 同时测三种能力。

```jsonc
{
  "id": "route-017",
  "split": "dev",
  "question": "第二门课适合我吗",
  "history": ["...上一轮推荐了 A、B 两门..."],
  "expectedAgent": "CONSULT",           // 新增：路由金标
  "expectedTools": ["queryCourseById"], // 新增：工具序列，前缀匹配
  "forbiddenFacts": ["热门", "报名量"]    // 复用现有字段
}
```

- **Langfuse 侧只加两个 BOOLEAN config**（沿用 `publish-langfuse-scores.py`）：

```
tj_route_correct_v1      BOOLEAN  是否命中 expectedAgent
tj_tool_sequence_v1      BOOLEAN  工具序列是否为 expectedTools 的前缀
```

- **原则**：确定性指标（路由分类、工具序列）一律程序断言，**不要用 LLM judge** —— 既贵又污染口径。沿用现有「程序能判就程序判，判不了才 categorical」的分法。
- **重点补三类现在完全没测的**：
  1. 多轮指代：「第二门」/「刚才那个」/「按这个再来几门」（对应 `route-system-message.txt:3/8/17`）
  2. 澄清分支：`agent=null` + `clarification` 的触发（`route-system-message.txt:9/10`）
  3. 空推荐：资料不足时提交 `recommendCourses([])`（baseline 文档里的失败样本正是此类，值得固化成 case）
- **工作量**：约半天（20-40 条标注 + 断言）　**验收**：能输出 `route_accuracy` / `tool_seq_match` 两个数字。

### P1-2　评测是手动看报告，缺门禁

- **现状**：`scripts/rag-evaluation/summarize.py` 跑完出 JSON/报告，人看。无阈值、无退出码、无「改动前后自动 diff」。
- **已有的好底子**：`summarize.py:27/45` 已经带 `datasetVersion` / `configVersion` / `contentHash`，基准比较所需元数据齐全。
- **改法**：加 `--baseline` 参数 + 阈值 + 非 0 退出码。

```
baseline.json  vs  current.json
  route_accuracy   0.92 → 0.95   PASS
  tool_seq_match   0.85 → 0.90   PASS
  hit@3            0.88 → 0.90   PASS
  cite_precision   0.95 → 0.86   FAIL (-0.09 跌破阈值)  → exit 1
```

- **不需要进 CI**（个人项目日请求量低，CI 无统计意义），本地「改动前后必跑一条命令」即可，关键是有红绿灯。
- **工作量**：约 2-3 小时　**验收**：故意降一条指标，脚本 exit 1。

### P1-3　知识库语料太少，recall 没有统计意义

- **现状**：`points-v1` 只有 `knowledge/tianji-points.md` 一份资料、7 个片段 —— 这个规模下随便召回都能中。
- **改法**：用现成的 `CourseDocumentService` 导入链路再加 1-2 份课程目录类文档，使 `hit@3` 具备区分度。
- **工作量**：约 2 小时（写资料 + 导入 + 跑基线）　**验收**：同一问题在不同文档间能产生可区分的召回结果。

---

## 三、P2：稳定性与体验补强（按性价比排序）

| # | 问题 | 位置 | 根因 | 改法 |
|---|---|---|---|---|
| P2-1 | 模型调用零重试，一次抖动整轮失败 | `application.yml:49-50` `retry.max-attempts: 1` | 为省成本关掉自动重试（正确），但没做**首 token 前**的安全重试 | 错误映射里区分：首 token 前失败 → 重试 1 次；已出 token → 走现有 `saveStoppedAnswer` 保留部分输出 |
| P2-2 | 工具循环打满 8 轮直接抛异常 | `CourseToolPreparationAdvisor.java:40` `MAX_TOOL_ROUNDS = 8`、`:126` throw | 熔断策略选错：应降级出答案而非报错 | 上限提为配置项（`tj.ai.tools.max-rounds`）；打满时 `return finalPrompt(...)` 用已有资料出答案 |
| P2-3 | 卡片在文字流结束后才发，异常时卡片丢失 | `ChatServiceImpl.java:196-203` | `concatWith` 在流末；若文字生成期出错，`onErrorResume`（`:96`）截断，卡片事件不执行 → **工具已提交推荐但用户看不到** | 在 `finalPrompt` 开始生成前就把卡片事件推给前端（推荐已确定） |
| P2-4 | RAG 在 Advisor 里 `.block()`，占用共享调度器 | `KnowledgeRetrievalAdvisor.java:84` | `BaseAdvisor.before()` 是同步钩子，只能 block；但 `boundedElastic` 是共享的，ES+百炼最长 25s 会占满线程 | 给 RAG 单独 `Schedulers.newBoundedElastic(..., "rag-")`，或加并发限流 |
| P2-5 | 记忆是裸窗口，无摘要压缩 | `SpringAIConfig.java:44-48`、`application.yml:memory.max: 100` | 100 条消息只挤掉早期上下文，长会话成本线性增长 | 超过阈值时对早期消息做滚动摘要，保留结构化事实 |
| P2-6 | 有埋点但无按 agent 的成本口径 | `ChatServiceImpl.java:67-68`（`chat.request` span） | 只有总耗时，回答不了「哪个分支最贵」 | 加低基数 tag：`chat.agent` + `chat.tokens.total`，产出「RECOMMEND 占 X% 成本」的结论 |
| P2-7 | 每轮固定两次串行 LLM 调用，首 token 延迟叠加 | `RouteAgent` + `AbstractAgent` 串行 | 路由与主回答用同一个 `deepseek-flash` | 路由换更小模型；或评估「工具即路由」（合并工具集让模型自选），省一整跳 |

---

## 四、明确不做（避免过度工程，面试要能说出理由）

| 不做的事 | 理由 |
|---|---|
| 灰度发布 / A-B 实验平台 | 单实例、日请求量低，没有统计意义 |
| 多区域部署 / 跨可用区容灾 | 无真实用户，成本与复杂度不匹配 |
| Prompt 版本管理平台 | 已有 `prompts/*.txt` + Nacos 覆盖 + `prompt-version` 标记，够用 |
| 私有知识库 / 任意文件上传 | 无业务驱动（与 `knowledge.md:21` 的既有边界一致） |
| 评测进 CI 阻塞合并 | 真实模型调用有费用与波动，本地手动跑 + 阈值即可 |
| 把 8 轮上限调大来「提升效果」 | 打满 8 轮说明模型在重复查同类资料，调大只会更贵，应做熔断降级 |

---

## 五、验收清单（改完逐条打勾）

- [ ] 路由 mock 抛异常 → 用户仍收到正常回答（P0-1）
- [ ] README 三段重写完毕，能力/拓扑/限制与代码一致（P0-2）
- [ ] `cases.json` 含 `expectedAgent` / `expectedTools`，能输出 `route_accuracy`、`tool_seq_match`（P1-1）
- [ ] Langfuse 出现 `tj_route_correct_v1` / `tj_tool_sequence_v1` 两个 config 的实际分值（P1-1）
- [ ] `summarize.py --baseline` 在指标下跌时 exit 1（P1-2）
- [ ] 知识库语料 ≥ 2 份文档，`hit@3` 具备区分度（P1-3）
- [ ] 首 token 前 5xx 能自动重试 1 次（P2-1）
- [ ] 工具 8 轮打满时用已有资料出答案，不再 throw（P2-2）
- [ ] 文字流中途出错时卡片仍能送达（P2-3）
- [ ] Langfuse 能按 agent 维度看 token 成本（P2-6）

---

## 六、面试话术（基于本次核对结论）

**「你这个智能客服跟直接调 API 有什么区别？」**

> 差别不在功能，在三件直接调 API 拿不到的事。第一，**模型不能自己决定查什么** —— 工具按分支白名单分发，咨询分支拿不到推荐写工具（`ConsultAgent.java:16`）；第二，**过程不能污染结果** —— 工具循环单独跑完再出答案，中间「我先查一下」既不进可见流也不进记忆（`CourseToolPreparationAdvisor.java:101/154`）；第三，**失败要分级** —— 路由挂了降级纯问答，工具打满用已有资料出答案，只有检索失败才明确报错，因为那时代码里已无可信依据。至于 ReAct 循环本身，Spring AI 框架默认就有，我关掉自己写是为了拿到上面这三项控制权。

**「你怎么衡量效果？」**

> 三条链各一套标注集，跑完出 diff 看阈值。RAG 那条已有 32 条带 `expectedOutcome`/`forbiddenFacts` 的标注，分数直接写进 Langfuse 原生 Score（7 个 config，`pass`/`fail`/`not_applicable` 三值，`not_applicable` 不算通过）。路由和工具两条正在扩，用 BOOLEAN 做精确匹配而不是模型裁判 —— 裁判只该用在程序判不了的地方，否则又贵又污染口径。

**「最大的不足是什么？」**

> 评测里内容正确性目前仍是 `codex_source_review`，`independentHumanReview=pending`，也就是没有独立人工校准，模型裁判也没启用。所以我能保证「流程对、事实有出处」，但不能宣称「答案质量已被验证」。这一点我写在报告里，没有藏。

---

## 附：本次审查中发现但**不需要改**的点

- 天机学堂 / 星辰 WMS 的 Redis 都是单机。**不需要读写分离**：读压力来自会话与缓存，加从库只分摊读、解不了单点；真要高可用是「主从复制 + 哨兵」。而且主从延迟会让「刚写入的状态读不到」，在读己之写的业务上反而生 bug。
- `RouteAgent.java:39` 的 `toolChoice("none") + internalToolExecutionEnabled(false)` 是**刻意设计**（路由不该调工具），不是「模型不支持工具」。
- Spring AI 1.1.2 的 `internalStream` 本身会递归循环工具调用（`OpenAiChatModel.java:367-390`），项目显式关掉它属于**主动接管控制权**，不是缺陷。
