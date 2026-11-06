# 支付宝 MCP 沙箱前置核查

2026-10-27。当前结论：凭据核查、官方 MCP 工具发现、真实只读查单及最小业务接入均已通过；自动联调已生成沙箱支付链接并打开对应课程、金额的收银台，后续用户反馈“我已实测验收成功”，沙箱付款与查单的人工验收通过。本次不实现正式课程开通或支付回调。接入结果见 [沙箱购买验收记录](alipay-sandbox-checkout-2026-10-27.md)。

## 凭据配置后的复核

- 沙箱内读取曾未发现变量；提升权限的只读检查确认 `AP_APP_ID`、`AP_APP_KEY`、`AP_PUB_KEY` 均存在于 Windows User 环境变量。不是用户漏配，也不需要再次粘贴或重建密钥。
- APPID 数字格式检查通过；应用私钥为 PKCS#8、支付宝公钥为 SPKI，均为 2048 位 RSA。本机使用应用私钥签名并用其派生的应用公钥验签通过；支付宝公钥与该应用公钥不同，排除了直接误填应用公钥的情况。
- 本地格式检查本身不证明应用配置匹配；后续已通过受控只读查单取得预期的 `40004/ACQ.TRADE_NOT_EXIST`，且实际签名链接可打开沙箱收银台。未将“交易不存在”误报为支付成功。
- Windows 用户未设置沙箱选择及工具限制。检查进程仅在子进程环境中强制 `AP_CURRENT_ENV=sandbox`、限定网页支付/查单、关闭日志，不修改 User/Machine 环境变量，结束后恢复父进程环境。
- 官方 `@alipay/mcp-server-alipay@2.0.0` 安装在忽略的 `target/alipay-sandbox-preflight/runtime`，安装禁用生命周期脚本。实际以 stdio 完成 initialize、initialized 通知和 tools/list，只发现 `create-web-page-alipay-payment` 与 `query-alipay-payment`；检查后关闭进程。
- 初次协议探针只调用初始化和工具发现。后续自动联调已执行真实查单、创建链接及一笔试点业务订单，没有自动付款、退款或调用模型，未修改日常支付配置；此后用户报告人工实测验收成功。

脱敏证据：`target/alipay-sandbox-preflight/key-check.json`、`protocol-check.json`。前者仅保存格式、位数和验证布尔值，后者只保存协议和工具 Schema 信息，均不包含凭据。

## 初次核查记录（配置前）

- Process/User/Machine 环境变量中，`AP_APP_ID`、`AP_APP_KEY`、`AP_PUB_KEY` 及原项目的 `ALIPAY_APP_ID`、`ALIPAY_MERCHANT_PRIVATE_KEY`、`ALIPAY_PUBLIC_KEY` 均未配置。
- 支付服务的 application/local/dev 配置没有提供有效的应用 ID、私钥和支付宝公钥；当前 Nacos `pay-service.yaml` 未定义这些字段，local/dev 专用 Data ID 无法读取。
- 检查的 IDEA workspace/runConfigurations 中未找到相关环境配置。
- `AliPayTest.java` 含硬编码示例，示例网关为生产地址；未采用这些示例参数，未运行该测试。
- 本机 Nacos 8848 可连接，支付服务 8087 未监听。原支付宝 Java 配置的默认网关也是生产环境，不能据此认定已有沙箱。

以上为配置前记录，只说明当时已检查位置没有可用配置，不代表用户的支付宝账号下没有沙箱应用。初次核查没有登录用户账户、安装或运行支付宝 MCP、创建支付单、付款、退款或修改日常支付配置；配置后的复核见上节。

脱敏核查结果：`target/alipay-sandbox-preflight/report.json`，不包含凭据值。

## 凭据来源与配置约定

登录自己的[支付宝沙箱控制台](https://open.alipay.com/develop/sandbox/app)，在同一个沙箱应用下准备：

| 本机用户环境变量 | 来源 |
| --- | --- |
| `AP_APP_ID` | 沙箱应用 APPID |
| `AP_APP_KEY` | 与该沙箱应用公钥匹配的应用私钥 |
| `AP_PUB_KEY` | 该沙箱应用对应的支付宝公钥，区别于应用公钥 |

通过 Windows 用户环境变量配置，不写进仓库或聊天。私钥需要按支付宝工具生成/导出，不能从 APPID 推导，也不能用支付宝公钥代替。若应用要求上传应用公钥，应在控制台完成设置并确认匹配。

本次独立试点还应明确配置以下非敏感选项，避免默认接入生产或开放退款：

```text
AP_CURRENT_ENV=sandbox
AP_SELECT_TOOLS=create-web-page-alipay-payment,query-alipay-payment
AP_LOG_ENABLED=false
```

修改后后续检查会从 User/Machine 作用域重新读取，不能仅以当前旧终端未继承变量判定缺失。

## 条件满足后的范围

先核验密钥格式和沙箱选择，再做电脑网页支付与查单的最小接入。商户订单、金额、用户归属仍由现有交易/支付服务确定；用户确认后生成沙箱支付链接。支付完成须以验签回调或服务端查单确认，不以模型文字或浏览器跳转判定成功。回调、返回地址在具体接入时另行核对。

官方包：[`@alipay/mcp-server-alipay`](https://www.npmjs.com/package/@alipay/mcp-server-alipay)。固定使用 `2.0.0`，子进程强制 sandbox。初始化、业务接口、收银台与付款成功分别验收，不通过前一阶段推断后一阶段成功。
