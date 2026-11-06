# 公共规则按需检索设计（积分 + 优惠券）

> 产品确认：2026-10-31。合成自 Cursor 方案 A + DeepSeek `AGREE_VARIANT`。  
> 实现计划：`docs/superpowers/plans/2026-10-31-on-demand-public-rules.md`。

## 一句话

普通会话不再选知识库；问到积分/签到/优惠券规则时，KNOWLEDGE 按需调用 `searchPublicRules(domain, question)`，每次只查一个 `documentId`（走 `.keyword`）。旧 `knowledgeBaseId` 会话钉住兼容、不迁移。

## 产品确认

1. 旧「积分与签到」历史会话保留原样可用，不迁移 — **接受**。
2. 「某门课能得多少积分」类混合题：本期只保证通用规则，不保证结合课程算数值 — **接受**。

## 架构

| 层 | 行为 |
| --- | --- |
| 前端 tj-protal | 删除「积分与签到问答」；新会话不传 `knowledgeBaseId` |
| 路由 | 平台规则问句 → KNOWLEDGE（提示词 + 可选确定性规则） |
| Tool | 单工具 `searchPublicRules`；`domain ∈ POINTS\|COUPONS` 服务端映射白名单 |
| 旧会话 | `knowledgeFilter != null` 仍走 Advisor；**不挂**公共规则工具 |
| 硬修 | `CourseToolPreparationAdvisor` 仅 CONSULT/RECOMMEND，避免劫持 KNOWLEDGE |

## 明确不做

合并白名单、新索引、POLICY Agent、前端选库器、余额/订单查询、讲义与规则跨索引融合、迁移旧会话、删 `knowledgeBaseId` 字段。

## 验收摘要

- 普通会话：问积分无券原文；问券无积分原文；混合题可双域各调一次并标注来源。
- 旧 points 会话：行为不变、无跨库工具。
- 闲聊不触发规则检索；课程/讲义回归不变。
