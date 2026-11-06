# 接手说明：天机学堂 AI 客服 · 课程对话修复（第二轮）

写给 Codex。**如果你是新线程，这份文件就是全部背景**，不要重新调研，不要回顾旧会话。
本文由 Hermes 独立复核产出（只读核对，未改代码、未调模型、未重跑回放）。所有数字都可用下列文件复验。

---

## 0. 一句话现状

上一轮修复**三件事修好了**（路由澄清、重复二选一、热度复述），**一件事没修好**（回答里塞未问到的"资料边界"），**一个环境问题没处理**（日常 8096 不含最后两次模板改动）。

---

## 1. 你的上一轮做了什么（已核对，数字属实）

| 声明 | 复验结果 |
|---|---|
| 92 项本地回归通过 | ✔ `reference-regression.log` 末尾 `Tests run: 92, Failures: 0, Errors: 0, Skipped: 0` |
| 仅改最终模板后 18 项再次通过（属于前述集合） | ✔ `final-answer-regression.log` `Tests run: 18 ... BUILD SUCCESS`，且报告未累加成 110 |
| 第二次完整七轮回放：68 项检查、67 通过 1 失败 | ✔ `replay-complete-2.json`：`checks=68`、失败项 `{"name":"answer does not introduce unasked framework catalogue gaps","passed":false}` |
| `automatic_checks_passed=false`、`acceptance_passed=false` | ✔ 字段确认为 false，未粉饰 |
| 第一次回放 59 项全过但未认定整体通过 | ✔ `replay-complete-1.json`：`checks=59`、0 失败、`automatic_checks_passed=true`、`acceptance_passed=false` |
| 合成会话清理 1 个、剩余 0 | ✔ `cleaned=["97ac27e4824844e7b763e3f71317afe3"]`、`remaining_synthetic_sessions=0` |
| 原学员会话只读、未被修改 | ✔ `conversation-before.json`：session `96d9fa39...`，19:52:33 建 / 19:56:55 最后更新，12 条消息 = 6 轮 |

**评价**：这轮判断比上一轮务实（先看真实体验、承认"链路通过≠内容通过"、没拿第一次 `passed=true` 掩盖第二次失败）。继续保持这个口径。

---

## 2. 已修好、**不要回退**的三件事（证据在 replay-2 的逐轮回答）

| 行为 | 修复前 | 现在（replay-2 实测） |
|---|---|---|
| 用户说"我也不知道" | 又发两张卡片 + 再问"前端还是微服务" | 问"你目前学过哪些编程知识，能独立做点什么？"，**无卡片** |
| 普通推荐 / 明确不要热门 | 每轮复述"暂缺可靠热度依据" | 第 5、6 轮**完全没有热度话术** |
| "第二个是啥"课程指代 | 早期三次回放都在第四轮失败 | 正确解释第二门课的用途与前置基础 |

这三条属于**正向重写**（给出可执行的替代行为 + 具体示例），所以生效率 3/3。别动它们。

---

## 3. 没修好的那一条：**否定式禁令对这个模型无效**

### 3.1 硬证据（时间戳来自你自己的文件）

```
tj-aigc/src/main/resources/prompts/course-final-answer.txt   mtime = 2026-10-25 21:18
target/course-user-review-20261025/replay-complete-2.json    完成 = 2026-10-25 21:25
```

21:18 写进模板的规则原文：

> 用户只问HTML/CSS/JavaScript时，不主动增加Vue/React目录缺口；只问方向或课程是什么时，不追加未问的深度、实战项目或完整覆盖说明。

21:25 的回放里，第 7 轮仍然输出：

> 另外提醒一下：介绍里提到 Vue、React、jQuery，但当前正式目录没有列出对应章节，是否有这部分内容需要进详情页核对。

第 4 轮同类（报告自己也记为"内容待改进项"）：

