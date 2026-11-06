package com.tianji.aigc.tools;

import com.tianji.aigc.agent.ChatContextBudget;

import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.mcp.McpToolException;
import com.tianji.aigc.mcp.McpCallScope;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 课程工具先备齐资料，最终答案单独流式生成；中间文字不进入可见流或记忆。 */
public class CourseToolPreparationAdvisor implements StreamAdvisor {
    public static final String AGENT_TYPE = "courseAgentType";
    private static final int MAX_TOOL_ROUNDS = 8;
    private final String preparationMessage;
    private final String finalAnswerMessage;
    private final ChatModel model;
    private final ChatContextBudget budget;
    private final ToolCallingManager tools;

    public CourseToolPreparationAdvisor(ChatModel model) {
        this(model, ObservationRegistry.NOOP);
    }

    public CourseToolPreparationAdvisor(ChatModel model, ObservationRegistry registry) {
        this(model, registry, new ChatContextBudget());
    }

    public CourseToolPreparationAdvisor(ChatModel model, ObservationRegistry registry, ChatContextBudget budget) {
        this.model = model;
        this.budget = budget;
        this.tools = ToolCallingManager.builder().observationRegistry(registry)
                .toolExecutionExceptionProcessor(DefaultToolExecutionExceptionProcessor.builder()
                        .rethrowExceptions(List.of(McpToolException.class, CancellationException.class,
                                CourseToolCallLimitException.class)).build())
                .build();
        try {
            preparationMessage = new ClassPathResource("prompts/course-tool-preparation.txt")
                    .getContentAsString(StandardCharsets.UTF_8);
            finalAnswerMessage = new ClassPathResource("prompts/course-final-answer.txt")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("无法读取课程回答阶段提示词", error);
        }
    }

    @Override
    public String getName() { return "courseToolPreparation"; }

