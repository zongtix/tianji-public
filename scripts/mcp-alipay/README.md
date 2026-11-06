# 本机支付宝 MCP 沙箱购买

从助手课程卡片读取服务端价格，用户确认后复用 `IOrderService.placeOrder` 创建业务订单，再通过官方支付宝 MCP 生成网页支付链接，手动查单。金额和用户归属不交给模型决定。

这是沙箱支付演示。虚拟支付结果只保存在独立 Redis 确认信息中，不调用 `handlePaySuccess`，不更新正式订单为已支付，不发送课程开通消息。正式支付、退款及完整支付回调不属于此入口。

## 前置条件

- Java 17、Node、已安装依赖的前端 `D:/Java/tj-protal`。
- 原课程资料试点已经启动，`scripts/mcp-pilot/pilot.ps1 -Action Check` 通过。
- Windows User 环境变量 `AP_APP_ID`、`AP_APP_KEY`、`AP_PUB_KEY` 为同一个支付宝沙箱应用的凭据。脚本只向交易子进程传递凭据，不写文件、不传给前端。
- 已构建 `tj-trade/target/tj-trade.jar`。Windows 的 MCP stdio 必须使用 UTF-8，启动脚本已加 `-Dfile.encoding=UTF-8`。
- 安装固定版本官方包（不运行安装脚本）：

```powershell
npm install --prefix target/alipay-sandbox-preflight/runtime --ignore-scripts --no-audit --no-fund --save-exact @alipay/mcp-server-alipay@2.0.0
```

## 启停

在仓库根目录运行：

```powershell
./scripts/mcp-alipay/pilot.ps1 -Action Start
./scripts/mcp-alipay/pilot.ps1 -Action Check
./scripts/mcp-alipay/pilot.ps1 -Action Stop
```

入口：`http://127.0.0.1:18085/#/assistant/index?pilot=mcp`。后端只监听 `127.0.0.1:19188`。Stop 只结束本脚本记录且启动时间匹配的两个进程，保留原课程试点、日常服务、订单数据。

默认业务配置关闭；独立 `alipay-sandbox` profile 才启用。脚本固定试点账户 `900000000000000028` 和两门已授权课程。代理剥离浏览器账户信息后注入试点身份；不要将此本机代理当成线上身份认证。

## 浏览器操作

打开已有课程咨询会话，在课程卡片点击“沙箱购买”，核对从服务端读取的课程名称和金额，再点击“确认创建沙箱支付链接”。打开沙箱收银台，使用支付宝控制台提供的沙箱买家账户完成虚拟付款，回来点击“查询支付结果”。普通支付宝账户和真实资金不用于此演示。

预览不创建业务订单；同一确认令牌重复提交会复用订单及链接。确认信息有效期 10 分钟；确认或订单过期后重新选择课程。关闭页面后不会自动付款、自动查单或自动退款。

## 验证

仅运行沙箱专项测试，避免运行仓库中既有生产网关示例测试。Maven 参数至少包含：

```text
-pl tj-trade -am -DargLine=-Dfile.encoding=UTF-8 -Dtest=AlipaySandboxMcpTest,SandboxCheckoutServiceTest,SandboxCheckoutDisabledTest -Dsurefire.failIfNoSpecifiedTests=false
```

`AlipaySandboxLiveTest` 默认跳过；显式设置 `TJ_ALIPAY_MCP_LIVE=true` 并向测试进程提供上述凭据及 `ALIPAY_MCP_SCRIPT`、`ALIPAY_MCP_NODE` 后，只对固定不存在订单执行一次真实沙箱查单，不创建支付。

官方创建工具会生成签名链接并尝试预创建，但不向调用方报告预创建的业务返回码。因此“链接已生成”只表示链接已校验，不能代替服务端查单结果。支付成功只以支付宝查单的已知状态及匹配金额判断；权限、验签等错误不会被当成不存在交易。
