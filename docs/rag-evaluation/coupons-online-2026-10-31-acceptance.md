# 优惠券公共规则库上线验收（Final，2026-10-31）

依据：`docs/superpowers/plans/2026-10-31-knowledge-taxonomy-and-coupons.md`（P0→P2→P3→P4；P1 不阻塞；P5 挂起）。  
Claude 核对：`PASS_WITH_NOTES`（本文件已消化备注中的证据/措辞项）。

## 配置与白名单

| knowledgeBaseId | documentId | 索引 |
| --- | --- | --- |
| `tianji-points` | `tianji-points-guide` | `tj_aigc_knowledge_v4_1024` |
| `tianji-coupons` | `tianji-coupon-guide` | 同上（复用，不相交） |

## 导入证据

- 命令：`scripts/aigc-es/import-points-guide.ps1 -Document coupons`
- 首次导入留档：`docs/rag-evaluation/results/coupons-online-2026-10-31/first-import.json`（`imported=true`，`chunks=9`）
- 复跑报告：`target/aigc-es/knowledge/coupons-import.json`（`imported=false`，`repeatImportSkipped` 取自真实二次导入结果）
- `documentId=tianji-coupon-guide`
- `contentHash=43efb22125d1267ce289a84f08128aa299d40c3010bc1200387a22c484287cc4`（本期冻结）
- 3 条探针均命中预期章节（类型与金额单位 / 有效期 / 兑换码）
- ES 计数：`tianji-coupon-guide=9`，`tianji-points-guide=7`
- 导入前校验：`documentId` 必须落在 `public-bases` 镜像白名单（`KnowledgeImportCommand.PUBLIC_BASES`）

## 隔离与鉴权（单测）

- `KnowledgeBaseAccessTest`：coupons filter 仅含 `tianji-coupon-guide`；未知 id → 400；匿名 → 401；两库 documentId 不相交
- `KnowledgeImportDocumentsTest`：points/coupons 映射、探针、public-bases 对齐
- `KnowledgeDocumentServiceTest`：优惠券切分稳定 id/hash（既有）
- `ElasticsearchExactMetadataFilterConverterTest`：授权字段走 `.keyword`

## 评测口径

- 引用归档：`docs/rag-evaluation/points-coupons-2026-10-30.md` 仅作候选覆盖参考（样本小；**该轮每题同时授权两库，不能用来宣称按库隔离或回答准确率**）
- **本期不默认付费重跑四模式全量**；若线上冒烟异常再开 P3′
- 对外口径：同一索引 + `documentId` 白名单 + `.keyword` 精确匹配；**不是**独立物理索引

## 会话冒烟（HTTP）

脚本：`scripts/local/coupons-http-smoke.py` → `target/aigc-es/knowledge/coupons-http-smoke.json`  
说明：SSE 当前无命中 `documentId` 字段，隔离断言基于回答文本（拒答 + 无券原文），不再依赖空的 `mentionedDocumentIds`。

| 项 | 结果 |
| --- | --- |
| 未知 id → 400 | ✅ |
| 匿名 → 401 | ✅ |
| coupons 会话答优惠券 | ✅ |
| points 会话答积分 | ✅ |
| points 会话问兑换码（隔离） | ✅ 已于 2026-10-31 约 23:04 重启含 `.keyword` 修复的实例后复测通过：拒答/资料不足，未出现兑换码字符集原文 |

### 串库根因与修复

- 现象：points 会话对兑换码问题仍引用《天机学堂优惠券规则》原文（含 `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`）。
- 根因：Spring AI `ElasticsearchAiSearchFilterExpressionConverter` 用 `metadata.documentId:`（analyzed text）。`tianji-points-guide` 与 `tianji-coupon-guide` 共有分词 `tianji`/`guide`，`in ['tianji-points-guide']` 实际命中 16 条（7+9）。
- 对照：`metadata.documentId.keyword:tianji-points-guide` 仅 7 条。
- 修复：`ElasticsearchExactMetadataFilterConverter`（授权字段改走 `.keyword`），挂到 ES `VectorStore` + `KnowledgeKeywordSearch`；已在 a1bf926 合入并经重启后冒烟验证。

## 仍待产品

- 前端/会话入口传 `knowledgeBaseId=tianji-coupons`（本仓库仅 API 验收）
- 入口文案限定为公共优惠券规则（类型/金额单位/领取/有效期/兑换码），不含具体活动券数据

## 纪律

- 分支：Final；不合 dev、不推（除非另说）
- 回滚：删 `public-bases.tianji-coupons` 并重启
- 讲义口径：manifest 发布白名单，非购课；P5 付费挂起
