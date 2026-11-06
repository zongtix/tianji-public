# 本人优惠券分页

## 接口

`GET /user-coupons/page`，网关路径为 `GET /prs/user-coupons/page`，必须登录。
该接口补齐个人中心原有调用，不依赖 `tj.promotion.async.enabled`，不改变同步/异步领取、兑换或核销事务。

| 参数 | 默认值 | 约束 |
| --- | --- | --- |
| status | 1 | 1 未使用、2 已使用、3 已失效；显式为空或其他值返回 400 |
| pageNo | 1 | 正整数 |
| pageSize | 20 | 1～1000；兼容当前页面传入 1000，统一 MyBatis 分页仍最多返回 200 条 |

用户只取服务端 `UserContext`，请求中的 `userId` 不会覆盖身份。
固定按用户券 `create_time DESC, id DESC` 排序，不使用继承参数中的动态 `sortBy/isAsc`。
不增加“近一年”时间过滤，以免隐藏仍在有效期内的历史券。

响应沿用 `PageDTO` 的 `total/pages/list`；网关调用再由公共层包装为 `code/msg/data`。
空页返回空数组，超出最后一页仍保留正确总条数、总页数；大整数 ID 沿用应用现有字符串序列化。

## 状态与时间

- 未使用：记录状态为 UNUSED，且用户券 `term_end_time > 当前时间`。
- 已使用：记录状态为 USED，即使当前已超过原有效期，仍留在已使用栏。
- 已失效：显式 EXPIRED，或仍标 UNUSED 但用户券有效期已经结束。
- 过期判断在只读查询时完成，不写回数据库、不批量改变历史记录状态。
- 尚未到使用开始时间的有效 UNUSED 券仍属于未使用；不表示它已可立即核销，核销/推荐仍须自行检查开始时间。
- 不按优惠券模板当前发放状态过滤，已合法领取的券不会因模板结束发放或暂停而消失。

## 字段来源

| 字段 | 来源 |
| --- | --- |
| id | `user_coupon.id`，不是优惠券模板 ID |
| couponId | `user_coupon.coupon_id` |
| termBeginTime / termEndTime | 用户券实际有效期，不复制模板有效期或重新计算相对天数 |
| status | 用户券记录状态，加上本次只读过期判断 |
| usedTime / createTime | 用户券使用/领取时间 |
| name / specific | 对应优惠券模板 |
| discountType / thresholdAmount / discountValue / maxDiscountAmount | 对应优惠券模板规则，金额单位仍为分 |

不返回其他用户信息、兑换码、模板库存或发放状态；不返回模板 `termDays`，使现有卡片显示用户券实际截止日期。
每页先分页读取本人用户券，再一次批量读取涉及的模板，避免逐张查询。
若模板已缺失，保留用户券行和分页总数，名称提示“优惠券信息不可用”，不伪造折扣规则。

## 验证与限制

Java 17 下新增服务测试 16 项、Controller 测试 7 项；连同既有同步 Controller 测试聚焦 31 项通过。
重新执行促销模块全量测试 191 项通过，0 失败、0 错误、0 跳过，并成功重新打包。
覆盖用户隔离、三种状态、自然过期、已用券过期、未来生效券、稳定排序、空页、单页上限、用户券/模板字段区分、
模板缺失和批量读取、非法参数、匿名拒绝、查询不回写及继承排序注入。

```powershell
$env:JAVA_HOME='D:\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -s C:\Users\your-user\.m2\settings.xml -o -pl tj-promotion -am '-Dtest=UserCouponPageQueryTest,UserCouponPageControllerTest,UserCouponControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

当前前端仍只请求第一页，超过单页 200 条时需另行增加前端翻页；本次不扩展页面分页交互、结算或交易核销。
