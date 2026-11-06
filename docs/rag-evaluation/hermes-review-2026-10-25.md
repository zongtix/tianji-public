# Hermes 独立复核：两例 RAG 回答失败

日期：2026-10-25。执行者：Hermes（丁香）。任务来源：`hermes-independent-review-2026-10-25.md`。
性质：**只读分析**。未修改项目代码/配置/提示词，未调用项目模型 API，未访问凭据或配置中心，未写索引、未提交、未推送、未部署。
唯一写入：本文件（用户显式授权落盘，供 Codex 接手）。

数据来源优先级：`results/evidence-v3-2026-10-25/*.json` 真实输出 → 源码/资源文件 → `evidence-v3-live-2026-10-25.md` 的陈述。
本复核**不预设** Codex 的归因正确；对被推翻的判定直接标注。

---

## 0. 结论先行

1. **两例失败不是同一个原因，不能用单一"消息结构"假设同时解释。** Codex 观察到的"问题/资料/约束同在一条用户消息"最多解释 points-04 里**一句话的措辞**（把模板禁令说成"资料要求"），解释不了实质拒答。
2. **points-04（v3+4）是可证实的模板自相矛盾**：`knowledge-retrieval-v3-evidence.txt:16` 把"分别查到两个类型规则也不能断言互不影响"写成**无前提的绝对禁令**，而本轮资料 chunk 2 恰好明确写出了该关系。模型逐字引用资料后，用模板禁令推翻资料。这是提示词缺陷，不是模型幻觉。
3. **points-03 的失败主要来自题设与资料互斥（报告未提）**：按资料"单次 10 分 + 上限 50 + 只补足到上限"，当天课程学习积分只能是 10 的倍数，**45 分不可达**；且资料类型名是"课程学习"，用户说的是"学习积分"。三组输出（含 v1 默认）都独立卡在这一点 → **"v3 已知条件保留 0/2" 是事实，但不能归因给 v3**。
4. 最值得先做的是**给 v3 那条禁令加适用前提**（一个文件、两句话），并把 points-03 题设修正后再谈归因。消息结构假设缺证据，见 §3-H2 的反证。

---

## 1. 请求组装：源码级核实（可重建，但不是抓包）

| 环节 | 结论 | 证据 |
|---|---|---|
| 原问题是否被替换/丢失/重复 | **不丢失、不重复**。模板渲染后用 `Prompt.augmentUserMessage(text)` **替换**最后一条 user 消息，原问题只通过 `{query}` 重回模板 | `KnowledgeRetrievalAdvisor.java:170-176`（生产 assemble）与 `:134-137`（legacy 路径）；`spring-ai-model-1.1.2` 源码 `Prompt.augmentUserMessage(String)` = `userMessage.mutate().text(newUserText).build()` |
| 历史是否参与 | 两例 `history: []`，评测只加 System + 1 条 User | `cases.json` points-03/04；`RagEvaluation.java:144-148` |
| 系统消息全文 | **可确认**：`sha256(common-system-message.txt + "\n" + knowledge-system-message.txt, {now}→2026-10-24 12:00:00)` = `daa4938b1aec0cf8d7ad0819b25a9986baae763b4fc3e0f0b778b7415befb6f6`，与 `summary.json:32`、三份结果 `systemPromptHash` 完全一致 ⇒ Nacos 下发的就是仓库这两份文件，可全文引用 | 见附录 A 的复现命令 |
| 资料片段 | **可逐字重建**：md 做 LF 归一后 `sha256 = b0be0d634823a1f33ab9a78208f5cc708e48c7235371d6a2e38c8921aa50a91d`，与各片段 `contentHash`/`dataVersion` 一致；按 `KnowledgeDocumentService.java:60-80`（按 `^## ` 分节 + TokenTextSplitter 600）→ 7 片段 | 见附录 B 的片段映射 |
| 最终结构 | `[SystemMessage(common+role), UserMessage(模板渲染结果)]`，无第二系统消息、无历史、无工具 | `RagEvaluation.java:151-153` + `RagEvaluation.java:144-148` |
| 生成参数 | eval 硬编码 `temperature 0.0 / maxTokens 2048 / thinking disabled / toolChoice none / 无工具执行` | `RagEvaluation.java:92-95`、`:151-152` |

**token 交叉校验**：重建的 system+user ≈ 769 + 1907 = 2676 字符，DeepSeek 中文约 0.65 token/char ≈ 1740；实际 `promptTokens=1646`（`boundary-context4.json:214`）、points-04 为 1645（`:397`）。同量级一致 ⇒ 结构里**没有**重复问题或模板二次套用（否则会多数百 token）。

