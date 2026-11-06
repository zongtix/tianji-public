# Langfuse 计时与评分基线（2026-10-26）

使用两个固定合成场景验证观测与评分：网页开发课程推荐、学习积分上限与签到。问题及评审标准见 [chat-baseline-cases.json](chat-baseline-cases.json)。只使用专用测试身份，创建的会话均在调用后清理；课程、索引和真实用户聊天不修改。自动模型裁判未启用。

## 观测范围

通过 Micrometer `SpanExportingPredicate` 排除 `/actuator/health` 及子路径的 trace 导出，保留健康接口和 HTTP 观测/指标。聊天继续全量采样，历史健康记录不删除。

`chat.request` 增加四个毫秒属性：

| 属性 | 含义 |
| --- | --- |
| `chat.first_token.ms` | 从聊天 Flux 订阅到第一个非空文本事件 |
| `chat.last_token.ms` | 到最后一个非空文本事件 |
| `chat.answer_complete.ms` | 回答管线正常完成，发生在标题生成之前 |
| `chat.total.ms` | 整轮完成或终止，包含标题、占位释放等 |

服务端属性使用单调时钟，不包括订阅前的鉴权/会话检查和客户端网络；评测器另记录端到端首字、末字及 SSE 结束时间。取消或错误时没有实际发生的首字、回答完成值不补零、不伪造。

内容捕获增加 `ToolResponseMessage` 中实际工具返回，仍由 `log-prompt` 控制，每项上限8000字符。旧 trace 缺失的工具正文无法追溯补齐。该改动不改变发往模型的消息。

## 原生 Scores

| Score Config | 类型 | 数据来源 |
| --- | --- | --- |
| `tj_flow_valid_v1` | BOOLEAN | 程序：SSE、单次终止、错误事件、工具标记、历史及卡片事实 |
| `tj_fact_correctness_v1` | CATEGORICAL | 对照业务来源复核关键事实 |
| `tj_evidence_support_v1` | CATEGORICAL | 结论的资料支持情况 |
| `tj_request_fulfilled_v1` | CATEGORICAL | 当前问题及已知条件是否得到满足 |
| `tj_first_token_ms_v1` | NUMERIC | 服务端首字耗时 |
| `tj_answer_complete_ms_v1` | NUMERIC | 服务端回答完成耗时 |
| `tj_total_ms_v1` | NUMERIC | 服务端整轮耗时 |

分类为 `pass`、`fail`、`not_applicable`；未复核不写内容分。`not_applicable` 不等于通过，不能把分类内部数值求均值当作质量分。本轮内容评分来源为 `codex_source_review`，通过 API 写入，`independentHumanReview=pending`；不声称独立人工验收或校准后的模型裁判。程序检查通过不等于回答正确。

Langfuse 的 trace Scores 可查看分数和证据说明，后续人工可用同一配置标注，再比较人工与自动评价的一致性。自动模型裁判应先校准、控制采样与额外用量，再离线或异步执行。

## 重复使用

```powershell
# 默认执行两题，调用现有模型；运行前须确认本次调用与数据发送授权。
python scripts/local/verify-chat-performance.py --real-model --label baseline
python scripts/local/enrich-langfuse-report.py target/langfuse-evaluation/baseline.json
# 在报告 turns[].reviews 中填写 value、reviewer、comment；不填则只发布程序检查与耗时。
python scripts/local/publish-langfuse-scores.py target/langfuse-evaluation/baseline.json
python scripts/local/enrich-langfuse-report.py target/langfuse-evaluation/baseline.json --verify-scores
```

`--case-id` 限制到一个已有场景；`--resume` 仅复用同版本中已完成、已清理且校验通过的结果，避免重复模型调用。脚本仅访问本机服务，评分发布复用用户目录的 Langfuse 凭据，不写入仓库。原生评分 API 异步接受不等于入库通过，最后一条命令逐项核对实际存储的分值、来源和 trace 身份。

## 首轮对照

| 场景 | 基线首字/整轮（客户端） | 首轮候选版本首字/整轮 | DeepSeek 调用数 | 来源初审 |
| --- | --- | --- | --- | --- |
| 课程推荐 | 11.55s / 14.87s | 13.48s / 16.46s | 6 → 7 | 基线推荐参考课程；候选未给出有效推荐，需求满足为 fail |
| 知识库问答 | 5.68s / 9.19s | 5.74s / 8.84s | 2 → 2 | 两次核心结论均受按积分类型分别计额的规则支持 |

候选提速在 `recommendCourses` 成功提交非空最终卡片后直接进入最终回答，省去再次调用模型确认“准备完成”。详情预览卡片、空提交、失败提交均不触发。测试验证少一轮调用并保留工具响应、最终回答、记忆及取消边界。

首轮候选课程样本调用了 `recommendCourses([])`，未触发提速分支，不能用这次结果宣称优化成功，也不能仅凭该样本归因于提速分支。旧捕获器缺少工具响应，无法确认检索结果中是否包含参考课程；已补充该观测。失败样本保留，小样本不支持整体性能或质量优于基线的结论。

原始记录在 `target/langfuse-evaluation/`：`baseline-instrumented.json`、`optimized.json` 及各自评分回执。健康检查历史保持22条，最新14:26:54，早于新实例14:39启动及后续健康探测。

## 最后一次同题复测与收口

仅追加一次相同课程问题，结果保存在 `optimized-course-repeat.json`：返回了参考课程卡片，实际工具响应包含该课程的适用人群与HTML/CSS/JavaScript目录；另一个候选目录为空、适用人群无有效说明，因此未推荐。该次来源初审通过，独立人工复核仍待完成。

该次 DeepSeek 调用5次（路由1、准备2、最终回答1、标题1），成功提交卡片后不再调用模型确认“准备完成”。基线为6次。不过客户端首字12.06s、整轮15.54s，仍未优于基线11.55s/14.87s；不能将少一次模型调用等同实际已提速。首轮候选中的失败记录和fail评分保留，整体时延与稳定性验收尚未通过，不自动扩跑模型场景。

本轮共执行5次合成对话，均清理测试会话；7个评分配置、35条评分均逐项核对实际落库。相关14个Java测试类的最新结果共160项通过，0失败/错误/跳过；6项评测脚本检查通过。完整验证摘要在 `target/langfuse-evaluation/verification-summary.json`。

日常8096已通过原有入口加载最终代码，PID 37496，健康状态UP；模式C、knowledge-v1、rerank=true保持不变。健康trace过滤、分阶段计时、工具返回捕获及成功卡片后的结束条件均已生效。代码、测试及评测工具按本轮授权分批本地提交，未推送。
