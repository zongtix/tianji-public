# 优惠券推荐

## 目标与边界

为当前登录用户基于**真实已领取且有效**的用户券推荐一个确定性的优惠券使用方案。推荐只读取数据并计算，
不领取、不核销、不修改订单和支付状态，也不接入任何 AI 模型。

新增内容仅位于 `tj-promotion`：

- `domain/dto/CouponRecommendationDTO`
- `domain/vo/CouponRecommendationVO`、`domain/vo/CouponUsageVO`
- `service/ICouponRecommendationService`、`service/impl/CouponRecommendationServiceImpl`
- `controller/CouponRecommendationController`
- 对应服务与接口测试

推荐功能本身不修改原领取/兑换接口、现有 PO/mapper、`pom.xml` 或公共模块。
同批异步领取改造的独立边界见 `docs/coupon-async.md`。

## 接口契约

`POST /coupon-recommendations`

请求体与现有交易下单的 `PlaceOrderDTO.courseIds` 语义一致，一次最多 10 门课程：

```json
{
  "courseIds": [101, 102]
}
```

当前用户来自网关注入的认证信息（`UserContext`），请求不接受 `userId`。未登录返回 401。

响应：

```json
{
  "originalAmount": 30000,
  "totalDiscount": 6000,
  "payableAmount": 24000,
  "userCouponIds": [11],
  "couponIds": [1],
  "usages": [
    {
      "userCouponId": 11,
      "couponId": 1,
      "couponName": "八折券",
      "discountType": 2,
      "thresholdAmount": 10000,
      "discountValue": 80,
      "maxDiscountAmount": 0,
      "specific": false,
      "scopeBizIds": [],
      "matchedCourseIds": [101, 102],
      "applicableAmount": 30000,
      "discountAmount": 6000,
      "ruleDescription": "折扣：满100.00元享8折；本次抵扣60.00元"
    }
  ]
}
```

- `userCouponIds`：用户券 `user_coupon.id`，按建议核销顺序排列。
- `couponIds`：对应优惠券模板 `coupon.id`，与 `userCouponIds` 顺序一致。
- `totalDiscount`、`payableAmount`：单位均为分。
- `usages`：每张券的规则、参与抵扣的课程和实际抵扣金额，用于解释结果。
- 没有可用券时返回 `totalDiscount=0`、`payableAmount=originalAmount`，两个 id 列表和 `usages` 为空。

推荐结果中的 `userCouponIds` 是用户券 ID，可为后续交易集成提供候选。
当前交易模块的优惠计算仍有 TODO，本次没有接入下单计算、价格重校验、用券锁定或核销，
不能仅将这些 ID 传给下单就认为推荐优惠已生效；本接口不扩展支付或订单核销。

## 数据来源与过滤

1. 当前用户：`UserContext.getUser()`，为空直接抛 `UnauthorizedException`。
2. 用户券：`user_coupon` 中 `user_id = 当前用户`、`status = 未使用`、`term_begin_time` 为空或在当前时间之前、
   `term_end_time` 晚于当前时间。已使用、已失效、未开始、其他用户的券都不会进入候选。
3. 优惠券模板：按用户券的 `coupon_id` 读取 `coupon`；模板缺失的脏数据跳过。
4. 作用范围：按模板 id 读取 `coupon_scope`，`type=1` 表示分类、`type=2` 表示课程。
5. 课程价格与分类：调用已有 `tj-api` 的 `CourseClient.getSimpleInfoList`。课程必须存在、状态为上架、
   未过购买截止时间且价格非负；金额和分类不来自客户端，避免伪造。

## 算法

### 1. 商品项

课程按 `courseId` 升序固定排序，每项保留价格（分）和一级/二级/三级分类 id。原始金额为各项价格之和。
先以 long 累加，超出 Integer.MAX_VALUE 分时明确拒绝，避免金额溢出后返回负金额或错误零优惠。

### 2. 候选券

