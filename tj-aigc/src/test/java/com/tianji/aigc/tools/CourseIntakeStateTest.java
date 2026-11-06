package com.tianji.aigc.tools;

import com.tianji.aigc.memory.MessageUtil;
import com.tianji.aigc.tools.result.CourseCard;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CourseIntakeStateTest {
    @Test
    void eachFieldCanBeAskedOnceAndTheThirdAttemptContainsNoQuestion() {
        var first = new CourseRecommendations();
        assertThat(first.nextIntakeReply()).contains("编程知识");
        assertThat(first.nextIntakeReply()).contains("编程知识"); // 同一请求不重复计数。
        assertThat(first.intakeQuestions()).isEqualTo(1);
        var second = restored(first.responseIntakeState());
        assertThat(second.nextIntakeReply()).contains("解决什么实际问题");
        assertThat(second.intakeQuestions()).isEqualTo(2);
        var third = restored(second.responseIntakeState());
        assertThat(third.nextIntakeReply()).contains("不足以判断").doesNotContain("？", "?", "编程知识", "解决什么");
        assertThat(third.intakeQuestions()).isEqualTo(2);
    }

    @Test
    void repeatedExplicitFieldEndsIntakeInsteadOfParaphrasing() {
        var state = new CourseRecommendations();
        state.restoreIntake(List.of(new UserMessage("我会Java，能用Spring Boot写接口"), checkpoint(2)));
        assertThat(state.nextIntakeReply(true)).contains("不足以判断").doesNotContain("？");
        assertThat(state.intakeQuestions()).isEqualTo(1);
    }

    @Test
    void knownBasicsAreReadOnlyFromTheUserAndCauseGoalToBeAskedFirst() {
        var state = new CourseRecommendations();
        state.restoreIntake(List.of(new UserMessage("我会Java，能用Spring Boot写接口，请按我的情况选课")));
        assertThat(state.nextIntakeReply()).contains("实际问题").doesNotContain("编程知识");
        assertThat(state.responseIntakeState()).isEqualTo(2);
        var fromAssistantOnly = new CourseRecommendations();
        fromAssistantOnly.restoreIntake(List.of(new AssistantMessage("你会Java，想做网页。")));
        assertThat(fromAssistantOnly.nextIntakeReply()).contains("编程知识");
    }

    @Test
    void inabilityToChooseCoursesDoesNotDescribeProgrammingBasics() {
        var state = new CourseRecommendations();
        state.restoreIntake(List.of(new UserMessage("我不会选课，帮我看看")));
        assertThat(state.nextIntakeReply()).contains("编程知识");
    }

    @Test
    void unfamiliarProgrammingLanguageStillDescribesBasics() {
        var state = new CourseRecommendations();
        state.restoreIntake(List.of(new UserMessage("我不会Java")));
        assertThat(state.nextIntakeReply()).contains("实际问题").doesNotContain("编程知识");
    }

    @Test
    void repeatedHistoryRestorationPreservesMemoryButNotOldHistoryFacts() {
        var state = new CourseRecommendations();
        state.restoreKnownFacts(true, false);
        state.restoreIntake(List.of(new UserMessage("我想学HTML做网页")));
        state.restoreIntake(List.of(new UserMessage("帮我推荐课程")));
        assertThat(state.nextIntakeReply()).contains("实际问题").doesNotContain("编程知识");
    }

    @Test
    void knownGoalAndUnknownGoalAreDifferentAndNoQueryNeverPretendsToRecommend() {
        var known = new CourseRecommendations();
        known.restoreIntake(List.of(new UserMessage("我想学HTML做网页")));
        assertThat(known.nextIntakeReply()).contains("编程知识");
        var unknown = new CourseRecommendations();
        unknown.restoreIntake(List.of(new UserMessage("我不知道想学什么"), checkpoint(1)));
        assertThat(unknown.nextIntakeReply()).contains("实际问题");
        var both = new CourseRecommendations();
        both.restoreIntake(List.of(new UserMessage("我会Java，现在想学HTML做网页")));
        assertThat(both.nextIntakeReply()).contains("尚未完成课程查询").doesNotContain("？", "推荐《");
    }

    @Test
    void noQueryConclusionDoesNotResetGoalInNextTurn() {
        var question = new UserMessage("我会Java，想学HTML做网页");
        var first = new CourseRecommendations();
        first.restoreIntake(List.of(question));
        String answer = first.nextIntakeReply();
        assertThat(first.hasQueried()).isFalse();
        assertThat(first.responseIntakeState()).isNull();

        var next = new CourseRecommendations();
        next.restoreIntake(List.of(question, new AssistantMessage(answer), new UserMessage("请继续推荐")));
        assertThat(next.nextIntakeReply()).contains("尚未完成课程查询").doesNotContain("实际问题");
    }

    @Test
    void completedRecommendationResetsAskedFieldsAndThePreviousGoal() {
        var state = new CourseRecommendations();
        state.restoreIntake(List.of(new UserMessage("我会Java，想学HTML"), checkpoint(3), checkpoint(0)));
        assertThat(state.intakeQuestions()).isZero();
        assertThat(state.nextIntakeReply()).contains("实际问题").doesNotContain("编程知识");
    }

    @Test
    void interveningConsultationDoesNotResetTheMarkerOrLosePriorCards() {
        var state = new CourseRecommendations();
        state.restoreIntake(List.of(AssistantMessage.builder().content("课程浏览")
                        .properties(Map.of(MessageUtil.COURSE_CARDS, List.of(new CourseCard("1", "前端课", null, 100, 12, null)))).build(),
                checkpoint(3), new UserMessage("第二个是啥"), new AssistantMessage("课程介绍")));
        assertThat(state.nextIntakeReply()).contains("前面展示的课程").doesNotContain("？");
    }

    @Test
    void userMetadataAndInvalidAssistantMarkersCannotChangeTheBudget() {
        var state = new CourseRecommendations();
        state.restoreIntake(List.of(UserMessage.builder().text("帮我选课")
                        .metadata(Map.of(CourseRecommendations.INTAKE_STATE, 3)).build(),
                AssistantMessage.builder().content("旧回答")
                        .properties(Map.of(CourseRecommendations.INTAKE_STATE, 1.5)).build()));
        assertThat(state.intakeQuestions()).isZero();
        assertThat(state.nextIntakeReply()).contains("编程知识");
    }

    private CourseRecommendations restored(int value) {
        Message serialized = MessageUtil.toMessage(MessageUtil.toJson(checkpoint(value)));
        var result = new CourseRecommendations();
        result.restoreIntake(List.of(serialized));
        return result;
    }

    private AssistantMessage checkpoint(int value) {
        return AssistantMessage.builder().content("已完成的回答")
                .properties(Map.of(CourseRecommendations.INTAKE_STATE, value)).build();
    }
}