> 现有资料没说明后续章节和实战深度，名称里的"最全"也不能当作完整覆盖的依据。

### 3.2 结论

**规则在场、7 分钟前刚写、同一次回放就被违反。** 而同一批改动里的正向规则 3/3 生效。
⇒ 在 deepseek-flash（thinking disabled）上，`不要 X` / `不主动 Y` / `无需 Z` 的服从率接近 0。
**再补一条"不主动增加…"是无效动作，不要再试。**

这与上一轮 RAG v3 的失败同源：`knowledge-retrieval-v3-evidence.txt` 里的绝对禁令导致模型用约束推翻已提供的资料。同一个模型、同一类失败。

---

## 4. 要求你做的（三选一优先，不要全做）

### 方案 A（首选，最可靠）：在工具层裁字段，而不是提示词层面禁止

准备阶段返回给模型的课程详情，只保留"本轮问到的维度"：

- 用户只问 HTML/CSS/JavaScript ⇒ 详情里**不出现** Vue/React/jQuery 相关目录项与介绍段；
- 用户只问"这门是什么/方向" ⇒ 不返回章节明细，只返回 `usePeople` + 简介；
- 用户明确问"是否教 X / 讲多深 / 是否完整" ⇒ 才返回介绍声明与完整目录。

这样模型**没有可说的材料**，也就不会多嘴。改动集中在 `CourseTools`（`/mnt/d/Java/tianji/tj-aigc/src/main/java/com/tianji/aigc/tools/CourseTools.java`）与 `CourseToolPreparationAdvisor`，不动提示词。
注意：卡片展示需要完整事实，**卡片的取值路径不能跟着被裁**，只裁喂给模型的上下文。

### 方案 B（次选，改动最小）：删掉整段边界声明，只留"不编造"

在 `course-final-answer.txt` 中：

- **删除**"事实边界始终保留"整段里的解释性内容，只保留"不编造课程、前置要求、价格、服务、链接、购买操作"这一句；
- 把边界说明改成**正向条件**：
  > 只有用户问到"是否教某个主题、讲多深、是否完整覆盖"时，才用一句话说明资料能确认到哪一层。
- 同时检查 `consult-system-message.txt` 与 `recommend-system-message.txt` 里同类的无条件句（"资料未提到的内容只说明未提供""缺少主题对应目录时不能声称已经核实"等），一并改成"仅在用户问到时"。

### 方案 C（不推荐但要先排除）：模型能力

只有在 A、B 都做过、并且用同一份请求换模型对照过之后，才允许提"换模型"。
**不要把它当第一解释**——同一模型在正向规则上 3/3 生效。

---

## 5. 必须处理的环境问题：日常 8096 不含最后两次改动

```
runtime-final.json:  dailyPid=7612, dailyReplaced=false, dailyHealth=UP
你报告里声明：       日常 8096 是 21:02 启动的进程，早于最后修复
模板 mtime：         route/consult 21:08、course-final-answer 21:18
```

⇒ 用户现在在 8096 上体验到的，**只包含 `recommend`(20:06) 和 `course-tool-preparation`(20:25) 的改动**；路由与最终模板仍是旧的。
所以他如果觉得"还是那样"，不代表修复无效。

**要求**：在验收通过后，把日常 8096 重启到最新构建，然后**用一句真实问话复验**（例如"我也不知道"），确认路由修复在 8096 上也生效。
重启前先记录当前构建版本与启动时间，重启后逐一核对，不要用"临时预览服务通过"代替日常服务验证。

---

## 6. 明确不在本轮范围（不要动）

1. **数据面**：`tj_course` 里只有 2 门有真实正文的课（`replay-complete-2.json` 的 `course_facts` 只有 2 个 ID：`1589888774267072513` Java程序员的前端课程、`1589905661084430337` 可能是史上最全的微服务技术栈课程），报名量均为零。热门榜与个性化推荐在数据上无解。**这是内容运营问题，不要用提示词掩盖，也不要改课程数据。**
2. 召回/topK/相似度阈值、ES 索引、Nacos 配置。
3. `KnowledgeRetrievalAdvisor` / RAG 那条线（另有单独评审：`docs/rag-evaluation/hermes-review-2026-10-25.md`）。