- 不限定范围（`specific=false`）的券对全部课程生效。
- 限定范围的券只对命中的课程生效：课程范围匹配 `courseId`，分类范围匹配课程的任一级分类。
- 限定范围但没有任何 `coupon_scope` 的券视为不可用。
- 规则非法时跳过：折扣值为空或非正、折扣值不小于 100、每满减/满减门槛非正。
- 门槛按当前可抵扣金额判断；初始不满足门槛的券不会因为后续叠加而重新满足，因此直接排除。
- 单券初始优惠额为 0 的券不进入搜索。

### 3. 单券优惠计算（整数分）

- 满减（4）：`min(discountValue, maxDiscountAmount, applicableAmount)`。
- 无门槛（3）：门槛为 0，抵扣 `discountValue`，再受 `maxDiscountAmount` 和可抵扣金额限制。
- 每满减（1）：`floor(applicableAmount / thresholdAmount) * discountValue`，再受最高优惠和可抵扣金额限制。
- 折扣（2）：`floor(applicableAmount * (100 - discountValue) / 100)`，再受最高优惠和可抵扣金额限制。
- 所有结果都向下取整到分，且不会超过当前可抵扣金额或剩余订单金额。

### 4. 组合与顺序

- 先按单券初始优惠额降序、用户券 id 升序保留最多 20 个候选。
- 枚举 1 至 3 张券的所有组合，并对每个组合枚举所有使用顺序。
- 顺序应用：每张券以“前面券抵扣后的剩余金额”为基数，重新计算范围金额、门槛和优惠。
- 抵扣额按命中课程的剩余金额比例分摊，取整后的余数按课程顺序补足，保证后续范围计算一致且不超扣。
- 同一 `userCouponId` 在一次方案中最多出现一次；同一模板的不同用户券视为不同券，可分别使用。
- 选择总优惠额最大的方案；并列时选择用券更少的方案；再并列时按核销顺序比较用户券 id，较小者优先。

### 5. 规模边界

| 边界 | 值 | 说明 |
| --- | --- | --- |
| 单次请求课程数 | 10 | 与交易 `PlaceOrderDTO` 一致 |
| 扫描用户券记录 | 200 | `LIMIT 200`，按用户券 id 升序 |
| 参与组合的候选券 | 20 | 按单券优惠额和 id 确定性截取 |
| 单方案用券数 | 3 | 不进行无界排列 |
| 组合×顺序上限 | 7241 | `C(20,1)*1! + C(20,2)*2! + C(20,3)*3!` |

### 6. 结果

返回总优惠、应付金额、按核销顺序排列的用户券 id 和模板 id，以及每张券的适用范围、门槛、优惠值、
最高优惠、参与课程、可抵扣金额、实际抵扣金额和可解释规则说明。

## 已知限制

- 推荐不检查优惠券模板的发放状态是否仍为“发放中”；用户已合法领取的券在其有效期内仍可推荐。
- 推荐不锁定用户券，也不处理并发核销；实际下单时仍需由交易/促销流程做最终校验和核销。
- 候选截断到 20 个，极端账户下可能不是全局最优；这是明确的有界近似，结果仍完全确定。
- 推荐接口尚未接入交易下单流程；交易侧可将返回的 `userCouponIds` 作为 `couponIds` 使用，但本次不修改交易模块。
- 课程信息依赖 `CourseClient`，课程服务不可用时会返回调用错误，而不是使用缓存或猜测价格。

## 验证

```bash
mvn -s C:/Users/your-user/.m2/settings.xml -pl tj-promotion -am \
  -Dtest=CouponRecommendationServiceTest,CouponRecommendationControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

推荐测试覆盖服务算法、接口校验和金额溢出回归；整体回归同时包含原有领取、兑换、发放与兑换码生成测试。
2026-10-30 本地验证：推荐相关 18 项通过（服务 14 项、接口 4 项）；促销模块全量 168 项通过。
