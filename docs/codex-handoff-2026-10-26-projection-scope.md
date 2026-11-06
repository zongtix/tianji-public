# 接手说明：课程资料投影（scope）语义修正

写给 Codex。**如果你是新线程，这份文件就是全部背景**，不要重新调研，不要回顾旧会话。
本文由 Hermes 独立复核产出（只读核对，未改代码、未改提示词、未调模型、未跑测试）。所有结论都能用第 8 节的命令复验。

---

## 0. 一句话现状

工程闭环（连接复用/预算/停止竞态/Redis 协调/Langfuse）**已通过，不要回退**；内容验收未通过。
三个内容缺陷**全部定位在本地投影层 `CourseTools`**，不在 MCP 传输、不是模型幻觉。
本轮唯一目标：**让"本轮没展开"和"课程没有"在模型上下文里可区分**，顺带修一个误触发的正则和一句被压缩掉的前置要求。

---

## 1. 已确认的结论（只写结论，不写推导）

| # | 结论 |
|---|---|
| 1 | `catalogue` 被裁成空数组 `[]` 下发，`detail` 字段整个消失 —— 模型因此说"目录未提供/没有提供目录内容"，而真实课程有 **11 条**目录 |
| 2 | `FULL_CONTENT` 里的裸词 `完整` 命中了评测问题中的"完整课程名"，导致 `fullContent=true`，**整份 detail（含 `<h3>原介绍声明的其他内容</h3>` 的 Vue2/Vue3/React/jQuery 段）与 11 条目录原样进入上下文**，模型随即主动展开未问的框架 |
| 3 | 微服务课的前置要求原文含 MyBatis，工具结果也正确带了；模型 bullet 抄对了，**收尾句压缩成"已会 Java 和 SpringBoot"漏了 MyBatis**，并把"熟练使用"降级成"会" |
| 4 | 三条都不是 MCP 造成的：local / MCP 两侧的 tool 结果**逐字节同形**，缺陷在共享的 `CourseTools` 投影 |
| 5 | 三项都属于"内容/投影层"，不能拿来论证 MCP 传输的收益或损害 |

---

## 2. 硬证据：模型当时实际收到的 JSON

来源 `target/mcp-evaluation/live/spans.jsonl` 的 `langfuse.observation.input`（真实请求体，不是摘要）。
**这是本轮最重要的锚点：不要凭报告里的 reviewReason 复述，以这些原文为准。**

### 2.1 详情问题（"…适合什么基础的人？请查一下课程详情。"）

```json
queryCourseById{"id":"1589888774267072513","name":"Java程序员的前端课程",
"price":149.0,"validDuration":9999,
"usePeople":"有一定后端基础，想快速学习前端知识的同学。","catalogue":[]}
```

没有 `detail` 字段。而真实 `detail` 里有两段可用信息，其中一段原文正是：

```html
<h3>前置基础</h3><p>原资料仅说明需有一定后端基础，未细化具体语言、框架或工作年限要求。</p>
```

模型据此回答："课程详情里没有列出具体的前置技术要求，也没有提供目录内容"（MCP）/ "目录也未提供"（local）。

### 2.2 限定推荐问题（含"请用这个完整课程名作为搜索关键词"）

```json
"detail":"<h3>课程定位与适用人群</h3>…<h3>当前正式目录</h3><ul>…11 条…</ul>
<h3>原介绍声明的其他内容</h3><p>原介绍涉及 Vue2、Vue3、React、jQuery，并说明会涉及框架使用、
原理及相关 Web 知识。当前正式目录未列出这些框架的对应章节，具体章节安排仍待核对。</p>…",
"catalogue":["Html与CSS","Html与CSS / HTML元素", … 共 11 条 …]
```

模型据此回答："需要提醒的是，原介绍提到会涉及 Vue2、Vue3、React、jQuery…"。
注意：`consult-system-message.txt` 里**已经有**"用户没问具体框架，不主动展开介绍与目录的框架差异"这句 ——
规则在场、素材也在场，规则输给了素材。这与上一轮"否定式禁令 0/1 生效"同源：**这类问题只能工程解**。

### 2.3 比较问题

