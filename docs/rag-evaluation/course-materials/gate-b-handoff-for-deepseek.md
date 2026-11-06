# 闸 B 交接说明（给 DeepSeek）

日期：2026-10-31  
作者：Cursor  
前提：**闸 A 关键项已过**（见同目录 `acceptance-checklist.md`、`gate-a-notes.md`）。

请你按方案 P3 / Task 7 冻结开发侧材料；**不要改** Cursor 的 `holdout-cases-draft.json`（保留题，不参与调参）。

## 你这边要交付

1. **冻结开发题** `dev-cases-draft.json`  
   - 约 24～32 题量级（可分批）；覆盖：直接讲义问答、同义、条件/否定、跨章节、相似课干扰、资料缺失、价格+讲义混合。  
   - 每题标明 `authorizedCourseIds`、期望锚点（materialId / 章节 / 可核对原文短语）、`participatesInTuning: true`。  
   - 冻结后记录内容哈希或版本日期，写进本题 notes 或单独 freeze 小节。

2. **证据锚点与来源审阅**  
   - 对 Redis 主课 + MySQL 对照课现有 Markdown：技术事实、课程绑定、演示声明仍成立。  
   - 若改讲义正文：同步 bump manifest / 重新导入索引，并通知跑评测的人刷新 `materials-import` 报告。

3. **语义来源审阅（评测跑完后）**  
   - 对照归档回答是否贴讲义原文；分歧记到双方都能看到的地方（可回 `dissent-log.md` 或你方审阅文件）。

## 当前环境快照（便于你对齐）

| 项 | 值 |
| --- | --- |
| 主课 | `910000000000010005` Redis |
| 对照课 | `910000000000010004` MySQL |
| 讲义索引 | `tj_aigc_course_materials_v1_1024` |
| 导入报告 | `target/aigc-es/course-materials/materials-import.json` |
| 闸 A 聊天报告 | `target/aigc-es/course-materials/gate-a-chat-smoke.json` |
| 本地开关 | `TJ_AI_COURSE_MATERIALS_ENABLED=true` + `store-type=elasticsearch` + knowledge enabled |

## 不要做

- 不把 holdout 题拿来调参或改提示词「刷分」。  
- 闸 B 未冻结指纹前，不要扩大到付费资料或正式售卖课讲义。  
- 生产 Java / 提示词冲突热点仍归 Codex；你这边以 fixture、开发题、来源审阅为主。

## 回传格式建议

短消息即可：

1. `dev-cases` 是否已冻结 + 题量 + 哈希/日期  
2. 讲义是否有改动（有则列出 materialId）  
3. 你期望的评测运行窗口（谁跑 A/A′/B/C）