**缺口（必须如实标注）**：没有捕获的 HTTP 请求体（messages[] 与 options 的实际序列化）。上述结论是**源码级重建 + 哈希校验 + token 一致性校验**，**不等于线上捕获的请求**。唯一未能本地核实的部分：`KnowledgeContextSelector` 在多片段拼接时的分隔符（`System.lineSeparator()`）、以及远端 Nacos `aigc-service.yaml` 是否覆盖温度等参数（未授权访问配置中心）。

### 1.1 生产路径 vs 评测路径（差异清单）

| 项 | 生产 | 评测 | 对这两例的影响 |
|---|---|---|---|
| 系统消息 | Nacos `tj.ai.prompt.system.chat` + `agents.KNOWLEDGE`，`AbstractAgent:66-71` 拼接 | 同文本的 hash 快照（已验证一致），`{now}` 固定 | 无 |
| 历史 | `MessageChatMemoryAdvisor`（`SpringAIConfig:30` defaultAdvisors） | 无记忆 Advisor，`history: []` | 无（两例无历史），但"生产会不会被历史放大"未验证 |
| 附加 Advisor | `CourseCardMetadataAdvisor`（只加元数据，不改 prompt） | 无 | 无 |
| 温度等参数 | `application.yml` 设了 `max-tokens 2048`、`thinking disabled`，**未设 temperature**；远端 Nacos 是否覆盖未核查 | 硬编码 `temperature 0.0` | **可能有别**：生产走供应商默认温度，评测确定性 ≠ 生产确定性。不能把评测结论直接当线上行为 |
| 检索过滤 | `knowledgeBaseAccess.requireFilter`（public-base `tianji-points` → `tianji-points-guide`） | cases 的 `authorizedDocuments` 生成同义 filter | 无 |
| 流式/非流式 | 流式拼接到前端 | 流式 collect | 无 |

---

## 2. 已证实问题

### 2.1 points-04（v3 + 4 片段）：模板与资料正面冲突

- 资料 `tianji-points.md:15-16`（= chunk 2，本轮已选入，`boundary-context4.json:231-256`）：
  > 每日上限按积分类型分别计算，不是把所有类型的积分合并限制为 50 分。
  > 例如，课程学习当天获得 50 分后，仍可按签到、问答等各自规则获得其他类型积分。
- 模板 `knowledge-retrieval-v3-evidence.txt:14`：
  > ……不能自行补充不同类型之间互斥、独立、共用额度或互不影响等业务关系。
- 模板 `knowledge-retrieval-v3-evidence.txt:16`：
  > ……分别查到两个类型的规则，也不能据此断言它们之间互不影响。
- 实际输出 `boundary-context4.json:353`：先**逐字引用** chunk 2 的"按类型分别计算"和"学习当天获得 50 分后仍可按签到、问答规则获得其他类型积分"（引用正确），紧接着写：
  > 但需要注意：资料同时明确要求，不能仅凭分别查到两个类型的规则，就断言它们之间互不影响。
  然后判"严格按本轮资料……暂时不能确认"。

这句话是模板 `:16` 的逐字文本。模型把**约束**当成了资料内容，并让约束压过资料。**可证实**：模板对该禁令**没有设"资料已给出该关系"的例外**。

### 2.2 points-03：题设与资料互斥（报告未提）

- `tianji-points.md:10-13`：单次 10 分、每日上限 50、"只补足到上限" ⇒ 当天"课程学习"积分只能取 **0/10/20/30/40/50**，**45 分不可能**由该类型单独累积。
- 类型名歧义：资料称"课程学习"，仅 `:11` 用"学习积分"作简称；而 chunk 3（`:23`）"课程评价单次 10 分、上限 0"也是 10 分事件 ⇒ 资料内"再来一次 10 分事件"有两种可能，用户没指明是哪种。
- 三组独立复现同一疑问：
  - control（v1+3，`boundary-control.json` points-03）：先写"本次实际入账 5 分"，再补"资料未说明你所说的 45 分具体属于哪种类型，建议先确认积分明细中的类型"。
  - v3+3（`boundary.json` points-03）："没有说明这 45 分是否都属于课程学习类型"，于是给两个分支。
  - v3+4（`boundary-context4.json:154`）："无法确认……缺少一个关键依据：你已有的 45 分属于哪种积分类型"。
- **v1（当前默认）同样不通过** ⇒ 条件保留 0/2 全组皆然，不能作为 v3 变差的证据。
- v3 自身还给了两条互相打架的兜底：`:12`"不要求重复确认" vs `:17`"当问题依赖的关键关系或规则没有依据时……具体说明缺少哪项依据" → 模型选了后者。
- v3+4 points-03 那句 **"资料没有说明不同类型积分之间是否共用额度或互不影响"（`:154`）确实是错的**——chunk 2 已给。报告的 `evidence_reading` 判定成立。

### 2.3 对报告现有判定的核对