工具结果里两门课的 `usePeople` 原文都完整（微服务课含 `熟练使用了SpringBoot、MyBatis等基础框架`），
`detail` 也保留了这句 → 第 3 条缺陷是**输出压缩**，不是证据缺失。

---

## 3. 要做的四件事（按序，一件一提交）

### P1（主）投影语义：被裁的字段必须可区分

文件：`tj-aigc/src/main/java/com/tianji/aigc/tools/CourseTools.java`

现状（`:227-230`）：

```java
if (!scope.topics().isEmpty() && info.getCatalogue() != null) {
    selected.setCatalogue(info.getCatalogue().stream().filter(scope::matches)
            .filter(scope::hasOnlyRequestedIdentifiers).toList());   // 空 → []
}
```

现状（`:234-242`）注释宣称"避免与服务未提供资料混淆"，但 `NON_NULL` 对空 list 无效，目标没达成：

```java
/** 被省略的字段不序列化为 null，避免与服务未提供资料混淆。卡片始终从原始业务事实生成。 */
public static class ScopedCourseInfo extends CourseInfo {
    @Override @JsonInclude(JsonInclude.Include.NON_NULL) public List<String> getCatalogue() { ... }
```

**要求**：

1. `ScopedCourseInfo` 增加显式信号字段，建议 `List<String> notExpanded`，语义："本轮按问题范围没有展开的字段名"，
   取值来自 `catalogue` / `detail`。空时用 `@JsonInclude(NON_EMPTY)` 省略，无裁剪的回答不受影响。
   - `catalogue` 过滤后为空 → `notExpanded` 加 `"catalogue"`；
   - `introduction()` 返回 null，或 `detail` 因句级过滤变成空 → 加 `"detail"`。
2. `getCatalogue()` 的 `JsonInclude` 从 `NON_NULL` 改成 `NON_EMPTY`，让 `[]` 不再以"空目录"的形态出现。
   （单靠 NON_EMPTY 仍无法区分"裁剪"与"课程真没目录" —— 所以 `notExpanded` 是唯一可靠信号，两者都要。）
3. `common-system-message.txt:10` 那句是模型说"资料未提供"的直接授权，加一个括注即可，**不要重写整段**：

   ```
   没有匹配、字段未提供和查询失败是不同情况，应分别如实说明
   （工具结果里 notExpanded 列出的字段属于"本轮没有展开"，不是字段未提供）。
   ```
4. `course-final-answer.txt` 加**一条正向规则**（写在第 8 行那段附近），措辞方向：

   > 工具结果里 `notExpanded` 列出的字段代表"本轮没展开、课程里还有"；回答时说明"这次没展开目录，需要的话再查"。

   不要写成"不要声称资料缺失"这类否定句——上一轮已实测该模型对否定式禁令服从率接近 0。

**禁止**：不要新建第二套裁剪层（不要新增 Advisor、不要改 `CourseToolPreparationAdvisor` 的循环），
就在现有 `modelInfo` / `ScopedCourseInfo` 上改。**卡片取值路径（`card()` / `availableCourses()`）一律不动**，
卡片必须继续拿原始 DTO。

### P2 `FULL_CONTENT` 收紧要约：只排除"完整课程名"这类元语言

文件：`CourseTools.java:248-252`，把裸 `完整` 换成带负向前瞻的写法：

```java
"目录|章节|大纲|多深|深度|深入|完整(?!的?(?:课程)?名(?:称)?)|全面|系统性|系统地|覆盖|实战|"
```

已实测（同一份正则口径）：

| 输入 | 改前 | 改后 |
|---|---|---|
| 是否完整覆盖前端？ / 请给完整目录 / 请给我完整的内容介绍 | true | **true（保持）** |
| 我想完整学一遍前端，需要会什么？ | true | **true（保持）** |
| 请用这个完整课程名作为搜索关键词。 | true | **false** |
| 请用完整的课程名称搜索 / 完整的课程名称是什么？ | true | **false** |

`CourseToolsTest:82` 的 7 个用例（`是否教 Vue？`…`React讲不讲`）改后仍全部命中，不需要改测试。

### P3 前置要求不得被压缩（模板一句）

