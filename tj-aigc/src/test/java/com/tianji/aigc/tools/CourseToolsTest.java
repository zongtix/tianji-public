package com.tianji.aigc.tools;

import com.tianji.aigc.tools.result.CourseInfo;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CourseBaseInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.api.dto.course.CoursePopularityDTO;
import com.tianji.api.client.search.SearchClient;
import com.tianji.aigc.knowledge.course.CourseSemanticSearch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CourseToolsTest {
    private final CourseClient client = mock(CourseClient.class);
    private final CourseTools tools = new CourseTools(client, mock(com.tianji.api.client.search.SearchClient.class));
    private final ToolContext context = new ToolContext(Map.of(CourseRecommendations.CONTEXT_KEY, new CourseRecommendations()));

    @Test
    void overviewOmitsCatalogueWithoutPretendingItIsMissingAndPreservesUnknownPrice() throws Exception {
        var detail = givenScopedCourse();
        detail.setPrice(null);
        var callback = java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(tool -> tool.getToolDefinition().name().equals("queryCourseById")).findFirst().orElseThrow();
        var response = new ObjectMapper().readTree(callback.call("{\"courseId\":1}", scoped("第二个是啥")));

        assertThat(response.has("catalogue")).isFalse();
        assertThat(response.has("contentScope")).isFalse();
        assertThat(response.path("detail").asText()).contains("前端知识").doesNotContain("Vue", "目录", "实战", "深度");
        assertThat(response.path("usePeople").asText()).isEqualTo("有一定后端基础");
        assertThat(response.has("price")).isTrue();
        assertThat(response.get("price").isNull()).isTrue();
    }

    @Test
    void browsingGetsTwoRootTopicsAsPositiveEvidenceWithoutExpandingTheFullCatalogue() throws Exception {
        var detail = givenScopedCourse();
        detail.setCatalogue(List.of("Html与CSS", "Html与CSS / HTML元素", "JavaScript", "JavaScript / 变量", "Vue", "Vue / 入门"));
        var response = queryJson("第二个是啥");
        assertThat(response.path("overviewTopics").toString()).isEqualTo("[\"Html与CSS\",\"JavaScript\"]");
        assertThat(response.has("catalogue")).isFalse();
        assertThat(response.path("notExpanded").toString()).contains("catalogue");
        assertThat(detail.getCatalogue()).hasSize(6);
        var goal = queryJson("想学 HTML 和 CSS");
        assertThat(goal.has("overviewTopics")).isFalse();
        assertThat(goal.path("catalogue").toString()).doesNotContain("JavaScript", "Vue");
    }

    @Test
    void zeroTopicMatchesMarksOmittedFieldsAndPreservesAudience() throws Exception {
        var detail = givenScopedCourse();
        detail.setCatalogue(List.of("Html与CSS / HTML元素", "Html与CSS / CSS", "JavaScript / 变量"));
        String question = "Java程序员的前端课程适合什么基础的人？请查一下课程详情。";
        assertThat(CourseTools.DetailScope.forQuestion(question).topics()).isNotEmpty();

        var response = queryJson(question);

        assertThat(response.has("catalogue")).isFalse();
        assertThat(response.has("detail")).isFalse();
        assertThat(response.path("notExpanded").toString()).isEqualTo("[\"catalogue\",\"detail\"]");
        assertThat(response.has("partiallyExpanded")).isFalse();
        assertThat(response.path("usePeople").asText()).isEqualTo("有一定后端基础");
    }

    @Test
    void htmlGoalKeepsMatchingChildWhenParentAlsoMentionsCss() throws Exception {
        var detail = givenScopedCourse();
        detail.setCatalogue(List.of("Html与CSS", "Html与CSS / HTML元素", "Html与CSS / HTML页面",
                "Html与CSS / CSS", "JavaScript / 变量"));
        var response = queryJson("我会Java，想学HTML做网页，请继续推荐。");
        assertThat(response.path("catalogue").toString()).isEqualTo("[\"HTML元素\",\"HTML页面\"]");
        assertThat(response.path("partiallyExpanded").toString()).contains("catalogue");
        assertThat(response.path("notExpanded").toString()).doesNotContain("catalogue");
        assertThat(detail.getCatalogue().get(1)).isEqualTo("Html与CSS / HTML元素");
    }

    @Test
    void matchingExcerptIsMarkedPartialWithoutChangingOriginalFacts() throws Exception {
        var detail = givenScopedCourse();
        var response = queryJson("我想学HTML、CSS和JavaScript做网页");

        assertThat(response.path("catalogue")).hasSize(3);
        assertThat(response.path("detail").asText()).isEqualTo("学习 HTML、CSS 和 JavaScript");
        assertThat(response.path("partiallyExpanded").toString()).isEqualTo("[\"catalogue\",\"detail\"]");
        assertThat(response.has("notExpanded")).isFalse();
        assertThat(detail.getCatalogue()).hasSize(6);
        assertThat(detail.getDetail()).contains("Vue", "React", "jQuery");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void genuinelyMissingContentIsNotMarkedAsOmitted(boolean emptyInsteadOfNull) throws Exception {
        var detail = givenScopedCourse();
        detail.setCatalogue(emptyInsteadOfNull ? List.of() : null);
        detail.setDetail(emptyInsteadOfNull ? "" : null);

        var response = queryJson("第二个是啥");

        assertThat(response.has("catalogue")).isFalse();
        assertThat(response.has("notExpanded")).isFalse();
        assertThat(response.has("partiallyExpanded")).isFalse();
        assertThat(response.path("detail").asText()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"请用这个完整课程名作为搜索关键词。", "请用完整的课程名称搜索", "完整的课程名称是什么？"})
    void completeCourseNameDoesNotExpandUnrelatedTeachingContent(String question) throws Exception {
        givenScopedCourse();
        assertThat(CourseTools.DetailScope.forQuestion(question).fullContent()).isFalse();
        var response = queryJson(question);
        assertThat(response.path("detail").asText()).doesNotContain("Vue", "React", "jQuery");
    }

    private com.fasterxml.jackson.databind.JsonNode queryJson(String question) throws Exception {
        var callback = java.util.Arrays.stream(ToolCallbacks.from(tools))
                .filter(tool -> tool.getToolDefinition().name().equals("queryCourseById")).findFirst().orElseThrow();
        return new ObjectMapper().readTree(callback.call("{\"courseId\":1}", scoped(question)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"query", "browse", "semantic"})
    void allDetailEntrypointsProjectCurrentGoalWhileKeepingCompleteCardFacts(String entrypoint) {
        var detail = givenScopedCourse();
        var search = mock(SearchClient.class);
        var semantic = mock(CourseSemanticSearch.class);
        var scopedTools = new CourseTools(client, search, semantic);
        var context = scoped("我会Java，能用Spring Boot和MyBatis写后端接口，现在想学HTML、CSS和JavaScript做网页，请按我的情况推荐一门课程。");
        var popular = new CoursePopularityDTO(); popular.setId(1L); popular.setSold(0);
        when(search.queryPopularCourses(null, null)).thenReturn(List.of(popular));
        when(semantic.search(anyString())).thenReturn(List.of(1L));
        when(client.getBaseInfoBatch(List.of(1L))).thenReturn(List.of(detail));

        CourseInfo result = switch (entrypoint) {
            case "query" -> scopedTools.queryCourseById(1L, context);
            case "browse" -> scopedTools.browseCourses(null, null, context).courses().get(0).info();
            default -> scopedTools.searchCoursesByGoal("模型自行扩展为 Vue、React、jQuery", context).get(0).info();
        };

        assertThat(result.getCatalogue()).containsExactly("Html与CSS / HTML元素", "Html与CSS / CSS", "JavaScript / 变量");
        assertThat(result.getDetail()).isEqualTo("学习 HTML、CSS 和 JavaScript");
        assertThat(result.getUsePeople()).isEqualTo("有一定后端基础");
        assertThat(CourseRecommendations.from(context).detailFacts()).singleElement().satisfies(fact -> {
            assertThat(fact.usePeople()).isEqualTo(detail.getUsePeople());
            assertThat(fact.catalogue()).containsExactlyElementsOf(detail.getCatalogue());
        });
        assertThat(detail.getDetail()).contains("Vue", "React", "jQuery", "深度");
        assertThat(detail.getCatalogue()).contains("Vue / 入门", "Java / 基础");
        var recommendations = CourseRecommendations.from(context);
        if (!entrypoint.equals("query")) {
            scopedTools.recommendCourses(List.of(new CourseTools.Recommendation("1", "网页目标")), context);
        }
        assertThat(recommendations.snapshot()).singleElement().satisfies(card -> {
            assertThat(card.getCourseId()).isEqualTo("1");
            assertThat(card.getName()).isEqualTo("Java程序员的前端课程");
            assertThat(card.getCoverUrl()).isEqualTo("/real-cover.jpg");
            assertThat(card.getPriceInCents()).isEqualTo(14900);
            assertThat(card.getValidDuration()).isEqualTo(9999);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"是否教 Vue？", "有没有讲 React？", "JavaScript 讲多深？", "是否完整覆盖前端？", "请给完整目录", "教 HTML 吗？", "React讲不讲", "请给我完整的内容介绍", "我想完整学一遍前端，需要会什么？"})
    void explicitContentVerificationKeepsOriginalClaimsAndEntireCatalogue(String question) {
        var detail = givenScopedCourse();
        var result = tools.queryCourseById(1L, scoped(question));
        assertThat(result.getDetail()).isEqualTo(detail.getDetail());
        assertThat(result.getCatalogue()).containsExactlyElementsOf(detail.getCatalogue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"有哪些热门课程推荐？", "有没有课程推荐", "普通推荐", "随便推荐几门", "随便推荐两门", "我也不知道学什么", "这俩有啥区别", "这两门怎么选"})
    void generalBrowsingStillProvidesOverviewAndPrerequisites(String question) {
        givenScopedCourse();
        var result = tools.queryCourseById(1L, scoped(question));
        assertThat(result.getDetail()).contains("前端知识").doesNotContain("Vue", "React", "深度");
        assertThat(result.getUsePeople()).isEqualTo("有一定后端基础");
        assertThat(result.getCatalogue()).isNull();
    }

    @Test
    void targetedExcerptDoesNotCreateClaimsByDeletingUnrelatedWordsFromMixedSentences() {
        var detail = givenScopedCourse();
        detail.setDetail("HTML 与 Vue 均声称完整覆盖。HTML 和 Vue 的差别。HTML 页面元素。CSS 不包含在课程内。");
        var result = tools.queryCourseById(1L, scoped("想学 HTML 和 CSS"));
        assertThat(result.getDetail()).contains("HTML 页面元素", "CSS 不包含在课程内").doesNotContain("Vue", "完整覆盖", "差别");
    }

    @Test
    void chineseGoalKeepsMatchingCatalogueWithoutOpeningUnrelatedSections() {
        var detail = givenScopedCourse();
        detail.setCatalogue(List.of("认识微服务", "认识微服务 / SpringCloud", "Html与CSS"));
        var result = tools.queryCourseById(1L, scoped("想学微服务"));
        assertThat(result.getCatalogue()).containsExactly("认识微服务", "认识微服务 / SpringCloud");
    }

    private ToolContext scoped(String question) {
        return new ToolContext(Map.of(CourseRecommendations.CONTEXT_KEY, new CourseRecommendations(),
                CourseTools.DETAIL_SCOPE, CourseTools.DetailScope.forQuestion(question)));
    }

    private CourseBaseInfoDTO givenScopedCourse() {
        var detail = new CourseBaseInfoDTO();
        detail.setId(1L); detail.setName("Java程序员的前端课程"); detail.setPrice(14900); detail.setValidDuration(9999);
        detail.setUsePeople("有一定后端基础");
        detail.setDetail("<h3>课程定位与适用人群</h3><p>有后端基础，想快速学习前端知识的同学。</p>"
                + "<p>学习 HTML、CSS 和 JavaScript。</p>"
                + "<h3>当前正式目录</h3><ul><li>JavaScript / 变量</li></ul>"
                + "<h3>原介绍声明的其他内容</h3><p>原介绍涉及 Vue、React、jQuery，当前目录未列出对应章节。</p>"
                + "<h3>资料范围</h3><p>现有资料未说明讲解深度和完整实战项目。</p>");
        detail.setCatalogue(List.of("Html与CSS / HTML元素", "Html与CSS / CSS", "JavaScript / 变量", "Vue / 入门", "Java / 基础", "HTML与Vue / 对比"));
        when(client.getCourseBaseInfoById(1L)).thenReturn(detail);
        var simple = new CourseSimpleInfoDTO();
        simple.setId(1L); simple.setName(detail.getName()); simple.setStatus(2); simple.setPrice(14900);
        simple.setValidDuration(9999); simple.setCoverUrl("/real-cover.jpg");
        when(client.getSimpleInfoList(List.of(1L))).thenReturn(List.of(simple));
        return detail;
    }

    @Test
    void queriesNewEndpointAndConvertsContentAndPrice() {
        CourseBaseInfoDTO dto = new CourseBaseInfoDTO();
        dto.setId(1L);
        dto.setName("Java 入门");
        dto.setPrice(1999);
        dto.setValidDuration(12);
        dto.setUsePeople("初学者");
        dto.setDetail("Java 基础课程");
        dto.setCatalogue(List.of("认识微服务 / Spring Cloud"));
        when(client.getCourseBaseInfoById(1L)).thenReturn(dto);

        CourseInfo result = tools.queryCourseById(1L, context);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Java 入门");
        assertThat(result.getPrice()).isEqualTo(19.99d);
        assertThat(result.getValidDuration()).isEqualTo(12);
        assertThat(result.getUsePeople()).isEqualTo("初学者");
        assertThat(result.getDetail()).isEqualTo("Java 基础课程");
        assertThat(result.getCatalogue()).containsExactly("认识微服务 / Spring Cloud");
        verify(client).getCourseBaseInfoById(1L);
        verify(client).getSimpleInfoList(List.of(1L));
        verifyNoMoreInteractions(client);
    }

    @Test
    void nullIdDoesNotCallCourseService() {
        assertThat(tools.queryCourseById(null, context)).isNull();
        verifyNoInteractions(client);
    }

    @Test
    void missingResultReturnsNull() {
        assertThat(tools.queryCourseById(1L, context)).isNull();
        assertThat(CourseRecommendations.from(context).hasQueried()).isTrue();
    }

    @Test
    void invalidArgumentsAndEmptySelectionsDoNotCountAsCourseQueries() {
        assertThat(tools.queryCourseById(0L, context)).isNull();
        assertThat(tools.searchCourses(" ", context)).isEmpty();
        assertThat(tools.searchCoursesByGoal(" ", context)).isEmpty();
        assertThat(tools.recommendCourses(List.of(), context)).isEmpty();
        assertThat(CourseRecommendations.from(context).hasQueried()).isFalse();
        verifyNoInteractions(client);
    }

    @Test
    void nullPriceAndContentRemainSupported() {
        CourseInfo result = CourseInfo.of(new CourseBaseInfoDTO());
        assertThat(result.getPrice()).isNull();
        assertThat(result.getUsePeople()).isNull();
        assertThat(result.getDetail()).isNull();
        assertThat(CourseInfo.of(null)).isNull();
    }

    @Test
    void freeFlagTakesPrecedenceOverMissingOrStalePrice() {
        var dto = new CourseBaseInfoDTO();
        dto.setFree(true);
        assertThat(CourseInfo.of(dto).getPrice()).isZero();
        dto.setPrice(1999);
        assertThat(CourseInfo.of(dto).getPrice()).isZero();
    }

    @Test
    void invalidPriceIsUnknownWhileExplicitZeroRemainsZero() {
        var dto = new CourseBaseInfoDTO();
        dto.setFree(false);
        dto.setPrice(-1);
        assertThat(CourseInfo.of(dto).getPrice()).isNull();
        dto.setPrice(0);
        assertThat(CourseInfo.of(dto).getPrice()).isZero();
    }
}
