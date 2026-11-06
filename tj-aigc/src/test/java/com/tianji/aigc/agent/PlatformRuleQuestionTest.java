package com.tianji.aigc.agent;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformRuleQuestionTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "签到得多少积分",
            "签到几分",
            "连续签到第七天多少分",
            "积分规则是什么",
            "学习积分上限多少",
            "优惠券使用规则",
            "优惠券怎么用",
            "优惠券领取规则",
            "兑换码怎么用",
            "买课程的优惠券怎么用",
            "课程优惠券有哪些使用规则",
            "学完课程得到的积分有多少",
            "推荐规则里提到的优惠券怎么领取"
    })
    void matchesClearPlatformRuleIntents(String question) {
        assertThat(PlatformRuleQuestion.matches(question)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "你好",
            "今天天气怎么样",
            "随便聊聊",
            "微服务课适用人群是什么？",
            "Java程序员的前端课程适合什么基础的人？",
            "有哪些热门课程推荐",
            "请解释JavaScript闭包",
            "推荐一门讲优惠券领取的课程",
            "有没有讲签到功能实现的课程",
            "有哪些课程讲积分规则实现",
            "想找一门实现兑换码的课",
            "签到系统课程推荐",
            "哪门课会讲优惠券领取",
            "搜索讲学习积分设计的课程",
            "有没有讲 签到 功能实现的\n课程",
            "这门课需要签到吗",
            "第二门课有签到环节吗",
            "讲义里的兑换码在哪一页"
    })
    void rejectsChitchatAndCourseQuestions(String question) {
        assertThat(PlatformRuleQuestion.matches(question)).isFalse();
    }
}
