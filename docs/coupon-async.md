# 优惠券异步领取与兑换

## 本次边界

在原同步接口之外新增异步入口。采用数据库持久化请求（兼作 outbox）、RabbitMQ 通知、
事务内行锁发券和结果查询。不是 Redis Lua 预扣库存方案，不承诺秒杀级吞吐；热门券仍在数据库行锁处串行。
异步化缩短的是 HTTP 等待链路，并提供可恢复、可观测的受理状态，不是把排队受理当成发券成功。

原有同步接口不变：

- `POST /user-coupons/{id}/receive`
- `POST /user-coupons/exchange`

不修改前端、订单、支付或优惠券核销，不自动执行生产 DDL。
实现阶段未启动业务服务；后续已使用独立验收实例完成开发库联调，范围见文末验收记录。
推荐方案接口见 `docs/coupon-recommendation.md`。

## 启用顺序

1. 在目标 `tj_promotion` 数据库审核并执行 `tj-promotion/src/main/resources/db/coupon-claim-request.sql`。
   该脚本只新增请求表，不回填、不删除已有优惠券数据；应用没有自动数据库迁移。
   对已有同名表，`IF NOT EXISTS` 不会校验或补齐字段、唯一约束和索引，必须自行核对结构。
2. 确保 RabbitMQ 可连接，应用账号具备声明交换机、队列、绑定和投递/消费权限。
3. 配置 `tj.promotion.async.enabled=true`，再重启促销服务。
   默认不加载异步 Controller、Service、Listener 和发布任务，因此未建表的现有环境不受影响。
4. 客户端主动切换到新入口，并接入状态轮询；旧页面不会自动变成异步领券。
5. 网关为这些入口配置按用户的速率/并发/请求体限制，避免换幂等键大量占用持久化队列。

可选配置 `tj.promotion.async.publish-interval-ms` 默认 1000。每实例每批最多投递 100 条，消费者并发 2。
未完成请求投递后 10 秒仍未终态会再次投递；MQ 未确认或消息路由丢失不会据此将请求误记为成功。

## 接口

所有接口都要求登录，用户由服务端 `UserContext` 获取，不能用请求参数覆盖。
两个提交接口要求 `Idempotency-Key` 头：8~64 位字母、数字、下划线或短横线。
网络超时后的原请求重试必须复用同一键；用户发起新的领取操作才生成新键。

### 提交公开领券

`POST /user-coupons/{id}/receive-async`，无请求体。

### 提交兑换

`POST /user-coupons/exchange-async`，请求体为 `{"code":"兑换码"}`。
提交时仅校验码格式、校验位及序列号范围，真实码存在性、使用状态、过期、库存和累计限领仍由工作事务检查。

### 查询本人请求

`GET /user-coupons/requests/{requestId}`。

响应数据（运行环境沿用项目既有统一响应包装）：

```json
{
  "requestId": "866081e0-dd84-47ea-85d4-2a177d310e5e",
  "status": "PENDING",
  "userCouponId": null,
  "message": "请求已受理，等待处理"
}
```

- `PENDING`：已可靠保存请求，尚未确认领到券；没有预留库存，不可显示“去使用”。
- `SUCCEEDED`：已发券，`userCouponId` 是真实 `user_coupon.id`。
- `FAILED`：业务拒绝或系统重试耗尽；`message` 给出业务原因或安全的系统提示。

同一用户、同一键、同一业务参数返回原请求及其最新结果，不二次创建。
同一键改换优惠券或兑换码返回 400；其他用户不能查询该请求，也不返回码、用户 ID 或内部错误。
建议客户端按 1~2 秒间隔轮询，达到 UI 等待上限后保留 requestId 稍后续查，不另造请求。
关闭功能开关后查询入口也关闭，因此关闭前应先停止新增请求并排空 PENDING。

## 一致性与恢复

1. HTTP 请求只落请求表，持久化失败不返回受理成功；不使用进程内队列或提交后即遗忘的 `@Async`。
2. 定时任务查询到期 PENDING 请求，仅向 durable RabbitMQ 队列发送 requestId；消息标记为持久化。
   投递失败保留请求，下轮重试；投递后进程崩溃或确认丢失允许重复投递。