| 报告判定 | 我的结论 |
|---|---|
| control points-03 `fail_condition_retention` | **成立**，但需注明它**先给出"实际入账 5 分"作为主结论**，与 v3"先说不确定"不同级别 |
| v3+3 points-04 `safe_abstention_incomplete_task` | **成立**（chunk 2 缺席，`evidenceRecall=0.667`） |
| v3+4 points-04 `fail_positive_evidence_and_source_attribution` | **成立**，但根因应落在**模板**，不是模型能力 |
| v3+4 points-03 `evidence_reading` | **成立**（见 2.2 末条） |
| "已知条件保留 0/2"作为 v3 结论 | 数字成立，**归因不成立**（v1 同为 0/2，见 2.2） |
| "问题、资料、约束拼在同一条用户消息"是待验证原因之一 | **证据不足**，见 §3-H2 反证 |
| `evidence-boundary-experiments.json` 顶层 `status=prepared` | 与真实运行不符（实验已跑）。建议改为已运行状态，避免下一位读者误判 |

---

## 3. 待验证假设

**H1｜模板禁令缺"资料已给出"的例外（接近证实）**
支持：§2.1 逐字证据。反证：无。缺口：`改后是否修好` 的对照（本轮不允许调用）。

**H2｜问题/资料/约束同条用户消息导致角色混淆（Codex 假设）**
- 支持：points-04 那句"资料同时明确要求"。
- **反证（关键）**：同一结构、同一模板下，`boundary.json` points-03 正确写出"**资料明确**每日上限按积分类型分别计算，不能把不同类型合并判断"——把 chunk 2 的规则正确归给资料；`boundary-control.json` points-04 也把各类型规则正确归给资料。**结构相同、结果不同 ⇒ 结构不是充分原因。**
- 另一反证：v3 的格式约束（`:10` 注明章节）在 6/6 条输出中全部被遵守 ⇒ 约束并未整体"丧失角色"，是**选择性服从**，冲突点在内容不自洽。
- 缺口：无 HTTP 请求体。

**H3｜points-03 是题设缺陷**
支持：45 不可达 + 类型名歧义 + 三组一致复现。反证：标注方认为"学习积分"已足够明确。缺口：出题人的意图说明。

**H4｜模型指令遵循能力（deepseek-flash，thinking=disabled）**
支持：v3 明确写了"不要求重复确认"仍反问。反证：v1/v3 表现一致，更像内容冲突而非能力差异。**未做模型替换，不能断言。**

---

## 4. 一个最小修正 + 最小验证方案

### 4.1 修正：只改 `knowledge-retrieval-v3-evidence.txt` 两句话

不碰模型、召回、片段数、生成参数、消息结构。

- `:14` 改为：
  > 只有当本轮资料没有明确给出不同类型之间的关系时，才不得自行补充互斥、独立、共用额度或互不影响；资料已明确给出该关系时，应引用并据此回答，这不属于自行补充。
- `:16` 第二句改为：
  > 资料分别给出两个类型的规则、但没有说明二者关系时，不能据此断言互不影响。

**理由**：points-04 唯一可证的实质失败就锁在这句；加前提后"缺依据不肯定"仍然保留（前提不满足时禁令照旧），但不再与资料冲突。改动量最小、可回退、不与任何其他变量纠缠。

**能解决**：资料已给出跨类型关系时，模型不再用模板禁令推翻资料（预期修好 v3+4 points-04）。

**不能保证**：
- 对 points-03 无效（那是题设/术语问题，见 §2.2）；
- 不保证消除"把约束称作资料"的措辞（若仍出现，才轮到结构假设成立）；
- 有**过度放行**风险：模型可能在真缺依据时也断言互不影响 → 必须用缺依据组守住。

### 4.2 最小对照实验（下述未执行，不涉及额外 API 调用）

固定项：索引 `tj_aigc_knowledge_v4_1024`、`dataVersion b0be0d63…`、mode B、系统提示词 hash `daa4938…`、temperature 0、maxTokens 2048、thinking disabled、toolChoice none、同 `cases.json`。每格 1 次（供应商不保证温度 0 确定性，关键格追加 1 次）。

| 臂 | 唯一变量 | 用例与片段 | 预期结果 |
|---|---|---|---|
| A0 基线 | 无（复用 2026-10-25 结果） | points-03、points-04 | 已知：p03 反问类型；p04（4 片段）拒答 |
| **A1** | 模板两句 | (a) p04 片段 `3,6,4,2`；(b) p04 片段 `3,6,4`（**无** chunk 2）；(c) p03 原样 | (a) 答"能，按类型分别计上限，签到/问答按各自规则"并引用章节 2；(b) **仍明确说不确定**并指出缺少类型间关系依据；(c) 预期不变（副作用对照） |
| **A2** | 题设文本 | p03 改为"这 45 分都是课程学习积分，再来一次 10 分事件会入账多少？"（或 45→40），v3 不动 | 直接给 5 分并说明上限补足 |
| **A3** | 消息结构 | v3 措辞不动，把 `:9-:18` 的约束段移入系统消息，用户消息只保留 问题 + `<knowledge_context>` | 若 H2 成立：p04、p03 都变好，且不再把约束称作资料 |

