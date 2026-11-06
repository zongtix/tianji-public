# 公开版本验证记录

日期：2026-11-06。验证对象为独立的公开源码副本，未导入原仓库的提交历史。

## 发布前处理

- 保留全部 1104 个 Java 源文件。Java 改动限于四个测试文件：将历史支付宝 SDK 测试的凭据改为环境变量并添加显式执行开关，以及将三处测试类中的加密夹具改为明显的固定测试值；未修改 Java 业务实现。
- 移除两份原认证 `.jks`，改为部署者自行生成和注入。
- 腾讯云、短信及认证密钥库配置改为环境变量；移除支付宝测试中的私钥、公钥与原回调地址。
- 原远程主机改为保留的示例域名，原 VPN 地址改为本机默认值。
- 排除原始 RAG 评测输出及相应部署文件清单；历史设计、阶段报告与可复用评测程序保留。历史报告中的结果不等于对公开副本的重新验收。
- local profile 默认关闭 AI 对话正文观测和 rerank，保留按需配置入口。
- 忽略环境变量文件、私有密钥目录、密钥库及生成结果；补充 README、环境变量示例和首次配置说明。

## 实际检查

| 检查 | 结果 |
| --- | --- |
| JDK 17，`mvn -o -P mcp-pilot -DskipTests package` | 29 个 Maven 模块构建通过，包含测试源码编译；未执行全量测试 |
| 定向测试 `UserMemoryHttpTest` | 3 项通过，0 失败、0 错误 |
| `UserMemoryRealInfrastructureTest` | 7 项因真实基础设施开关未启用而跳过 |
| `PayControlledIntegrationTest` | 4 项因真实基础设施开关未启用而跳过 |
| `AliPayTest` | 5 项因外部支付测试开关未启用而跳过 |
| 47 份源 YAML、全部 POM XML | 解析通过 |
| 文档中的 JKS 生成命令及 `tianji` alias 读取 | 在仓库外的临时私有目录验证通过，生成文件未纳入公开副本 |
| Gitleaks v8.30.1 | 公开源码及待提交内容扫描未检出密钥，未使用排除凭据规则 |
| 已识别的旧凭据、旧认证口令及嵌入私钥 | 在公开内容中未发现残留 |

定向测试命令：

```shell
mvn -o -pl tj-aigc,tj-pay/tj-pay-service -am "-Dtest=UserMemoryHttpTest,UserMemoryRealInfrastructureTest,PayControlledIntegrationTest,AliPayTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

运行时关闭了 `TJ_MEMORY_REAL_INTEGRATION`、`TJ_UPGRADE_CONTROLLED_SERVICES` 和 `TJ_ALIPAY_LIVE_TEST`。这些跳过项不能计为真实环境测试通过。

本次未执行整个平台启动、真实 Nacos/数据库/模型/云服务/支付验收，也未验证前端。自动扫描和配置检查不等于完整安全审计。原私有仓库、业务数据及其运行环境未改变。