    @Override
    public int getOrder() { return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 2; }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (!(request.prompt().getOptions() instanceof ToolCallingChatOptions options)
                || options.getToolCallbacks().isEmpty()
                || !(options.getToolContext().get(CourseRecommendations.CONTEXT_KEY) instanceof CourseRecommendations)
                || !isCourseAgent(options)) {
            return chain.nextStream(request);
        }
        return Flux.defer(() -> {
            var active = new AtomicBoolean(true);
            return Mono.fromCallable(() -> prepare(request.prompt(), active))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(prepared -> {
                        requireActive(active);
                        if (prepared.factAnswer() == null) {
                            budget.check(prepared.prompt());
                            return CourseOutputGuard.filter(chain.nextStream(request.mutate().prompt(prepared.prompt()).build()));
                        }
                        if (options.getToolContext().get(McpCallScope.CONTEXT_KEY) instanceof McpCallScope scope) {
                            scope.checkActive();
                        }
                        return Flux.just(ChatClientResponse.builder().context(request.context())
                                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(prepared.factAnswer())))))
                                .build());
                    })
                    .doOnCancel(() -> active.set(false))
                    .doFinally(signal -> active.set(false));
        });
    }

    private Prepared prepare(Prompt original, AtomicBoolean active) {
        var options = (ToolCallingChatOptions) original.getOptions().copy();
        var context = new HashMap<>(options.getToolContext());
        var questions = original.getInstructions().stream().filter(UserMessage.class::isInstance)
                .map(Message::getText).toList();
        ((CourseRecommendations) options.getToolContext().get(CourseRecommendations.CONTEXT_KEY))
                .restoreIntake(original.getInstructions());
        String question = questions.isEmpty() ? "" : questions.get(questions.size() - 1);
        var scope = CourseTools.DetailScope.forQuestion(question);
        if (!scope.fullContent() && scope.topics().isEmpty()
                && question.matches("(?s).*(?:按这个|按刚才|按我的|继续推荐|根据这些|按照这些|照这个).*")) {
            for (int i = questions.size() - 2; i >= 0; i--) {
                var prior = CourseTools.DetailScope.forQuestion(questions.get(i));
                if (questions.get(i).matches("(?s).*(?:想|希望|目标|打算).*")
                        && !prior.fullContent() && !prior.topics().isEmpty()) { scope = prior; break; }
            }
        }
        context.put(CourseTools.DETAIL_SCOPE, scope);
        // 每回合新计数器，避免单例 CourseTools 跨轮累计；与 PublicRuleTools 的 TURN_CALLS_KEY 同模式。
        context.put(CourseTools.TURN_CALLS_KEY, new AtomicInteger(0));
        options.setToolContext(context);
        boolean consultation = options.getToolContext().get(AGENT_TYPE) == AgentTypeEnum.CONSULT;
        String purpose = consultation ? "本轮职责：CONSULT。查询或解释用户指向的课程，不以提供个人基础为查询前提。"
                : "本轮职责：RECOMMEND。先区分随便浏览和需要个人信息的个性化选课。";
        options.setInternalToolExecutionEnabled(false);
        options.setToolCallbacks(options.getToolCallbacks().stream().map(callback -> guarded(callback, active)).toList());
        List<Message> messages = new ArrayList<>(original.getInstructions());
        int systemIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage) { systemIndex = i; break; }
        }
        Message originalSystem = systemIndex < 0 ? null : messages.get(systemIndex);
        if (systemIndex < 0) {
            systemIndex = 0;
            messages.add(0, new SystemMessage(preparationMessage + "\n" + purpose));
        } else {
            messages.set(systemIndex, new SystemMessage(originalSystem.getText() + "\n" + preparationMessage + "\n" + purpose));
        }
        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            requireActive(active);
            var prompt = new Prompt(messages, options);
            budget.check(prompt);
            ChatResponse response = model.call(prompt);
            requireActive(active);
            if (response == null || response.getResult() == null) {
                throw new IllegalStateException("课程资料准备未返回结果");
            }
            if (!response.hasToolCalls()) {
                return finalPrompt(messages, options, systemIndex, originalSystem, consultation, purpose, question);
            }
            if (round == MAX_TOOL_ROUNDS) throw new ToolRoundLimitException();
            // 保留工具协议中的调用和结果，不让准备阶段的课程结论影响最终措辞。
            var calls = AssistantMessage.builder().content("")
                    .toolCalls(response.getResult().getOutput().getToolCalls()).build();
            var toolResponse = new ChatResponse(List.of(new Generation(calls)), response.getMetadata());
            requireActive(active);
            messages = new ArrayList<>(tools.executeToolCalls(prompt, toolResponse).conversationHistory());
            requireActive(active);
            var recommendations = (CourseRecommendations) options.getToolContext().get(CourseRecommendations.CONTEXT_KEY);
            // recommendCourses 已核实详情、状态与价格并提交最终卡片，无需再调用模型确认“准备完成”。
            // 查询详情自动产生的卡片、空提交或失败提交均不能触发此出口。
            if (recommendations.hasSelectedCourses()) {
                return finalPrompt(messages, options, systemIndex, originalSystem, consultation, purpose, question);
            }
        }
        throw new IllegalStateException("课程资料准备未完成");
    }

    private record Prepared(Prompt prompt, String factAnswer) { }

    private static boolean isCourseAgent(ToolCallingChatOptions options) {
        Object agentType = options.getToolContext().get(AGENT_TYPE);
        return agentType == AgentTypeEnum.CONSULT || agentType == AgentTypeEnum.RECOMMEND;
    }

    public static boolean isToolRoundLimit(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ToolRoundLimitException) return true;
        }
        return false;
    }

    /** 上限只表示准备未完成，不能据此认定无课程或自动接受已有候选。 */
    private static final class ToolRoundLimitException extends IllegalStateException {
        ToolRoundLimitException() { super("课程资料准备超过工具调用轮数"); }
    }

    private Prepared finalPrompt(List<Message> messages, ToolCallingChatOptions options, int systemIndex,
                               Message originalSystem, boolean consultation, String purpose, String question) {
        var recommendations = (CourseRecommendations) options.getToolContext().get(CourseRecommendations.CONTEXT_KEY);
        recommendations.restoreIntake(messages);
        if (!consultation && !recommendations.hasQueried()) {
            return new Prepared(null, recommendations.nextIntakeReply());
        }
        String queryState = recommendations.hasQueried()
                ? "本轮已执行课程查询。只依据本轮工具结果区分查无匹配、字段缺失与查询失败。"
                : "本轮未执行课程查询，不能得出没有匹配课程的结论。"
                + (consultation ? "本轮是课程咨询，如课程指代确实不明确，仅澄清是哪门课程，不强行询问个人基础。"
                : "此时如需了解个人情况，只问尚未知晓且未问过的基础或实际用途；已问但未回答的基础问题应换成未问过的用途问题。不展示旧课程或重复用户已答不出的方向二选一。");
        messages.set(systemIndex, new SystemMessage((originalSystem == null ? "" : originalSystem.getText())
                + "\n" + finalAnswerMessage + "\n" + purpose + "\n" + queryState));
        options.setToolCallbacks(List.of());
        options.setToolNames(Set.of());
        String factAnswer = CourseFactAnswer.answer(question, recommendations,
                (CourseTools.DetailScope) options.getToolContext().get(CourseTools.DETAIL_SCOPE));
        if (factAnswer != null && !recommendations.hasSelectedCourses()) recommendations.clearCardReasons();
        return new Prepared(new Prompt(messages, options), factAnswer);
    }

    private void requireActive(AtomicBoolean active) {
        if (!active.get() || Thread.currentThread().isInterrupted()) throw new CancellationException("课程查询已停止");
    }

    private ToolCallback guarded(ToolCallback callback, AtomicBoolean active) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() { return callback.getToolDefinition(); }
            @Override
            public ToolMetadata getToolMetadata() { return callback.getToolMetadata(); }
            @Override
            public String call(String input) {
                requireActive(active);
                return callback.call(input);
            }
            @Override
            public String call(String input, ToolContext context) {
                requireActive(active);
                return callback.call(input, context);
            }
        };
    }
}