文件：`tj-aigc/src/main/resources/prompts/course-final-answer.txt`，第 12 行那段（示例段）后面加一句：

> 收尾句要把资料列出的前置条件**逐项**写出（例如 SpringBoot、MyBatis 两项都要提到），
> 不缩写成"会 X 就行"，也不把"熟练使用"降成"会"。

### P4 补测：本轮漏过的那个分支

文件：`tj-aigc/src/test/java/com/tianji/aigc/tools/CourseToolsTest.java`

现有用例只覆盖两种结果：`topics` 为空 → `catalogue` 为 null 被省略（`:36`、`:97`）；`topics` 有命中 → 保留非空列表（`:63`、`:113`）。
**没覆盖"`topics` 非空但零命中"** —— 这正是线上出问题的那条路径（用例名 `overviewOmitsCatalogueWithoutPretendingItIsMissing` 意图对，但走的是另一个分支）。

新增用例，用真实口径问题 `"Java程序员的前端课程适合什么基础的人？请查一下课程详情。"`：

- 断言响应里**不出现** `"catalogue":[]`；
- 断言 `notExpanded` 包含 `"catalogue"`，`detail` 被裁时也包含 `"detail"`；
- 断言 `usePeople` 仍原样返回（这是"适用人群"问题唯一靠得住的字段）；
- 顺带一条：`"…请用这个完整课程名作为搜索关键词。"` 走 `fullContent=false` 分支（P2 的回归保护）。

必须保持绿：`CourseToolsTest:36/46/63/82/92/97/101/109`。

---

## 4. 已被证伪的方向（不要重跑）

| 做法 | 现象 | 结论 |
|---|---|---|
| 往 `course-final-answer.txt` 加"不主动展开 Vue/React 目录缺口"这类否定句 | 写进模板 7 分钟后的同一次回放仍违反；本轮再次出现（`consult-system-message.txt` 已有同类句仍失效） | 否定式禁令对该模型无效，**别再试** |
| 把"推荐问题"改成带上"请用这个完整课程名作为搜索关键词"来限定范围 | 该措辞自身触发 `FULL_CONTENT` 的裸词匹配，把整份资料放进来 | 评测 question 里的 meta 指令是污染源，复验必须用用户原话 |
| 扩大 MCP 白名单让"较宽泛推荐"通过 | 选到白名单外课程被 `MCP_TOOL_FORBIDDEN` 拒绝 | **不扩大权限**，该失败样本保留，不计正向通过 |
| 归因到 MCP 传输 | local / MCP 的 tool 结果逐字节同形 | 缺陷在共享投影层，不是协议问题 |
| 用更多测试项数掩盖内容失败 | 198 项回归全绿但内容仍不合格 | 数量不等于覆盖，见 P4 的盲区 |

---

## 5. 边界：只准改这些文件

**允许改（4 个文件 + 1 个测试文件）**

- `tj-aigc/src/main/java/com/tianji/aigc/tools/CourseTools.java`（仅 `modelInfo` / `ScopedCourseInfo` / `FULL_CONTENT`）
- `tj-aigc/src/main/resources/prompts/common-system-message.txt`（仅第 10 行那一句加括注）
- `tj-aigc/src/main/resources/prompts/course-final-answer.txt`（加 P1 的正向规则、P3 的一句）
- `tj-aigc/src/test/java/com/tianji/aigc/tools/CourseToolsTest.java`（新增用例，不改既有断言）

**不准动**

1. 课程数据：`tj_course` 的 11 条目录、`detail` 正文、`usePeople`。真实课程资料不允许为过验收而修改。
2. MCP 侧：`tj-course-mcp/`（含白名单 `allowedCourseIds`、`MAX_BYTES`、并发许可）、`CourseDetailContract`。
3. 连接/预算/停止逻辑：`CourseMcpClient`、`McpCallScope`、`AbstractAgent`、`ChatServiceImpl`、20s 整轮预算、5s 单操作。
4. 召回与索引：topK、相似度阈值、ES 索引、语义检索开关、`application.yml`、Nacos 配置。
5. 模型与温度（生产 `temperature=0.7` 是现状，不是本轮变量）。

