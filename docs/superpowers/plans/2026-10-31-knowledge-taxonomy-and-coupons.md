# 知识库分类与向量索引 + 优惠券上线（Final 冻结稿）

> 合成自 Claude 两份评审：`act-as-the-design-floating-hammock.md`、`act-as-the-design-immutable-dijkstra.md`。  
> Cursor 裁定冲突点见文末「冲突裁定」。只做计划边界；实现在独立线程执行。

## 一句话（给产品）

知识库按用途分类即可，物理索引只保留「政策公共库、课程讲义、课程目录」三条主线；优惠券作为政策公共库的新 `knowledgeBaseId` 接入现有知识索引，不单独建索引。讲义当前是 manifest 发布白名单，不是购课权限。

## 对用户原话的折中

「统一设置为独立向量索引」→ **统一分类 + 按用途分索引 + base 级 `documentId` filter 授权**。  
不做「每个 knowledgeBaseId 一个索引」。

## Taxonomy（生产 3 条 + 评测面）

| 名称 | 类型 | 索引 | 访问方式 | 本期 |
| --- | --- | --- | --- | --- |
| `tianji-points` | 政策公共·积分 | `tj_aigc_knowledge_v4_1024` | 登录 + `knowledgeBaseId` → `documentId in ['tianji-points-guide']` | 已上线，保持 |
| `tianji-coupons` | 政策公共·优惠券 | 同上（复用） | 登录 + `tianji-coupons` → `['tianji-coupon-guide']`，与积分白名单**不相交** | **本期做** |
| `course_materials` | 课程讲义 | `tj_aigc_course_materials_v1_1024` | CONSULT tool + manifest `PUBLIC_DEMO/PUBLISHED` + `courseId/materialId`（**非购课**） | 已上线，保持；不做付费 |
| `course_semantic` | 课程目录/正文 | `tj_aigc_courses_v4_1024` | `searchCoursesByGoal` 等 | 保持；不新建 `course_catalog` |
| 评测语料 | 离线对照 | `tj_aigc_eval_*` | 仅评测脚本 | coupons 上线前按裁定做隔离验收 |
| 私有/付费、FAQ | 未来 | 默认复用现有索引 + filter | — | 挂起（`isLessonValid` 未接 AIGC） |

## 独立索引门槛（可操作）

**必须新建（任一）**：embedding/维度/相似度不同；存储或 mapping 不同；整库回滚/合规留存/爆炸半径；授权无法用 metadata filter；导入通道无法共用；评测证明 BM25 干扰不可接受。

**不要新建**：新 `knowledgeBaseId`；FAQ/运营新 base；每门课讲义；仅 topK/threshold 不同；文档改版（新 documentId）。

**降级说明**：「召回参数不同」不是硬门槛（`SearchRequest` 可覆盖）；「强隔离防串库」不能替代 filter，只有整库回滚/合规才值得拆。

## 本期明确不做（YAGNI）

- 每 base 一索引、索引路由、动态 VectorStore、管理后台。
- 新建 `course_catalog`；讲义并入知识索引；跨索引融合。
- 改 chunkSize / embedding / rag.mode；文档自动覆盖更新。
- 接 `LearningClient` / 购课权限；合 `dev` / 发生产（除非另说）。

## 分期（停 Final）

| 阶段 | 工作 | Owner |
| --- | --- | --- |
| **P0** 契约冻结 | 本文件 + 基线（索引名、filter、白名单不相交） | Claude A / 产品 C |
| **P1** 切分去重（可选加速） | 抽出共用 `##`+TokenTextSplitter(600)；golden fixture；**不写生产索引** | Cursor R；可与 P2 并行或 **延后到 coupons 上线后**（见裁定） |
| **P2** 通用导入 | `KnowledgeImportCommand` 参数化（默认 points）；script `-Document`；导入后校验 public-bases docId+hash | Cursor A/R |
| **P3** 隔离验收 | coupons 会话只命中 coupon；points 不串；匿名 401 / 未知 400；积分回归；评测口径见裁定 | Cursor 执行；Claude 核对 |
| **P4** 上线配置 | `public-bases` 增加 `tianji-coupons`；导入生产知识索引；重启 | Cursor R；产品 A |
| **P5** 付费 | 挂起 | 产品 A |

`application.yml` 目标片段：

```yaml
public-bases:
  tianji-points:
    - tianji-points-guide
  tianji-coupons:
    - tianji-coupon-guide
```

回滚：删 `public-bases.tianji-coupons` 并重启（建议排空 coupons 会话）；索引数据可留。

## RACI

| 事项 | Claude | Cursor | 产品 |
| --- | --- | --- | --- |
| taxonomy / 门槛 | A | C | C |
| P1 切分去重 | A（标准） | R | I |
| P2 导入器 | C | A/R | I |
| P3 验收核对 | A | R | C |
| P4 上线拍板 | C | R | A |
| Final 纪律 | C | A/R | I |

## 冲突裁定（两稿合并）

| 冲突 | hammock | dijkstra | **冻结裁定** |
| --- | --- | --- | --- |
| 讲义权限口径 | 「manifest + 购课」偏乐观 | 仅 PUBLIC_DEMO/PUBLISHED，无购课 | **采 dijkstra**：对外不宣称已购课才能查 |
| 召回参数作拆索引门槛 | 列为门槛之一 | 反对硬门槛 | **采 dijkstra** |
| 强隔离 | 四门槛之一 | 降级为条件性 | **采 dijkstra** |
| coupons 评测 | 复用归档 + 3 条冒烟，不重跑四模式全量 | 上线前必须隔离索引重跑对照 | **本期采折中**：P3 必做隔离冒烟 + 引用 `points-coupons-2026-10-30` 归档；**不默认付费重跑四模式**；若冒烟异常再开 P3′ 全量 |
| P1 切分去重 | 明确不做 | P1 必做 | **采折中**：不阻塞 coupons；P2→P3→P4 可先走；P1 作为同 PR 或后续小项，禁止第三份切分副本 |
| 本期步骤粒度 | 导入器→注册→导入→验收 | P0–P5 更完整 | **执行序**：P0（本文）→ P2 → P3 → P4；P1 不阻塞；P5 挂起 |

## 验证清单（P3/P4）

1. `mvn -pl tj-aigc -am -DskipTests package` + `KnowledgeDocumentServiceTest` / `KnowledgeBaseAccessTest`
2. 导入 coupons → 约 9 片段；points 仍约 7；重复导入 skipped
3. coupons 会话不出现积分证据；points 会话不出现优惠券证据
4. 匿名 401；未知 knowledgeBaseId 400
5. 前端/会话入口能传 `tianji-coupons`（若仅后端则可 API 验收）
6. 证据：导入 JSON + 验收记录；引用归档评测，不默认全量付费重跑