---

## 7. 验收口径（沿用上一轮，不放宽）

七个固定问题：热门课程 → 两者区别 → 我也不知道 → 第二个是什么 → 普通推荐 → 明确不要热门而要量身定制 → 补 Java/Spring Boot/MyBatis 基础 + HTML/CSS/JavaScript 目标。

必须同时满足：

| 格 | 通过条件 |
|---|---|
| 已知条件保留 | 第 3、6 轮不重复问同一句；第 7 轮给出推荐结论 |
| 缺依据不肯定 | 资料确实没有的维度不编造；只在被问到时说明 |
| 有依据能正常回答 | 第 7 轮引用真实目录作答，不因保守而拒答 |
| **不夹带（本轮重点）** | **第 4、7 轮不出现任何用户未问到的框架/深度/覆盖说明** |

自动检查之外必须保留人工内容审查；`acceptance_passed=false` 时**不得**用"自动检查通过"对外表述为已解决。

---

## 8. 工作约束（按这个做，别再越界）

- 只改：`course-final-answer.txt`、`consult-system-message.txt`、`recommend-system-message.txt`、`CourseTools`、`CourseToolPreparationAdvisor` 及其测试。
- 不改：模型、召回参数、topK、索引、课程数据、Nacos、`application.yml`。
- 交付：给 `git diff`，逐文件说明"改的是哪一句、为什么"，**不要只给结论段落**。
- 不要重跑完整七轮回放，除非用户明确授权新的模型调用额度；本地回归优先。
- **不要凭记忆复述上一线程的结论**——上一线程上下文已经压缩过两次（19:15 最近一次，压缩时单次往返 25 万 token），记忆不可靠，一律以仓库文件和 `target/course-user-review-20261025/` 的证据为准。
- 不得以"增加提示词禁令"或"单次好结果"宣称问题解决。

---

## 9. 证据文件清单（都在 `/mnt/d/Java/tianji/target/course-user-review-20261025/`，Windows 侧 `D:\Java\tianji\target\course-user-review-20261025\`）

| 文件 | 用途 |
|---|---|
| `conversation-before.json` | 用户真实 6 轮原会话（19:52–19:56），初始证据 |
| `replay-complete-2.json` | **最新一次完整七轮**，68 检查 67 过 1 败，含逐轮回答与 `course_facts` |
| `replay-complete-1.json` | 第一次完整七轮，59 检查全过但内容审查未通过 |
| `replay-complete-2.log` | 回放过程日志 |
| `reference-regression.log` | 92 项回归 |
| `final-answer-regression.log` | 18 项（含于 92） |
| `route-diagnostic.json` | 单次路由诊断（`{"agent":"CONSULT","clarification":""}`，1214 prompt tokens） |
| `runtime-final.json` | 服务状态收尾（`dailyReplaced=false`、`previewStopped=22420`、`dailyPid=7612`） |

---

## 10. 复验方法（你可以自己重跑，只读）

```bash
# 确认最后两次模板改动在回放之前
ls -la --time-style=+%H:%M \
  /mnt/d/Java/tianji/tj-aigc/src/main/resources/prompts/course-final-answer.txt \
  /mnt/d/Java/tianji/target/course-user-review-20261025/replay-complete-2.json

# 复核 68 项检查的失败项
python3 -c "
import json;d=json.load(open('/mnt/d/Java/tianji/target/course-user-review-20261025/replay-complete-2.json',encoding='utf-8'))
print(len(d['checks']), [c for c in d['checks'] if not c['passed']])"
```

（Windows 侧把 `/mnt/d/` 换成 `D:\`。）
