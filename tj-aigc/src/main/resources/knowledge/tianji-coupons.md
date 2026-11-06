# 天机学堂优惠券规则

本文面向天机学堂学习者，说明优惠券类型、公开券展示、领取条件、有效期、兑换码和“我的优惠券”状态的基本规则。核对日期为 2026-10-30，规则依据 `tj-promotion` 当前实现逐项核对，来源写在每节末尾。

本文只描述同步领取与兑换链路；异步领取入口、优惠券推荐、订单结算、支付、退款和积分联动均不在本文范围，不能依据本文推断。

## 优惠券类型与金额单位

优惠券支持四种折扣类型：

- 每满减：discountType 为 1。
- 折扣：discountType 为 2，discountValue 保存折扣值，80 表示 8 折。
- 无门槛：discountType 为 3，thresholdAmount 必须为 0。
- 满减：discountType 为 4，discountValue 保存满减金额。

折扣券的折扣值必须在 1~99 之间，等于或大于 100 会被拒绝。
每满减和满减券的 thresholdAmount 必须大于 0；无门槛券的 thresholdAmount 必须为 0。
thresholdAmount、maxDiscountAmount 等金额字段以“分”为单位；每满减和满减券的 discountValue 是抵扣金额，也以分为单位，例如 5000 表示 50 元。折扣券的 discountValue 表示折扣比例，不是金额。
thresholdAmount 表示使用门槛：0 表示无门槛，其他值表示最低消费金额。
每张券的总量配置范围为 1~5000，每人限领配置范围为 1~10。
限定使用范围的券（specific=true）必须至少配置一个范围；不限定范围的券不需要范围配置。

具体某张券实际打几折、门槛多少、最高优惠多少，以该券详情字段为准；本文不提供任何具体活动券的库存、价格或折扣数据。

来源：

- `tj-promotion/src/main/java/com/tianji/promotion/enums/DiscountType.java`
- `tj-promotion/src/main/java/com/tianji/promotion/domain/po/Coupon.java` 中 discountValue、thresholdAmount、maxDiscountAmount、totalNum、userLimit 字段说明
- `tj-promotion/src/main/java/com/tianji/promotion/domain/dto/CouponFormDTO.java` 中 totalNum、userLimit 校验
- `tj-promotion/src/main/java/com/tianji/promotion/service/impl/CouponServiceImpl.java` 的 `saveCoupon`
- `tj-promotion/src/main/java/com/tianji/promotion/domain/po/CouponScope.java`

## 公开优惠券列表

`GET /coupons/list` 只返回同时满足以下条件的券：

- 券配置状态为“发放中”（ISSUING，值为 3）；
- 获取方式为“手动领取”（PUBLIC，值为 1）；
- 当前时间已到发放开始时间，且尚未到发放结束时间。

列表中的 available 表示“当前是否可以领取”：

- 已发行数量 issueNum 必须小于总数量 totalNum；
- 当前用户在该券上的历史领取记录数必须小于每人限领上限 userLimit。

历史领取记录不区分状态，已使用和已失效的券也占用累计领取名额。

列表中的 received 表示“当前是否持有一张可用的券”，不是“历史上是否领过”：

- 必须存在状态为未使用（UNUSED）的用户券；
- 有效期已经开始（termBeginTime 为空或不晚于当前时间）；
- 有效期结束时间晚于当前时间。

没有领取记录时，个人已领数量按 0 计算。没有符合条件的券时返回空列表。

来源：`tj-promotion/src/main/java/com/tianji/promotion/service/impl/CouponServiceImpl.java` 的 `queryIssuingCoupons`；`tj-promotion/src/main/java/com/tianji/promotion/domain/vo/CouponVO.java`；`tj-promotion/src/main/java/com/tianji/promotion/enums/UserCouponStatus.java`；`tj-promotion/src/main/java/com/tianji/promotion/enums/CouponStatus.java`；`tj-promotion/src/main/java/com/tianji/promotion/enums/ObtainType.java`。

## 手动领取条件与限领

- 必须登录，用户身份由服务端认证上下文提供，请求参数不能覆盖。
- 优惠券必须存在，状态必须是发放中（ISSUING）。
- 手动领取入口只接受获取方式为手动领取（PUBLIC）的券；兑换码券不能从该入口领取。
- 当前时间必须在发放窗口内：发放开始时间不晚于当前时间，发放结束时间晚于当前时间。
- 已发行数量必须小于总数量，否则提示“优惠券已领完”。
- 同一用户在该券上的累计领取数量（含已使用、已失效）必须小于每人限领上限，否则提示“已达到该优惠券的领取上限”。
- 领取成功后生成一张状态为未使用的用户券，并将已发行数量加一；已发行数量更新和用户券记录在同一事务内完成。

来源：`tj-promotion/src/main/java/com/tianji/promotion/service/impl/UserCouponServiceImpl.java` 的 `receiveCoupon`、`receiveCouponForUser`、`issueUserCoupon`；`tj-promotion/src/main/java/com/tianji/promotion/controller/UserCouponController.java` 的 `receiveCoupon`；`tj-promotion/src/main/java/com/tianji/promotion/enums/UserCouponStatus.java`。

