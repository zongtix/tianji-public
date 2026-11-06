# qwen3-rerank 接入记录（2026-10-26）

现有 Embedding 服务地址为 `https://maas.qianwenaiapi.com/compatible-mode/v1`。核对[该服务商官方重排序文档](https://platform.qianwenai.com/docs/api-reference/rerank/dashscope-rerank)后，使用同一服务商的既有 `DASHSCOPE_API_KEY` 调用原生排序接口，未将凭据发送至其他供应商。

## 真实公开示例验证

- 模型：`qwen3-rerank`。
- 地址：`https://maas.qianwenaiapi.com/api/v1/services/rerank/text-rerank/text-rerank`。
- 输入：临时生成的三个公开通用英文示例，不含项目知识、真实用户问题或个人信息。
- 响应：HTTP 200，466ms，`usage.total_tokens=102`，request_id=`0979488d-2feb-94e8-832e-b9b61105c70e`。
- 输出索引与分数：0/0.91224、2/0.64054、1/0.17802。解释排序的两段排在食谱无关段之前。
- 原始无凭据记录：`target/rerank-provider-probe-20261026.json`。

这证明现有账号能调用真实排序模型，不能据此声称已改善项目回答质量或形成生产时延结论。

## 配置和激活

应用已补齐上述默认接口和模型。凭据优先取 `TJ_AI_RERANK_API_KEY`，未设置时从既有 `DASHSCOPE_API_KEY` 读取；没有在源码或报告中保存密钥。通用配置保留 `enabled=false` 和知识库模式B，本地 `application-local.yml` 在用户明确授权后启用模式C与重排开关true。

已继承现有机器环境变量，通过现有 Langfuse 启动脚本重启本地8096，同时在本次进程环境显式设置 `TJ_AI_KNOWLEDGE_RAG_MODE=C` 和 `TJ_AI_RERANK_ENABLED=true`。后续使用local配置启动也会保持启用。排序请求包含本次检索问题及最多12条已获本次授权的知识片段，不包含整个聊天历史；工具使用排序结果索引取回原候选正文。

首次包含项目知识片段的探测被自动审批拒绝：原授权未明确覆盖向外部排序接口发送这批资料。随后只发送公开通用示例验证接口。用户随后明确回复“允许”，授权将当前检索问题及最多12条知识片段发送至同一千问服务；据此完成真实知识库激活与验证，未将故障回退当作重排成功。

## 本地检查

配置测试覆盖现有凭据回退、独立凭据优先、C模式与开关解析，以及local配置持久启用；管线测试覆盖融合后成功重排、分数与原证据排名保留，以及既有故障回退和取消行为。最终5个相关测试类共26项通过，0失败、0错误、0跳过。日志：`target/rerank-activation-20261026-tests.log`；前一轮25项记录保留在 `target/rerank-ready-20261026.log`。

## 日常实例真实验证

- 本地8096已从PID 26512更新到PID 37224，启动日志确认 `mode=C, promptVersion=knowledge-v1, rerankEnabled=true`，健康状态UP。沿用Langfuse观测，没有改动Nacos、课程资料或索引。
- 仅发送一个合成知识库问题：“课程学习当天已经拿满50分，还能通过签到获得积分吗？”回答核心结论为可以，并引用按积分类型分别计算上限的明确规则。回答还附加了未问的签到分值/上限资料缺口，表达问题保留，不宣称整体内容验收通过。
- SSE非空、单次终止、无错误事件、最终回答与历史一致；专用测试身份创建的会话已清理，前后会话数均为0。记录：`target/rerank-activation-20261026-live.json`。
- Langfuse同一trace `d4a69d789c8631c52c930893b5d0e0cc` 中，`rag.vector`=1046ms、`rag.keyword`=123ms、`rag.fusion`=12ms、`rag.rerank`=417ms、`rag.context`=2ms。请求属性为 `rag.rerank.status=success`、`rag.degraded.routes=[]`、`rag.final.status=ON_COMPLETE`，确认真正执行重排且未降级。
- 追踪记录：`target/rerank-activation-20261026-traces.json`；启动日志：`target/langfuse-local/rerank-activation-20261026.stdout.log`。

这是接通与单题链路验证，不是32题全量C/D评测，也不据单次耗时宣称总体性能或质量优于B。原有实验结果文件没有改写。

后续经用户授权完成[6题B/C真实对比](rerank-comparison-2026-10-26.md)：核心结论来源初审通过数5/6→6/6，耗时中位数约增加0.57秒；关键规则由融合第4升至重排第1。保留本地C配置，本轮调优收口；仍未执行32题全量C/D评测。
