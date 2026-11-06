# 公开版配置说明

公开副本保留业务源码与测试，移除了原认证密钥库、硬编码云服务凭据、支付宝测试私钥，以及原始评测输出。下文用于在自己的环境准备配置，不会连接原开发环境。

## 1. 基础设施与 Nacos

按需要准备 MySQL、业务 Redis、RabbitMQ、Nacos；搜索和 AI 知识库另外需要 Elasticsearch 或独立向量 Redis。MongoDB、XXL-JOB、Seata、Langfuse 及云服务按所启动的模块启用。

在自己的 Nacos 中创建 namespace，设置 `NACOS_SERVER_ADDR`、`NACOS_NAMESPACE`，并以 `local` profile 启动。各模块 `application.yml` 的 `spring.config.import` 列出实际需要的 Data ID。常见公共配置如下：

| Data ID | 按自己的环境填写 |
| --- | --- |
| `shared-spring.yaml` | 公共 Spring 配置 |
| `shared-mybatis.yaml` | 数据源、MyBatis；模块通过 `tj.jdbc.database` 区分业务库 |
| `shared-redis.yaml` | `spring.data.redis` 的连接及认证 |
| `shared-mq.yaml` | RabbitMQ 的连接及认证，需要 MQ 的模块使用 |
| `shared-feign.yaml` | OpenFeign 配置，需要该配置的模块使用 |
| `shared-logs.yaml` | 日志配置 |
| `${spring.application.name}.yaml` | 对应服务的补充配置 |

不要把其他环境导出的 Nacos 配置直接提交到仓库。原环境迁移、SSH 隧道和开机维护脚本不是通用安装器，不应未经检查在新机器上运行。

本仓库提供若干功能的增量 DDL，如各模块 `src/main/resources/db/` 下的脚本；它们不是整个平台的初始化数据库。基础业务表和必要数据需要根据获授权的课程资料另行准备，不要把真实用户数据上传到 GitHub。

## 2. 生成自己的认证密钥

不再提供原 `tjxt.jks`。在仓库根目录设置一个自己的强口令，并生成新的 JKS：

```powershell
# 在 IDE 或终端的环境变量中设置 TJ_AUTH_KEY_STORE_PASSWORD。
# 以下命令从环境读取口令，命令本身不包含口令值。
New-Item -ItemType Directory -Force secrets | Out-Null
keytool -genkeypair -alias tianji -keyalg RSA -keysize 2048 -validity 3650 -storetype JKS -keystore secrets/tianji.jks -storepass:env TJ_AUTH_KEY_STORE_PASSWORD -keypass:env TJ_AUTH_KEY_STORE_PASSWORD -dname "CN=tianji-local,OU=Development"
```

`encrypt.key-store` 使用以下变量：

| 变量 | 含义 |
| --- | --- |
| `TJ_AUTH_KEY_STORE` | Spring Resource 地址，默认 `file:./secrets/tianji.jks` |
| `TJ_AUTH_KEY_ALIAS` | 默认 `tianji` |
| `TJ_AUTH_KEY_STORE_PASSWORD` | 必填，密钥库口令 |
| `TJ_AUTH_KEY_PASSWORD` | 私钥口令，默认复用密钥库口令 |

IDE 的工作目录或 Docker 挂载目录不同，应设置绝对资源地址，例如 `file:/opt/tianji/secrets/tianji.jks`。`secrets/` 和密钥库扩展名已加入忽略规则，不能提交。

## 3. AI 与可选外部服务

AI 至少需要配置实际模型地址、模型名及 `DEEPSEEK_API_KEY`，详情见 [AI 模块说明](../tj-aigc/README.md)。知识库、embedding、rerank、MongoDB 和观测平台根据所选功能配置；示例变量见根目录 `.env.example`，更多变量以 `application.yml` 为准。

公开副本默认不在 local profile 中开启对话正文观测，也不自动启用 rerank；需要时自行配置。不要将个人已有的 `~/.tianji/` 私有配置文件随项目分发。

腾讯云媒资读取 `TJ_TENCENT_APP_ID`、`TJ_TENCENT_SECRET_ID`、`TJ_TENCENT_SECRET_KEY`、`TJ_TENCENT_VOD_URL_KEY`、`TJ_TENCENT_COS_BUCKET`；短信读取 `TJ_SMS_ALI_ACCESS_ID`、`TJ_SMS_ALI_ACCESS_SECRET`。不使用这些服务时不要启动对应集成链路。

历史支付宝 SDK 测试 `AliPayTest` 默认跳过。只有显式设置 `TJ_ALIPAY_LIVE_TEST=true` 才会调用外部支付接口；运行前必须自行提供应用 ID、私钥、公钥、回调地址和测试订单号，并核对测试代码中的网关地址与当前账户环境。该测试包含退款、关闭订单等操作，不是离线单元测试。

## 4. 构建与测试

```shell
mvn -P mcp-pilot -DskipTests package
```

构建成功仅说明源码及测试源码可编译，不代表数据库、注册发现、模型、支付或前端链路已经接通。真实基础设施测试需要独立测试实例及显式开启对应环境开关，不能直接对业务环境运行。

公开版本的实际验证范围见 [验证记录](public-release-validation.md)。