## 有效期

- 如果券配置的有效期天数 termDays 大于 0，用户券的有效期从领取或兑换成功时刻开始，结束时间为该时刻加 termDays 天；不是从活动发放结束时间开始计算。
- 如果 termDays 等于 0，使用券配置的固定有效期 termBeginTime 和 termEndTime。
- 发放配置时，termDays 大于 0 不能同时指定固定有效期；termDays 等于 0 必须同时提供固定有效期起止时间，且结束时间晚于开始时间。
- 固定有效期的结束时间不能早于发放结束时间。
- 领取或兑换时还会再次校验计算出的有效期：开始或结束时间为空、结束时间不晚于开始时间、或结束时间不晚于当前时间，都会领取失败。

来源：`tj-promotion/src/main/java/com/tianji/promotion/service/impl/UserCouponServiceImpl.java` 的 `issueUserCoupon`；`tj-promotion/src/main/java/com/tianji/promotion/service/impl/CouponServiceImpl.java` 的 `issueCoupon`；`tj-promotion/src/main/java/com/tianji/promotion/domain/po/Coupon.java` 中 termDays、termBeginTime、termEndTime 字段。

## 兑换码

- 兑换码为 8~10 位大写字母或数字，字符只来自 `23456789ABCDEFGHJKLMNPQRSTUVWXYZ`，不包含 0、1、I、O；格式不符直接返回“无效兑换码”。
- 必须登录后兑换，用户身份由服务端认证上下文提供。
- 兑换时会校验兑换码的校验位、序列号范围、码值、类型和兑换目标字段；这些字段不匹配返回“无效兑换码”。目标优惠券不存在时返回“优惠券不存在”。
- 兑换码状态为已兑换时，返回“兑换码已兑换”；不是待兑换、或过期时间为空、或已过期时，返回“兑换码已失效”。
- 一个兑换码只能成功兑换一次；状态更新使用“待兑换且未过期”作为条件，成功后才发券。
- 系统生成的兑换码以对应优惠券的发放结束时间作为过期时间。
- 兑换要求目标券的获取方式为指定发放（ISSUE，值为 2），仍要经过发放中状态、发放窗口、库存、每人限领和有效期检查；如果发券失败，兑换码的核销会一起回滚，不会消耗兑换码。

来源：`tj-promotion/src/main/java/com/tianji/promotion/service/impl/UserCouponServiceImpl.java` 的 `exchangeCoupon`、`exchangeCouponForUser`；`tj-promotion/src/main/java/com/tianji/promotion/domain/dto/CouponExchangeDTO.java`；`tj-promotion/src/main/java/com/tianji/promotion/utils/CodeUtil.java` 的 `parseCode`；`tj-common/src/main/java/com/tianji/common/constants/RegexConstants.java` 的 `COUPON_CODE_PATTERN`；`tj-promotion/src/main/java/com/tianji/promotion/domain/po/ExchangeCode.java`；`tj-promotion/src/main/java/com/tianji/promotion/service/impl/ExchangeCodeServiceImpl.java` 的 `asyncGenerateCode`。

## 我的优惠券列表

`GET /user-coupons/page` 按状态查询本人优惠券，status 可省略，默认 1：

- 1 未使用：只返回状态为未使用且有效期结束时间晚于当前时间的券。
- 2 已使用：返回状态为已使用的券。
- 3 已失效：返回状态为已失效的券，以及状态仍为未使用但有效期结束时间不晚于当前时间的券。

返回结果会做展示转换：状态为未使用但已过有效期的券，在列表中显示为已失效。
该转换只影响展示，不修改用户券的存储状态。未使用列表只按结束时间过滤，有效期尚未开始的未使用券也会出现；已使用券过期后仍归入已使用列表。
列表按领取时间倒序、id 倒序排列。
状态参数不是 1、2、3 时返回“优惠券状态不正确”。
如果用户券对应的优惠券模板查不到，名称显示为“优惠券信息不可用”。

来源：`tj-promotion/src/main/java/com/tianji/promotion/service/impl/UserCouponServiceImpl.java` 的 `queryUserCouponPage`；`tj-promotion/src/main/java/com/tianji/promotion/domain/query/UserCouponQuery.java`；`tj-promotion/src/main/java/com/tianji/promotion/enums/UserCouponStatus.java`；`tj-promotion/src/main/java/com/tianji/promotion/domain/vo/UserCouponPageVO.java`；`tj-promotion/src/main/java/com/tianji/promotion/controller/UserCouponController.java` 的 `queryUserCouponPage`。

## 本文边界

本文没有说明以下问题，不能据此推断：

- 优惠券与积分、签到、课程学习进度之间的联动；
- 下单、支付时的具体抵扣顺序和金额计算；
- 退款、取消订单后优惠券是否退回；
- 异步领取请求的受理和结果状态；
- 优惠券推荐方案；
- 具体活动的库存、价格和折扣数据。
- 限定范围如何匹配课程或分类，以及并发领取的锁机制和发放状态定时推进机制。
