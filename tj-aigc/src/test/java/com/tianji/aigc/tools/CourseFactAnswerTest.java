package com.tianji.aigc.tools;

import com.tianji.api.dto.course.CourseBaseInfoDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CourseFactAnswerTest {
    private final CourseRecommendations state = new CourseRecommendations();

    @Test
    void demoFactAnswerExplainsItsNature() {
        var detail = new CourseBaseInfoDTO();
        detail.setId(1L);
        detail.setName("Java入门");
        detail.setDetail("AI联调演示数据；不提供真实授课。");
        detail.setUsePeople("Java初学者");
        state.rememberDetail(detail);
        assertThat(CourseFactAnswer.answer("Java入门适用人群是什么？", state))
                .contains("演示数据", "勿将其视为真实授课或付款依据");
    }

    @ParameterizedTest
    @ValueSource(strings = {"微服务课适用人群是什么？", "微服务课适合什么基础的人？", "学微服务课需要什么基础？", "微服务课的前置条件是什么？"})
    void audiencePreservesEveryConditionAndProficiencyWithoutUnaskedDimensions(String question) {
        remember(1L, "微服务课", "有一定的Java开发基础，熟练使用了SpringBoot、MyBatis等基础框架", List.of("架构演变"));

        String answer = CourseFactAnswer.answer(question, state);

        assertThat(answer).contains("有一定的Java开发基础，熟练使用了SpringBoot、MyBatis等基础框架")
                .doesNotContain("149", "9999", "目录", "深度", "只要", "都适合");
    }

    @Test
    void comparisonIncludesOnlyTheNamedCoursesInQuestionOrder() {
        remember(3L, "无关课程", "无关基础", List.of());
        remember(2L, "微服务课", "Java基础，熟练使用SpringBoot、MyBatis", List.of());
        remember(1L, "前端课", "有一定后端基础", List.of());

        String answer = CourseFactAnswer.answer("前端课和微服务课的适用人群有什么区别？", state);

        assertThat(answer).contains("有一定后端基础", "Java基础，熟练使用SpringBoot、MyBatis")
                .doesNotContain("无关课程", "无关基础", "如果", "都适合");
        assertThat(answer.indexOf("前端课")).isLessThan(answer.indexOf("微服务课"));
    }

    @Test
    void partialComparisonDoesNotInventTheMissingCourse() {
        remember(1L, "前端课", "有一定后端基础", List.of());
        assertThat(CourseFactAnswer.answer("前端课和微服务课的适用人群有什么区别？", state))
                .contains("有一定后端基础", "尚不足以比较").doesNotContain("《微服务课》");
    }

    @Test
    void depthQuotesRelevantTitlesWithoutDerivingTeachingLevel() {
        remember(1L, "Java程序员的前端课程", "有一定后端基础", List.of(
                "Html与CSS", "JavaScript / 快速入门", "JavaScript / 变量", "JavaScript / 控制语句", "Vue / 入门"));

        String answer = CourseFactAnswer.answer("Java程序员的前端课程中，JavaScript讲多深？能确认是完整覆盖的吗？", state);

        assertThat(answer).contains("JavaScript / 快速入门", "JavaScript / 变量", "无法确认讲解深度", "无法确认是否完整覆盖")
                .doesNotContain("覆盖范围有限", "仅限基础", "更偏向", "Vue", "Html与CSS", "MyBatis", "149");
    }

    @Test
    void absentTopicIsNotEvidenceThatTheCourseDoesNotTeachIt() {
        remember(1L, "前端课", null, List.of("HTML / 元素"));
        assertThat(CourseFactAnswer.answer("前端课中JavaScript讲多深？", state))
                .contains("未匹配到", "不足以判断课程是否教授", "无法确认讲解深度")
                .doesNotContain("不教授JavaScript", "只教HTML");
    }

    @Test
    void missingAudienceAndCatalogueStayUnknown() {
        remember(1L, "前端课", null, null);
        assertThat(CourseFactAnswer.answer("前端课适用人群是什么？", state)).contains("未提供适用人群", "无法确认前置要求");
        assertThat(CourseFactAnswer.answer("前端课讲多深？", state)).contains("本次未取得可核对的目录标题", "无法确认讲解深度");
    }

    @Test
    void queryStateAndClosedStateNeverReuseOldFacts() {
        assertThat(CourseFactAnswer.answer("前端课需要什么基础？", state)).contains("尚未查询").doesNotContain("没有匹配");
        state.markQueried();
        assertThat(CourseFactAnswer.answer("前端课需要什么基础？", state)).contains("本次查询未取得");
        remember(1L, "前端课", "旧条件", List.of());
        state.close();
        assertThat(CourseFactAnswer.answer("前端课需要什么基础？", state)).doesNotContain("旧条件");
    }

    @Test
    void capturedFactsAreIndependentOfLaterDtoMutation() {
        var catalogue = new ArrayList<>(List.of("JavaScript / 变量"));
        var dto = remember(1L, "前端课", "有后端基础", catalogue);
        dto.setUsePeople("修改后的条件");
        catalogue.add("Vue / 入门");
        assertThat(CourseFactAnswer.answer("前端课适用人群是什么？", state)).contains("有后端基础").doesNotContain("修改后的条件");
        assertThat(state.detailFacts().get(0).catalogue()).containsExactly("JavaScript / 变量");
    }

    @ParameterizedTest
    @ValueSource(strings = {"前端课多少钱？", "请解释JavaScript闭包", "前端课适用人群和有效期是什么？", "前端课需要什么基础，请再推荐一门", "前端课适合我吗？", "前端课适用人群和目录是什么？", "前端课适用人群和讲师是谁？"})
    void unsupportedAndMixedQuestionsKeepTheOriginalAnswerPath(String question) {
        remember(1L, "前端课", "有后端基础", List.of());
        assertThat(CourseFactAnswer.answer(question, state)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"前端课和微服务课有什么区别，我该选哪门？", "前端课和微服务课有什么区别，哪门更贴合我现在的目标？", "前端课和微服务课适用人群有什么区别，哪门更适合我？", "介绍一下前端课，学完有什么收获？", "推荐前端课，学完有什么收获？"})
    void mixedQuestionsDoNotShortCircuitUnansweredNeeds(String question) {
        remember(1L, "前端课", "有后端基础", List.of());
        remember(2L, "微服务课", "有Java基础", List.of());
        if (question.startsWith("推荐")) {
            var card = new com.tianji.aigc.tools.result.CourseCard("1", "前端课", null, 14900, 12, null);
            state.addCandidates(List.of(card)); state.select(List.of(card));
        }
        assertThat(CourseFactAnswer.answer(question, state)).isNull();
    }

    @Test
    void courseNameWordsDoNotOverrideTheQuestionIntent() {
        remember(1L, "推荐系统课程", "熟练使用Java", List.of());
        assertThat(CourseFactAnswer.answer("推荐系统课程的适用人群是什么？", state)).contains("熟练使用Java");
    }

    @Test
    void introductionAndComparisonPreservePrerequisitesWithoutUnaskedMissingFieldNotes() {
        var front = remember(1L, "前端课", "有一定后端基础", List.of("Html与CSS", "JavaScript"));
        front.setDetail("<h3>课程定位</h3><p>帮助后端开发者了解网页界面开发。</p>"
                + "<h3>前置基础</h3><p>原资料未细化具体语言、框架或工作年限要求。</p>");
        state.rememberDetail(front);
        String introduction = CourseFactAnswer.answer("第二个是啥", state);
        assertThat(introduction).contains("前端课", "网页界面", "有一定后端基础")
                .doesNotContain("未细化", "工作年限", "149", "9999");
        var micro = remember(2L, "微服务课", "有Java基础，熟练使用SpringBoot、MyBatis", List.of("架构演变"));
        micro.setDetail("<h3>课程内容</h3><p>介绍微服务架构演变与SpringCloud。</p>"
                + "<h3>资料范围</h3><p>暂不确定后续章节与项目深度。</p>");
        state.rememberDetail(micro);
        assertThat(CourseFactAnswer.answer("这俩有啥区别", state))
                .contains("前端课", "微服务课", front.getUsePeople(), micro.getUsePeople())
                .doesNotContain("熟悉", "未细化", "后续章节", "项目深度");
    }

    @Test
    void personalizedRecommendationQuotesTheUsersBackgroundAndRelevantRootTitles() {
        remember(1L, "前端课", "有一定后端基础，想学习前端的同学", List.of(
                "Html与CSS", "Html与CSS / HTML元素", "JavaScript", "JavaScript / 变量", "Vue / 入门"));
        var card = new com.tianji.aigc.tools.result.CourseCard("1", "前端课", null, 14900, 12, null);
        state.addCandidates(List.of(card)); state.select(List.of(card));
        String question = "我会Java，能用Spring Boot和MyBatis写接口，现在想学HTML、CSS和JavaScript做网页，请按我的情况推荐一门课。";
        assertThat(CourseFactAnswer.answer(question, state))
                .contains("我会Java", "Spring Boot", "MyBatis", "推荐《前端课》", "有一定后端基础", "Html与CSS、JavaScript")
                .doesNotContain("Vue", "HTML元素", "变量", "全部满足", "149", "9999");
    }

    @Test
    void popularityNeverBorrowsEnrollmentFromAnExcludedCandidate() {
        remember(1L, "未选中的课", "Java基础", List.of());
        remember(2L, "已选中的课", "后端基础", List.of());
        var first = new com.tianji.aigc.tools.result.CourseCard("1", "未选中的课", null, 100, 12, null);
        var second = new com.tianji.aigc.tools.result.CourseCard("2", "已选中的课", null, 100, 12, null);
        state.addCandidates(List.of(first, second));
        state.rememberEnrollment("1", 100); state.rememberEnrollment("2", 0);
        state.select(List.of(second));
        assertThat(CourseFactAnswer.answer("有哪些热门课程推荐？", state))
                .contains("暂缺可靠热度依据", "仅供浏览", "已选中的课")
                .doesNotContain("未选中的课", "平台没有", "报名数据不存在", "热门榜");
    }

    @Test
    void lectureEvidenceQuestionsAreNotAnsweredFromCatalogueOnly() {
        remember(910000000000010005L, "【AI演示】Redis 缓存与热点治理", "有缓存基础",
                List.of("缓存击穿", "互斥锁"));
        assertThat(CourseFactAnswer.answer(
                "【AI演示】Redis 缓存与热点治理讲义里怎样解释缓存击穿？", state))
                .isNull();
    }

    @Test
    void mixedPriceAndLectureReturnsNullForFactShortcut() {
        remember(910000000000010005L, "【AI演示】Redis 缓存与热点治理", "有缓存基础",
                List.of("互斥锁示例"));
        assertThat(CourseFactAnswer.answer(
                "这门课多少钱？讲义里互斥锁例子为什么这样写？", state))
                .isNull();
    }

    private CourseBaseInfoDTO remember(long id, String name, String audience, List<String> catalogue) {
        var detail = new CourseBaseInfoDTO();
        detail.setId(id); detail.setName(name); detail.setUsePeople(audience); detail.setCatalogue(catalogue);
        detail.setPrice(14900); detail.setValidDuration(9999);
        state.rememberDetail(detail);
        return detail;
    }
}
