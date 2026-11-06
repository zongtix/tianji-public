# Hermes 独立复核任务

请独立审查天机项目的两例 RAG 回答失败，帮助定位原因并提出最小修正方案。先依据原始结果和源码判断，不预设 Codex 的归因正确。本次只读分析，不修改项目，不运行真实模型评测、不访问凭据或配置中心，不写索引、不提交、不推送、不部署。你在本次对话中的分析与额外调用项目模型 API 是两件事，本任务不授权后者。

## 先读这些文件

- 失败报告：`D:\Java\tianji\docs\rag-evaluation\evidence-v3-live-2026-10-25.md`
- 六例真实输出及运行清单：`D:\Java\tianji\docs\rag-evaluation\results\evidence-v3-2026-10-25\`，其中 `boundary-control.json`、`boundary.json`、`boundary-context4.json` 是三组结果。
- 固定问题及验收标注：`D:\Java\tianji\docs\rag-evaluation\cases.json`，重点 points-03、points-04。
- 实验参数：`D:\Java\tianji\docs\rag-evaluation\evidence-boundary-experiments.json`。注意该文件顶层 status 仍写 prepared，属于运行前快照；实验已经运行，请以真实结果和运行清单为准。
- 知识原文：`D:\Java\tianji\tj-aigc\src\main\resources\knowledge\tianji-points.md`。
- 三版回答模板：`D:\Java\tianji\tj-aigc\src\main\resources\prompts\knowledge-retrieval-v1.txt`、`D:\Java\tianji\tj-aigc\src\main\resources\prompts\knowledge-retrieval-v2-conditions.txt`、`D:\Java\tianji\tj-aigc\src\main\resources\prompts\knowledge-retrieval-v3-evidence.txt`。

随后按需追踪：

- 最终请求组装：`D:\Java\tianji\tj-aigc\src\main\java\com\tianji\aigc\knowledge\KnowledgeRetrievalAdvisor.java`。
- 版本到模板映射：`D:\Java\tianji\tj-aigc\src\main\java\com\tianji\aigc\knowledge\KnowledgeAnswerPrompt.java`。
- Advisor 配置：`D:\Java\tianji\tj-aigc\src\main\java\com\tianji\aigc\config\KnowledgeStoreConfig.java`。
- 真实评测入口：`D:\Java\tianji\scripts\rag-evaluation\RagEvaluation.java`、`D:\Java\tianji\scripts\rag-evaluation\run.ps1`。
- 本地准备阶段测试：`D:\Java\tianji\tj-aigc\src\test\java\com\tianji\aigc\knowledge\KnowledgeAnswerPromptTest.java`、`D:\Java\tianji\tj-aigc\src\test\java\com\tianji\aigc\KnowledgeChatClientTest.java`。

如果你运行在 WSL，请先核实 Windows D 盘是否映射为 `/mnt/d`。目录无法访问时，明确列出缺少的文件，不根据文件名猜内容。

## 需要解释的失败

1. points-03：“学习积分今天已有45分，再来一次10分事件会入账多少？”用户已经明确学习类型；按提供的学习日上限50分规则，符合有效事件等条件时本次补5分。模型仍反问积分类型、引入其他类型假设。不能因为回答出现“5分”就判定通过。
2. points-04：“学习已满50分，还能按规则获得签到和问答积分吗？”
   - 未选入章节2时，资料缺少跨类型关系，不能从没有禁止推导允许。
   - 选入章节2时，已有按类型分别计额及学习满50后仍能获得其他类型积分的明确依据。v3＋4条模型引用这段后仍拒答，并把模板约束称作资料要求。不能因为拒答更保守就判定通过。

两例没有必要历史。当前默认仍是 A 检索、v1 模板；实验失败没有证明默认配置的质量问题已经解决。

## 请独立回答

1. 逐例引用实际回答与对应资料，指出具体哪项条件、依据或归因出了错；如果报告的判定有误，也请直接指出。
2. 检查原问题、检索结果、模板、系统指令及生成参数如何进入最终请求，区分生产路径与评测路径。核对是否存在已知条件未传入、模板冲突、过度约束、角色边界或评测方法问题。
3. Codex 当前观察到：问题、资料、回答约束拼在同一条用户消息里。请审查这个假设，主动寻找反证。源码结构及一次错误输出不能证明这是根因；如果缺少当时的完整系统消息或实际 HTTP 请求，就明确标注缺口，不能把根据源码重建的请求说成已捕获的线上请求。
4. 给出你优先推荐的一个最小修正及理由，避免同时改变模型、召回、片段数、措辞和消息结构。解释它能解决什么、不能保证什么。
5. 提出能区分竞争假设的最小对照实验，说明固定项、唯一变量、预期结果和推翻假设的条件。必须同时检验：已知条件保留、缺依据不肯定、有明确依据正常回答。只提出实验，不执行额外 API 调用。

## 输出格式

- 结论先行：最值得修正或验证的点是什么。
- 已证实问题：附文件行号或结果中的案例和字段。
- 待验证假设：附支持证据、反证和缺失信息。
- 一个最小修正建议与最小验证方案。

不要只泛泛建议“加强提示词、加 rerank、换模型”。不把相关性分数当作证据充分性，不用测试通过替代回答质量验收。若不能确定根因，明确说不能确定，但给出下一步能够缩小范围的具体检查。
