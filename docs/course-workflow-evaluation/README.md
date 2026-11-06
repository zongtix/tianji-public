# 课程路由与工具约束评测

首版 24 条合成案例见 [cases.json](cases.json)，分 dev/holdout；不代表真实用户分布，不含硬编码课程价格或有效期。真实课程事实执行时重新核实，fixture 仅供受控故障验证。积分 RAG 案例和历史报告保持原样。

## 已实现的离线入口

`scripts/local/evaluate-course-workflow.py` 不访问任何服务，不调用模型。它读取实际捕获并规范化的观察记录，输出路由准确率、工具约束通过率、逐例失败、分集合指标、覆盖范围和内容复核状态。

工具检查支持先浏览/语义查询取得详情，或先关键词查询再单独读详情等合法路径。非空推荐必须有此前实际返回的候选及对应详情；CONSULT 的禁止工具、指定课程 ID、空推荐提交和顺序约束由案例预先规定。模型提出 tool_calls 不算工具执行，必须有对应的实际返回。

以下情况均阻断：缺样本/重复样本、日志不完整、必要检查未通过、工具标记泄漏、终止事件重复、错误代码与场景不符、指标低于固定门槛，或相对兼容基线下降。缺失工具日志使指标为 null，而不是默认通过。

首版固定小案例集的两个门槛均为 1.0。这是明确场景的回归要求，不是“线上准确率 100%”的承诺。修改门槛会改变数据集哈希，必须先说明理由并建立新基线，不能跑完候选再降低门槛。

`gatePassed=true` 仅表示当前选择案例的程序门禁通过。`coverage.complete=false` 明确表示只跑了子集。未复核、not_applicable、受控模型替身结果都不能自动成为内容验收通过；`acceptancePassed` 另要求完整覆盖、真实观察及独立人工复核完成。

## 运行命令

先离线核对评分器、导入器及 CLI 退出码：

```powershell
python -X utf8 scripts/local/test-course-workflow-evaluation.py
python -X utf8 scripts/local/test-chat-evaluation.py
```

规范化观察文件与兼容基线齐备后，用一条命令比较完整案例集：

```powershell
python -X utf8 scripts/local/evaluate-course-workflow.py --input target/course-workflow/current.json --baseline target/course-workflow/baseline-summary.json --output target/course-workflow/current-summary.json
```

输出必须使用新文件名，脚本拒绝覆盖已有结果。退出码：0 为当前选择案例的程序门禁通过，1 为门禁不通过，2 为输入缺失或不可比较。显式选子集时，baseline 与 current 也必须是相同子集。

## 观察文件契约

根字段：

- `metadata`：datasetVersion、datasetHash（评分器 digest 函数的规范 JSON SHA256）、sourceKind（controlled/recorded-real/live-real）、dataHash、promptHash、model、modelOptions、config、configComplete、codeVersion。
- `turns`：实际观察。每条含 caseId、status=completed、route、toolsComplete、tools、eventTypes、errorCodes、answer、cards、checks、evidence；CLARIFY 还需非空 clarification。
- `evidenceFiles`：从观察文件所在目录解析的相对路径到文件 SHA256 的映射。每条 evidence 引用必须存在且匹配哈希。
- `independentHumanReview`：默认 pending；不能由程序检查自动改为 completed。

`route` 是实际路由结果，CLARIFY 与 KNOWLEDGE 分开。`tools` 按实际执行顺序记录 `{name, arguments, result}`，异常另含 error。工具错误也保留执行记录；没有执行时记录空数组，同时必须有完整捕获证据，不能因未收集日志就写 toolsComplete=true。

`checks` 包含案例要求的授权范围、历史一致、卡片事实等严格布尔结果，必须来自执行器对相应原始证据的核对；不能用模型自评替代。eventTypes、answer 和 errorCodes 还会被评分器独立检查。内容 reviews 沿用 fact_correctness/evidence_support/request_fulfilled，每项需 value、reviewer、comment。

比较会固定数据集、资料、实际系统提示词、模型及其选项；codeVersion 可以不同。config 必须完整捕获；只允许通过 `--allow-config-change 配置键` 预先声明待比较的配置变量。缺少有效配置的历史观察只可单独计分，拒绝当作应用改动前后的完整基线。

## 复算已有真实记录

`import-course-workflow-recording.py` 支持已有两门课程的三种事实场景，通过真实模型请求/返回、工具结果回灌、SSE/历史、原审计记录及捕获 span 重建观察，不联网、不修改原证据：

```powershell
python -X utf8 scripts/local/import-course-workflow-recording.py --recording target/mcp-evaluation/mcp-fact-live-20261026 --source mcp --output target/course-workflow/recorded-mcp.json
python -X utf8 scripts/local/evaluate-course-workflow.py --input target/course-workflow/recorded-mcp.json --case-id consult-audience --case-id consult-compare --case-id consult-depth --output target/course-workflow/recorded-mcp-summary.json
```

导入器只支持该类固定事实验收格式，未知批次格式需单独适配，不能猜字段。最后一次工具调用若因提前结束而没有后续请求携带其返回，工具日志标记不完整；不以模型请求、可见卡片或答案反推执行成功。

本次复算 MCP 为路由 3/3、工具 3/3；local 为路由 2/3，工具有 1 条不可计算。两侧均只覆盖 3/24；这不是新模型调用、不是 local/MCP 因果对照，也不是完整客服验收。

## Langfuse

评分发布器新增可选 `--workflow-summary` 参数，沿用原有 trace 身份核对、稳定 Score ID 和回读流程，支持 `tj_route_correct_v1`、`tj_tool_contract_v1` 两个 BOOLEAN。只有此次实际有可判分数的配置才会创建；不可计算的分数不发布。受控观察与不匹配的评分来源被拒绝，内容分不自动补齐。

导入的 traceVerified 默认为 false。先用既有 `enrich-langfuse-report.py` 对后端的 trace/session 完成核对，才可发布，随后必须 `--verify-scores` 回读。已有六条记录的 span 当时只被隔离接收器捕获；本次只读查询后端为 0/6，故没有伪造 traceVerified、没有创建新配置或发布新分数。

详细交付、异常策略取舍及待完成的真实验收见 [执行记录](../aigc-optimization-execution-2026-10-27.md)。

后续用户批准的 [七轮真实批次](../aigc-seven-turn-acceptance-2026-10-27.md)已完成：独立工具span补齐末轮返回，七条新trace和63条Score实际落库。内容仍有两项失败，门禁退出1；原六条历史记录的0/6结论保留，不以新批次替换历史。该七轮集合不等于全部24条案例，完整有效配置比较仍未完成。
