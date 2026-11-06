# 课程讲义 RAG 方案异议记录（Cursor）

用途：记录第三方对方案/实现的异议与是否被采纳，避免口头结论无据。

| 日期 | 提出方 | 异议 | 处理 | 落入文档 |
| --- | --- | --- | --- | --- |
| 2026-10-31 | Cursor | 应先证明工具可达与引用正确，再做 A/A′/B/C | 采纳为闸 A → 闸 B | 方案 §7–§8；实现计划 Task 6–7 |
| 2026-10-31 | Cursor | 路由/快捷回答/`CourseFactAnswer` 需可执行验收，不能只加工具白名单 | 采纳 | 验收清单 A3/A4/A13；实现计划 Task 5 |
| 2026-10-31 | Cursor | 资料作者不宜同时定义问题与评判答案；第三方负责保留题与标签审阅 | 采纳 | 分工表；holdout-cases-draft.json |
| 2026-10-31 | Cursor（闸 B 审阅） | 认同实现者：r1 仅用 `documentId in […]` 过滤时的跨课「泄漏」为假阳性；正式批应对齐生产 `courseId == … && materialId in […]`，并以该批 `forbiddenMaterialLeak=0` 计 B6 | 采纳（记入异议以免与 r1 混读） | `results/materials-20261031/README.md`；验收清单 B6 |
| 2026-10-31 | Claude（闸 B 语义主审） | 判定口径：回答以「《文档标题》—「章节」」引用，未出现 materialId slug；corpus 中标题与 materialId 一一对应，建议视为满足「materialId+章节」 | Claude 暂按满足计 PASS；**Cursor 复核采纳本轮满足**；后续在线若要求 slug 另开项 | `semantic-review-claude.md`；`semantic-review.md`；不改归档 |
| 2026-10-31 | Claude（闸 B 语义主审） | `cm-dev-07` 价格半边离线无业务工具，判 CONDITIONAL 而非 FAIL；价格真实链路以闸 A A4（139 元 + 讲义）为准 | 按约定执行；**Cursor 采纳**；A10/A11 在线复测一次 | `semantic-review.md`；`gate-a-notes.md` A4 |
| 2026-10-31 | Claude（闸 B 语义主审） | `cm-dev-02` 称「资料没有给出具体代码实现」，但讲义 chunk 5 有 `SET NX PX timeout` 原文；该 chunk 未入 selected | **Cursor 维持 PASS**（不展开≠错述）；记覆盖优化，不阻塞 | `semantic-review.md`；不改归档 |
| 2026-10-31 | Claude（闸 A A10/A11 审阅） | A10 可关但仅接口 SSE（非前端 UI）；停后仍有大量 in-flight 字；A11 答案层不串但缺时序字段，`sessionsDistinct` 弱；清单状态行曾与 ✅ 矛盾 | **采纳边界说明**；状态行已改齐；脚本补 start/end 时序后复跑 A11；前端实停留给产品人工验收 | `gate-a-a10-a11-notes.md`；本表下节 |

### 闸 A A10/A11 证据边界（Claude 审阅，2026-10-31；Cursor 落盘）

- A10 可关，但仅覆盖接口级 SSE 停答（非前端 UI）；停后仍可能有较多 in-flight token。「少量 in-flight」口径偏宽，单次运行不足以严格区分「及时中止」与「关流后冲刷缓冲」。建议产品人工验收补一次前端实停，或后续以更大 before-stop 文本复跑。
- A11 可关（答案层不串课，跨主题问未回填 MySQL 正文），但初版证据缺时序：报告无请求起止/重叠字段，`sessionsDistinct` 在 3 个非空会话下偏弱，并发仅由脚本「双线程先 start 后 join」推定。已要求脚本记录每轮 start/end；`overreachNoLeak` 中宽泛 `未` 子串判定改为更稳拒答标记。
- 清单/笔记顶部曾仍写「未专项手测 / 下一步 A10/A11」，与表格 ✅ 矛盾——已同步改齐。