---

## 6. 验收口径（不放宽）

**本地（本轮必须给）**

- `CourseToolsTest` 全绿，且新增用例真的走了"零命中"分支（给 `Tests run:` 数字，不要只说通过）。
- 用真实课程快照（11 条目录那门）跑一次投影，**打印 tool 结果 JSON**，证明：
  `catalogue` 不再是 `[]`，`notExpanded` 出现在响应里。
- `git diff` 逐文件说明"改的是哪一句、为什么"。

**真实模型（等用户明确授权，别自己开）**

同资料、**同用户原话（去掉所有 meta 指令）**，三格同时看：

| 格 | 通过条件 |
|---|---|
| 已知条件保留 | 问"适合什么基础"时，`usePeople` 的原文被正确复述，不把没展开当成缺失 |
| 缺依据不肯定 | 资料确实没有的维度（如目录之外的深度）仍说无法确认 |
| 有依据能正常回答 | 目录被问到时能引用真实章节，不因保守而拒答 |
| **不夹带** | 用户只问 HTML/CSS/JavaScript 时，回答里不出现 Vue/React/jQuery |

`acceptance_passed=false` 时**不得**用"自动检查通过"表述为已解决。上一轮这一点做对了，继续保持。

---

## 7. 提交状态：**第一个动作不是改代码，是提交**

```
git status --porcelain | wc -l  →  21
git ls-files tj-course-mcp | wc -l  →  0        ← 整个 MCP 模块从未进版本库
当前分支：feature-aigc   最近提交：6c6bd02 docs(aigc): 归档 RAG 验收结果并明确本轮收口
```

21 项改动 + 整个 `tj-course-mcp/`（0 文件入库）悬在工作区。**先做**：

```bash
cd /mnt/d/Java/tianji           # Windows 侧：D:\Java\tianji
git add -A
git commit -m "chore(aigc): 归档 MCP 真实模型验收与投影层现状"
git tag codex/baseline-20261026-before-projection-fix
```

有了 tag 再动 P1，任何一处出问题都能单点回退。不要在一个无法回退的工作区里叠改动。

---

## 8. 复验命令（只读，可自查）

```bash
cd /mnt/d/Java/tianji

# 8.1 三个缺陷的证据原文（模型当时收到的 tool 结果）
python3 - <<'PY'
import json, re
rows=[json.loads(l) for l in open('target/mcp-evaluation/live/spans.jsonl',encoding='utf-8') if l.strip()]
seen=0
for r in rows:
    a={x['key']:(x['value'].get('string_value') or '') for x in r.get('attributes',[])}
    inp=a.get('langfuse.observation.input','')
    for m in re.finditer(r'"catalogue":\[\]', inp):
        print(inp[max(0,m.start()-300):m.end()]); seen+=1; break
    if seen>=2: break
PY

# 8.2 真实课程快照（11 条目录 + usePeople 原文）
python3 - <<'PY'
import json
d=json.load(open('target/mcp-evaluation/live/core-report.json',encoding='utf-8'))
for cid,c in d['course_snapshot_before'].items():
    print(cid, c['name'], 'catalogue:', len(c.get('catalogue') or []))
    print('  usePeople:', c['usePeople'])
PY

# 8.3 现有测试里那两个盲区用例
sed -n '28,42p;90,98p' tj-aigc/src/test/java/com/tianji/aigc/tools/CourseToolsTest.java
```

（Windows 侧把 `/mnt/d/` 换成 `D:\`。）

---

## 9. 硬约束

- **以仓库文件和证据文件为准，不要凭记忆复述上一线程的结论。** 上一线程上下文已压缩过，记忆不可靠。
- 改完 `prompts/*.txt`（classpath 资源）**必须重启服务才生效**；重启后核对进程启动时间晚于模板 mtime，
  否则"验证通过的修复没上线"。本轮上线部署由用户决定，你不要自行重启日常 8096。
- 不新增提示词禁令；不为了过验收改课程数据；不扩大 MCP 权限；不把"单次好结果"当收敛。
- 一次只落一个变量：P1 先提交，再动 P2/P3。三处一次改完，出问题无法归因。
