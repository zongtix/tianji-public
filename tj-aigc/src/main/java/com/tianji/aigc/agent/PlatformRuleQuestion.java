package com.tianji.aigc.agent;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.regex.Pattern;

/** 明确的平台积分/签到/优惠券规则问句直接进入知识分支。 */
public final class PlatformRuleQuestion {
    // 签到/兑换码不再作无条件裸词：须与规则意图共现，避免抢走课程指代或讲义定位问句。
    private static final Pattern POINTS = Pattern.compile(
            "学习积分|积分规则|连续签到|"
            + "签到.{0,20}(?:积分|几分|多少|规则|怎么|如何|得|获得|入账)|"
            + "(?:积分|规则|多少|上限|怎么|如何|几分).{0,12}签到|"
            + "积分.{0,16}(?:规则|多少|上限|怎么|如何|得|获得|入账|几分)|"
            + "(?:规则|多少|上限|怎么|如何|几分).{0,8}积分");
    private static final Pattern COUPONS = Pattern.compile(
            "优惠券规则|"
            + "兑换码.{0,20}(?:规则|使用|怎么用|如何用|怎么|如何|领取|有效期|兑换|限领)|"
            + "(?:规则|使用|怎么用|如何用|怎么|如何|领取|有效期|兑换|限领).{0,12}兑换码|"
            + "优惠券.{0,16}(?:规则|使用|怎么用|如何用|领取|有效期|兑换|限领)|"
            + "(?:规则|使用|怎么用|如何用|领取|有效期|兑换|限领).{0,12}优惠券");

    // 明确找课时由原路由判断推荐/咨询，避免课程主题中的规则词抢占路由。
    private static final Pattern COURSE_DISCOVERY = Pattern.compile(
            "(?:推荐|找|搜索|有没有|有哪些|有什么|是否有).*(?:课|教程)|"
            + "(?:课程|课|教程)推荐|(?:哪(?:些|一)?门|什么|哪些)(?:课|教程)");

    // 明确课程/讲义指代（含序号）时，即使夹带签到/兑换码也不走平台规则捷径。
    private static final Pattern COURSE_REFERENCE = Pattern.compile(
            "(?:这门|该|这节|那门).{0,4}课|第[一二三123](?:个|门)(?:课|课程)?"
            + "|讲义里|讲义中|讲义的|课程里的|课程中的");

    private PlatformRuleQuestion() { }

    public static boolean matches(String question) {
        if (!StringUtils.hasText(question)) return false;
        String text = question.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (COURSE_DISCOVERY.matcher(text).find()) return false;
        if (COURSE_REFERENCE.matcher(text).find()) return false;
        return POINTS.matcher(text).find() || COUPONS.matcher(text).find();
    }
}