**判定 / 推翻规则**
- A1 修好 p04 两格且 A3 无额外收益 → 采纳 A1（最小），**H2 降级**。
- A3 独有收益（p03 也变好）→ 结构确是真变量，再谈 A1。
- A2 修好 p03 → points-03 归因是题设，与 H1/H2 无关。
- A1/A2/A3 都不改变 p03 → 才进入**唯一变量=模型**的单次对照（同请求换 chat 模型，需另行授权）。
- 验收口径：三格同时看（已知条件保留 / 缺依据不肯定 / 有依据能回答），任一格退化即判该臂失败；**不以"拒答更多"或"出现 5 分"判通过**；不以相关性/融合分数当作证据充分性。

---

## 附录 A：哈希与结构复现方法（只读）

```bash
# 1) 系统消息 hash（应输出 daa4938b1aec0cf8…）
cd tj-aigc/src/main/resources/prompts
python3 -c "
import hashlib
c=open('common-system-message.txt','rb').read()
r=open('knowledge-system-message.txt','rb').read()
print(hashlib.sha256((c+b'\n'+r).replace(b'{now}',b'2026-10-24 12:00:00')).hexdigest())"
```

```bash
# 2) 资料 contentHash（应输出 b0be0d63…）
python3 -c "
import hashlib
t=open('tj-aigc/src/main/resources/knowledge/tianji-points.md',encoding='utf-8').read().replace('\r\n','\n')
print(hashlib.sha256(t.encode()).hexdigest())"
```

3) `Prompt.augmentUserMessage` 语义：`spring-ai-model-1.1.2-sources.jar` → `org/springframework/ai/chat/prompt/Prompt.java`，方法体为 `userMessage -> userMessage.mutate().text(newUserText).build()`（替换最后一条 user 消息）。

4) 生成参数与结构：`scripts/rag-evaluation/RagEvaluation.java:92-95`（options）、`:144-153`（messages 与 Context）。

## 附录 B：7 片段映射（`chunkIndex` → 章节，用于核对"章节 2 在场/缺席"）

| chunkIndex | 章节 | 字符数（含标题前缀） | 关键内容 |
|---|---|---|---|
| 1 | 文档说明 | 264 | 规则核对说明 |
| 2 | 课程学习积分与每日上限 | 299 | 单次 10 分 / 上限 50 / 只补足到上限 / **按类型分别计额 + 学习满 50 后仍可获得签到、问答等其他类型** |
| 3 | 问答、笔记和评价积分 | 255 | 问答 5/20、笔记 3/20、**评价 10 分上限 0** |
| 4 | 每日签到与连续签到奖励 | 363 | 1 分基础 + 7/14/28 天额外奖励 |
| 5 | 签到状态为什么使用 Bitmap | 331 | 位图与消息记录 |
| 6 | 完成学习后为什么积分可能稍后到账 | 312 | 消息链路/到时延后 |
| 7 | 消息失败重试与重复加分保护 | 296 | 去重与重试 |

各组实际选入：`boundary-control` p03 `[3,2,4]` / p04 `[3,6,4]`；`boundary` 同；`boundary-context4` p03 `[3,2,4,6]` / p04 `[3,6,4,2]`。
⇒ points-04 的"无章节 2"组并非检索失败，而是 `contextTopK=3` 下融合顺序把 chunk 2 排到第 4 位（`fusionScore` 0.0164 vs 0.0310+）；`rerankStatus` 全为 `not_executed`。

## 附录 C：本轮做了什么、没做什么

- 做了：读 `results/evidence-v3-2026-10-25/` 三份结果与 `summary.json`/`runtime-manifest.json`；读三版模板、知识原文、`KnowledgeRetrievalAdvisor`、`KnowledgeAnswerPrompt`、`KnowledgeStoreConfig`、`KnowledgeDocumentService`、`RagEvaluation`、`run.ps1`、`AgentContext/AbstractAgent/KnowledgeAgent/SpringAIConfig`；复算系统消息 hash、资料 hash、片段映射、token 量级；读 `spring-ai-model-1.1.2-sources.jar` 核对 `augmentUserMessage`。
- 没做：未调用任何模型 API（含 embedding/rerank）；未访问 Nacos/ES/凭据；未改代码、提示词、案例、快照；未运行 `run.ps1`；未写索引；未提交。
- 待 Codex 决定：§4.1 是否照改（涉及策略层提示词，需 Fate 点头）；§4.2 三个臂需要新的外部调用授权。
