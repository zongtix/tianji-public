package com.tianji.aigc.agent;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.config.AIProperties;
import com.tianji.aigc.config.SpringAIConfig;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.memory.MessageUtil;
import com.tianji.aigc.tools.CourseRecommendations;
import com.tianji.aigc.tools.CourseTools;
import com.tianji.aigc.tools.result.CourseCard;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.dto.course.CourseBaseInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 复用真实路由、工具、记忆和元数据链；模型故意提出旧的错误措辞。 */
class CourseDeliveryClosureTest {
    private static final String SESSION = "delivery-test";
    private final ChatModel router = mock(ChatModel.class);
    private final ChatModel model = mock(ChatModel.class);
    private final CourseClient courses = mock(CourseClient.class);
    private final SearchClient search = mock(SearchClient.class);
    private final ChatMemory memory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository()).build();
    private AgentDispatcher dispatcher;

    @BeforeEach
    void setup() {
        when(model.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        var config = new SpringAIConfig();
        var client = config.chatClient(ChatClient.builder(model), config.loggerAdvisor(),
                config.messageChatMemoryAdvisor(memory), model);
        var prompts = new SystemPromptConfig(mock(NacosConfigManager.class), new AIProperties());
        prompts.init();
        var tools = new CourseTools(courses, search);
        dispatcher = new AgentDispatcher(List.of(new RouteAgent(router, prompts), new RecommendAgent(client, prompts, tools),
                new ConsultAgent(client, prompts, tools), new KnowledgeAgent(client, prompts)), memory);
    }

    @Test
    void originalThirdToSeventhTurnEndsIntakeAndStillRecommendsAfterTheUserSuppliesInformation() throws Exception {
        var detail = new CourseBaseInfoDTO();
        detail.setId(2L); detail.setName("前端课");
        detail.setUsePeople("有一定后端基础，想学习前端知识的同学");
        detail.setDetail("<h3>课程定位</h3><p>帮助后端开发者了解网页界面开发。</p>"
                + "<h3>前置基础</h3><p>原资料未细化具体语言、框架或工作年限要求。</p>");
        detail.setCatalogue(List.of("Html与CSS", "Html与CSS / HTML元素", "JavaScript", "Vue / 入门"));
        var simple = new CourseSimpleInfoDTO();
        simple.setId(2L); simple.setName("前端课"); simple.setStatus(2); simple.setPrice(14900); simple.setValidDuration(12);
        when(courses.getCourseBaseInfoById(2L)).thenReturn(detail);
        when(courses.getSimpleInfoList(List.of(2L))).thenReturn(List.of(simple));
        when(search.queryCoursesIdByName("前端")).thenReturn(List.of(2L));
        memory.add(SESSION, List.of(new UserMessage("有哪些热门课程推荐？"),
                AssistantMessage.builder().content("你想补前端，还是了解微服务？")
                        .properties(Map.of(MessageUtil.COURSE_CARDS, List.of(
                                new CourseCard("1", "微服务课", null, 19900, 12, null),
                                new CourseCard("2", "前端课", null, 14900, 12, null)))).build()));
        when(router.stream(any(Prompt.class))).thenAnswer(call -> {
            String question = new ObjectMapper().readTree(call.getArgument(0, Prompt.class).getUserMessage().getText())
                    .path("currentQuestion").asText();
            return Flux.just(text(switch (question) {
                case "我也不知道", "我不需要热门课程，我需要你给我量身定制课程" ->
                        "{\"agent\":null,\"clarification\":\"你希望学完后做什么？\",\"clarificationType\":\"COURSE_INTAKE\"}";
                case "第二个是啥" -> "{\"agent\":\"CONSULT\",\"clarification\":\"\"}";
                default -> "{\"agent\":\"RECOMMEND\",\"clarification\":\"\"}";
            }));
        });
        var calls = new HashMap<String, Integer>();
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            String question = call.getArgument(0, Prompt.class).getInstructions().stream()
                    .filter(UserMessage.class::isInstance).reduce((a, b) -> b).orElseThrow().getText();
            int round = calls.merge(question, 1, Integer::sum);
            if (question.equals("第二个是啥")) return round == 1 ? tool("queryCourseById", "{\"courseId\":2}") : text("准备完成");
            if (question.equals("有没有课程推荐")) return text("准备完成");
            return switch (round) {
                case 1 -> tool("searchCourses", "{\"keyword\":\"前端\"}");
                case 2 -> tool("queryCourseById", "{\"courseId\":2}");
                default -> tool("recommendCourses", "{\"selections\":[{\"courseId\":\"2\",\"reason\":\"零基础都适合，目录未展开，去详情页核对\"}]}");
            };
        });

        assertThat(talk("我也不知道")).contains("编程知识");
        assertThat(last().getMetadata()).containsEntry(CourseRecommendations.INTAKE_STATE, 1);
        roundTripMemory();
        assertThat(talk("第二个是啥")).contains("前端课", detail.getUsePeople()).doesNotContain("未细化", "工作年限");
        assertThat(last().getMetadata()).doesNotContainKey(CourseRecommendations.INTAKE_STATE);
        roundTripMemory();
        assertThat(talk("有没有课程推荐")).contains("解决什么实际问题").doesNotContain("编程知识");
        assertThat(last().getMetadata()).containsEntry(CourseRecommendations.INTAKE_STATE, 3);
        roundTripMemory();
        assertThat(talk("我不需要热门课程，我需要你给我量身定制课程"))
                .contains("不足以判断", "前面展示的课程").doesNotContain("？", "?", "你希望", "编程知识");
        roundTripMemory();
        String question = "我会Java，能用Spring Boot和MyBatis写后端接口，现在想学HTML、CSS和JavaScript做网页，请按我的情况推荐一门课程。";
        assertThat(talk(question)).contains("推荐《前端课》", "我会Java", "Spring Boot", "MyBatis", "Html与CSS", "JavaScript", detail.getUsePeople())
                .doesNotContain("Vue", "未细化", "工作年限", "零基础都适合", "去详情页核对");
        assertThat(last().getMetadata()).containsEntry(CourseRecommendations.INTAKE_STATE, 0);
        String cards = new ObjectMapper().writeValueAsString(last().getMetadata().get(MessageUtil.COURSE_CARDS));
        assertThat(cards).contains(detail.getUsePeople()).doesNotContain("零基础都适合", "未展开");
        verify(model, never()).stream(any(Prompt.class));
        verify(courses, times(3)).getCourseBaseInfoById(2L);
        verify(router, times(5)).stream(any(Prompt.class));
    }

    @Test
    void referenceClarificationDoesNotConsumePersonalIntake() {
        memory.add(SESSION, List.of(AssistantMessage.builder().content("第一次问题")
                .properties(Map.of(CourseRecommendations.INTAKE_STATE, 1)).build()));
        when(router.stream(any(Prompt.class))).thenReturn(Flux.just(text(
                "{\"agent\":null,\"clarification\":\"你指的是哪门课程？\",\"clarificationType\":\"COURSE_REFERENCE\"}")));
        assertThat(talk("这门怎么样")).isEqualTo("你指的是哪门课程？");
        assertThat(last().getMetadata()).doesNotContainKey(CourseRecommendations.INTAKE_STATE);
        verify(model, never()).call(any(Prompt.class));
        verify(model, never()).stream(any(Prompt.class));
        verifyNoInteractions(courses, search);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void missingSecondCardIsClarifiedBeforeRoutingAndNeverInventsAReference(int available) {
        var cards = available == 0 ? List.<CourseCard>of() : List.of(new CourseCard("1", "唯一课程", null, 100, 12, null));
        memory.add(SESSION, List.of(AssistantMessage.builder().content("已有上下文")
                .properties(Map.of(MessageUtil.COURSE_CARDS, cards, CourseRecommendations.INTAKE_STATE, 3)).build()));
        String answer = talk("第二个是啥");
        assertThat(answer).contains("课程名称").doesNotContain("《唯一课程》", "编程知识", "你希望");
        assertThat(last().getMetadata()).doesNotContainKey(CourseRecommendations.INTAKE_STATE);
        var restored = new CourseRecommendations();
        restored.restoreIntake(memory.get(SESSION));
        assertThat(restored.intakeQuestions()).isEqualTo(2);
        verifyNoInteractions(router, courses, search);
        verify(model, never()).call(any(Prompt.class));
        verify(model, never()).stream(any(Prompt.class));
    }

    private String talk(String question) {
        return dispatcher.processStream(new AgentContext(question, SESSION, new CourseRecommendations(),
                        () -> true, () -> true, null, null, List.of(), null))
                .map(response -> response.getResult().getOutput().getText()).filter(java.util.Objects::nonNull)
                .collectList().map(parts -> String.join("", parts)).block(Duration.ofSeconds(5));
    }

    private Message last() { var history = memory.get(SESSION); return history.get(history.size() - 1); }

    private void roundTripMemory() {
        var serialized = memory.get(SESSION).stream().map(m -> MessageUtil.toMessage(MessageUtil.toJson(m))).toList();
        memory.clear(SESSION); memory.add(SESSION, serialized);
    }

    private ChatResponse text(String value) { return new ChatResponse(List.of(new Generation(new AssistantMessage(value)))); }

    private ChatResponse tool(String name, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(name + "-call", "function", name, arguments))).build())));
    }
}