3. 消费者先对请求行执行 `FOR UPDATE`，再经 Spring Bean 代理调用现有发券事务。
   请求结果、库存计数、用户券以及兑换码核销在同一事务提交。
   同一请求重复消息、ACK 丢失重投及并发处理只产生一张用户券。
4. 库存、累计限领（包括已使用/已失效券）及兑换码唯一性复用原数据库锁定校验。
   不对 `(user_id,coupon_id)` 加唯一约束，保留每人可领多张的合法规则。
   同步与异步并行期间仍由同一券模板行锁协调。
5. 时间以工作事务实际执行时刻为准，不以提交受理时间保留资格。
   排队期间结束发放、兑换码过期、券已领完或达到限额都可能最终 FAILED。
6. 业务失败先完整回滚，再单独记录 FAILED。
   技术失败完整回滚后按 2、4、8、16 秒退避，最多累计 5 次失败后终止；等待退避时的重复消息不重复计数。
7. 故障记录本身失败时消费者记录日志并正常返回，避免 MQ 立即热循环 requeue；原持久化 PENDING 请求稍后补投。
   数据库长时间不可用期间无法累计 attempts 或查询状态，不能把 5 次失败理解为固定墙钟超时。
8. 多实例重复发布允许且安全；部署节点须同步时钟。不存在业务状态的消息直接忽略，不相信 MQ 消息里的用户身份。

## 运维与限制

- 监控 PENDING 总量和最老 create_time、FAILED 原因、系统失败 attempts、数据库锁等待、MQ 积压及重投日志。
  当前没有内置待处理配额和跨实例发布租约，不能未经限流/容量验证直接当作高并发抢券平台。
- 请求表包含兑换码，按敏感业务数据限制查询、备份和日志访问，不返回到 API。终态不会自动清理。
  清理策略需先约定幂等重放保留窗口；删除终态请求会缩短幂等保证，不能随意清理仍在处理的记录。
- 消息重复、进程重启和 broker 短时故障依靠 PENDING 重投恢复；永久 MQ 配置错误会一直排队，需要告警和人工修复。
  broker 完全不可用时不会自动改为同步发券。
- 本地回归使用 H2 MySQL 模式验证事务与并发，并以 mock 验证 RabbitTemplate 投递、失败及回放。
  后续开发环境真实 MySQL/RabbitMQ 联调单独记录，不等同于跨实例压测或精确 ACK 故障窗口验收。
- 2026-10-30 已在开发库迁移请求表，并启动、重启及停止专用验收实例。
  未修改公共 Nacos 配置，未切换常规服务或前端，未提交或推送。

## 本地回归

2026-10-30 验证：聚焦回归 88 项通过；`tj-promotion` 全模块 168 项通过，0 失败、0 错误、0 跳过。
其中新增异步测试 29 项、推荐测试 18 项，包含事务回滚、并发边界、故障补投与金额溢出回归。
这些结果不包含真实 MySQL/RabbitMQ 联调、服务重启验收或压测。
随后完成的真实环境联调见 `docs/coupon-live-verification-2026-10-30.md`，不计入上述单元/组件测试数量。

在 Java 17 与现有 Maven 设置下：

```powershell
$env:JAVA_HOME='D:\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -s C:\Users\your-user\.m2\settings.xml -o -pl tj-promotion -am '-Dtest=CouponClaimTest,CouponClaimControllerTest,CouponClaimConfigurationTest,CouponClaimListenerTest,CouponRecommendationServiceTest,CouponRecommendationControllerTest,UserCouponReceiveTest,UserCouponExchangeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

## 本人券列表闭环

后续已补齐前端原有 `GET /user-coupons/page` 查询，不依赖异步开关，不修改发券事务。
接口说明见 `docs/user-coupon-page.md`；真实页面三栏与兑换后刷新复验见 `docs/user-coupon-page-verification-2026-10-30.md`。
该次补齐后促销模块全量 191 项测试通过；旧的 168 项为实现阶段历史结果，不含新增分页测试。
