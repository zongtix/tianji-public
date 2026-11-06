# 积分与优惠券 RAG 同批对照（2026-10-30）

本轮完成两份资料、44 题、A/A′/B/C 各一次的真实调用，共 176 个案例执行。相比原向量方案，混合检索补回了遗漏的标注证据；扩大向量召回也达到全标注覆盖，但平均资料 Token 明显增加。重排改善了关键证据的排序，本批最终覆盖率与 B 相同，并出现一条超出资料依据的回答。因此不依据这一批小样本宣布 C 最优，也不据此切换日常配置。

## 数据来源与运行条件

- 积分资料来自原已核实文档，7 个片段；优惠券资料经源码核对和 DeepSeek 独立 AI 审阅，9 个片段，共 16 个片段。
- 原积分 32 题作为回归集，新增优惠券 12 题来自 AI 根据仓库规则设计。共 38 题有参考片段、5 题资料不足、1 题澄清；不来自真实用户日志，不是独立人工盲测集。
- 生产 `KnowledgeDocumentService.readMarkdown` 生成片段和稳定身份。优惠券通过原文锚点映射参考片段，未猜测片段序号。无答案题不进入证据召回分母。
- 每题均明确授权积分和优惠券两个文档，因此其他主题片段会成为实际检索干扰；没有加入订单、退款或用户私有资料。
- 独立索引 `tj_aigc_eval_points_coupons_20261030`，批次 `points-coupons-v1-20261030-r1`。正式积分索引和聊天发布白名单未修改。
- 四组使用相同数据集字节哈希、索引内容快照、源码版本、系统与检索提示词哈希、模型及生成参数。严格配对检查通过，各组结束时索引快照均未变化。
- 模型配置为 `deepseek-flash`，embedding 为 `text-embedding-v4`、1024 维，重排使用既有 `qwen3-rerank` 配置。模型名称是供应商别名，不代表不可变的模型权重版本。
- 新资料首次导入后，账号没有手动 refresh 权限；改为等待 ES 自动刷新，未扩大权限。首次初始化尝试未执行评测，随后同一隔离索引上的正式四组全部完成。

## 检索结果

候选证据召回：对每题，实际向量/BM25 返回的候选并集中命中的标注片段数，除以本题标注片段数，再对 38 题求平均。各模式候选深度不同，此指标不是固定统一 K 的 Recall@K。

最终证据覆盖：对每题，选入模型上下文的标注片段数除以本题标注片段数，再求平均。它不等于回答准确率。MRR 使用文档 ID、内容哈希和片段序号完整匹配，避免不同文档相同片段号误命中。

| 模式 | 实际方案 | 候选证据召回 | 最终证据覆盖 | 最终上下文 MRR | 平均资料 Token 估算 |
| --- | --- | ---: | ---: | ---: | ---: |
| A | 向量 topK=3，阈值 0.6 | 87.28% | 87.28% | 0.838 | 772 |
| A′ | 向量 topK=6，阈值 0.45 | 100.00% | 100.00% | 0.899 | 1887 |
| B | A 的向量参数 + BM25/RRF，最终最多 3 片段 | 100.00% | 99.12% | 0.917 | 972 |
| C | B + qwen3-rerank，最终最多 3 片段 | 100.00% | 99.12% | 0.987 | 929 |

相对 A，B/C 的候选证据召回增加 12.72 个百分点，最终证据覆盖增加 11.84 个百分点。A′ 说明简单调整候选量与阈值也有效，但不能忽略其资料量开销。A′ 同时改变 topK 和阈值，本批不能再拆解这两个参数各自的贡献。

按资料拆分：积分 29 个有标注案例的最终覆盖为 A 83.33%、A′ 100%、B/C 98.85%；优惠券 9 个有标注案例四组均为 100%。优惠券题尚未暴露召回差异，不能将其满分解释为复杂优惠券问题已被充分验证。

B/C 唯一未全部覆盖的是沿用原标签的 points-04。该题标注不一定是最小充分证据集合，保留原标签以避免看到结果后修改评分；需后续独立标注核对。完整逐题变化见原始报告与 `summary.json`。

## 耗时与回答审阅

| 模式 | 首个文本中位数 | Advisor 总耗时中位数 | 总耗时 p95 | AI 来源审阅 supported / issue |
| --- | ---: | ---: | ---: | ---: |
| A | 980 ms | 1929.5 ms | 2847 ms | 40 / 4 |
| A′ | 989.5 ms | 2085 ms | 2774 ms | 44 / 0 |
| B | 1048 ms | 1920.5 ms | 2732 ms | 44 / 0 |
| C | 1165 ms | 2043.5 ms | 2866 ms | 43 / 1 |

四组均无调用错误或检索降级。C 的 43 次需要检索的请求均实际重排成功，另 1 题直接澄清。耗时仅覆盖评测 Advisor 范围，不含完整 HTTP 鉴权、Agent 路由、标题、聊天存储或浏览器网络；固定顺序 A/A′/B/C 各运行一次，存在时间顺序和外部模型波动，不能将总延迟差直接归因于某模块。资料 Token 为估算，实际可获得的 chat/embedding 用量在逐题报告中；重排费用及导入费用未纳入完整成本核算。

DeepSeek 对 176 条归档回答做语义来源审阅，父代理核对核心缺口。该审阅是 AI 初审，生成与审阅模型来自同一供应商，独立人工复核仍待完成；supported 计数不作为人工准确率。

重要缺口：

- A / points-07、24、25：未检索到资料而拒答；完整语料中分别存在积分补齐、两张表区别、确认超时/NACK 的依据。拒答符合当前无资料保护，但整体任务未答成，缺口在检索覆盖。
- A / points-09：未选入明确说明不能重复评价刷分的章节，回答无法确认。仍是检索证据缺失造成的任务缺口。
- C / coupon-12：断言“签到积分达到每日上限，不会影响你领取优惠券的资格”，随后承认是自行推导。两份授权资料均没有说明这种联动，优惠券文档还明确标注这一范围未覆盖；该断言缺少支持。此例说明提高召回或排序不能保证回答有依据。

未围绕这些题继续改提示词或重跑至满分；原始结果完整保留。后续优先增加独立提问、资料边界和跨文档条件问题，并人工复核该类推断，之后才考虑回答约束调整。

## 复现与验收

从仓库根目录执行：

```powershell
./scripts/rag-evaluation/prepare.ps1 -Output target/rag-optimization/<new-dataset> -Index tj_aigc_eval_<new-batch>
./scripts/rag-evaluation/run.ps1 -Mode A -Answers -Dataset target/rag-optimization/<new-dataset>/dataset.json -RunGroup <new-batch> -InitializeCorpus
./scripts/rag-evaluation/run.ps1 -Mode APRIME -Answers -Dataset target/rag-optimization/<new-dataset>/dataset.json -RunGroup <new-batch>
./scripts/rag-evaluation/run.ps1 -Mode B -Answers -Dataset target/rag-optimization/<new-dataset>/dataset.json -RunGroup <new-batch>
./scripts/rag-evaluation/run.ps1 -Mode C -Answers -Dataset target/rag-optimization/<new-dataset>/dataset.json -RunGroup <new-batch>
```

新运行调用既有外部服务并产生费用，必须使用不同批次和新索引以保留原始证据。离线重建本批统计不调用服务：

```powershell
python -X utf8 scripts/rag-evaluation/report.py docs/rag-evaluation/results/points-coupons-2026-10-30/dataset.json docs/rag-evaluation/results/points-coupons-2026-10-30 --reviews docs/rag-evaluation/results/points-coupons-2026-10-30/reviews.json --output target/rag-optimization/recomputed-summary.json
python -X utf8 -m unittest discover -s scripts/rag-evaluation -p 'test_*.py' -v
```

本轮 Java 文档切分相关 5 项测试、Python 对比及汇总相关 8 项测试通过；严格配对和从实际片段身份重算指标通过。没有提交或推送代码。

归档目录：`docs/rag-evaluation/results/points-coupons-2026-10-30/`，包括冻结的 dataset/corpus、四组逐题报告、AI 审阅、汇总和执行时源码哈希清单。该结果补齐本项目的同批对照证据；两份资料仍是小规模业务切片，不足以声称覆盖全平台、生产稳定性或泛化效果。
