package com.tianji.aigc.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.tools.CourseFactAnswer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 一轮只选择一个业务智能体；内部路由结果不进入 SSE 或聊天记忆。 */
@Component
@Slf4j
public class AgentDispatcher {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final int MAX_ROUTE_CHARS = 2048;
    private final Map<AgentTypeEnum, Agent> agents = new EnumMap<>(AgentTypeEnum.class);
    private final ChatMemory memory;

    public AgentDispatcher(List<Agent> registeredAgents, ChatMemory memory) {
        this.memory = memory;
        for (Agent agent : registeredAgents) {
            if (agents.putIfAbsent(agent.getAgentType(), agent) != null) {
                throw new IllegalStateException("重复注册智能体：" + agent.getAgentType());
            }
        }
        for (AgentTypeEnum type : AgentTypeEnum.values()) {
            if (!agents.containsKey(type)) throw new IllegalStateException("缺少智能体：" + type);
        }
    }

    public Flux<ChatResponse> processStream(AgentContext context) {
        return Flux.defer(() -> {
            // 知识库会话的业务范围由服务端确定，不允许模型改变模式或绕过检索。
            if (context.knowledgeFilter() != null) {
                if (context.knowledgeAdvisor() == null) {
                    return Flux.error(new IllegalStateException("知识库会话缺少检索能力"));
                }
                return agents.get(AgentTypeEnum.KNOWLEDGE).processStream(context);
            }
            return readHistory(context).flatMapMany(this::routeAndExecute);
        });
    }

    private Mono<AgentContext> readHistory(AgentContext context) {
        return Mono.fromCallable(() -> context.withHistory(memory.get(context.conversationId())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<ChatResponse> routeAndExecute(AgentContext context) {
        context.recommendations().restoreIntake(context.history());
        if (context.userMemory() != null) {
            var memories = context.userMemory().memories();
            context.recommendations().restoreKnownFacts(
                    memories.stream().anyMatch(item -> "background".equals(item.key())),
                    memories.stream().anyMatch(item -> "goal".equals(item.key())));
        }
        String ordinalQuestion = context.question().replaceAll("[\\s。！？?！]", "");
        var ordinal = java.util.regex.Pattern.compile("第([一二三123])(?:个|门|门课|个课程)(?:是啥|是什么|是什么课|介绍一下)").matcher(ordinalQuestion);
        if (ordinal.matches()) {
            int position = switch (ordinal.group(1)) { case "一", "1" -> 1; case "二", "2" -> 2; default -> 3; };
            var references = AgentHistory.latestCards(context.history());
            if (references.size() < position) {
                String clarification = references.isEmpty() ? "目前没有可按顺序定位的课程，请说明课程名称。"
                        : "目前只展示了" + references.size() + "门课程，无法定位第" + position + "门，请说明课程名称。";
                return agents.get(AgentTypeEnum.KNOWLEDGE).processStream(context.withClarification(clarification));
            }
        }
        Mono<Decision> decisionSource = CourseFactAnswer.isExplicitCourseFactQuestion(context.question())
                ? Mono.just(new Decision(AgentTypeEnum.CONSULT, null, null))
                : PlatformRuleQuestion.matches(context.question())
                ? Mono.just(new Decision(AgentTypeEnum.KNOWLEDGE, null, null))
                : classify(context);
        // classify 完成回调可能落在模型流线程；active() 含阻塞 Redis 续租，切到 boundedElastic。
        // 停用仍返回 Flux.never()（与原语义一致），不可用 fromCallable(null)→empty 替代。
        return decisionSource.flatMapMany(decision -> Mono.fromCallable(context.active()::getAsBoolean)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(active -> {
                    log.debug("Agent route selected: agent={}, clarification={}", decision.agent(), decision.agent() == null);
                    // 路由完成到业务调用之间再检查一次，避免心跳尚未触发时启动已停止的请求。
                    if (!active) return Flux.never();
                    if (decision.agent() == null) {
                        boolean intake = decision.clarificationType() == ClarificationType.COURSE_INTAKE
                                || decision.clarificationType() == ClarificationType.COURSE_BASICS
                                || decision.clarificationType() == ClarificationType.COURSE_GOAL
                                || decision.clarificationType() == null && legacyCourseIntake(context);
                        // intake 最终只用服务端 nextIntakeReply；模型 clarification 可为 null，禁止 strip NPE。
                        String text = decision.clarification() == null ? "" : decision.clarification().strip();
                        if (intake) text = switch (decision.clarificationType() == null ? ClarificationType.COURSE_INTAKE : decision.clarificationType()) {
                            case COURSE_BASICS -> context.recommendations().nextIntakeReply(false);
                            case COURSE_GOAL -> context.recommendations().nextIntakeReply(true);
                            default -> context.recommendations().nextIntakeReply();
                        };
                        AgentContext clarification = context.withClarification(text);
                        return agents.get(AgentTypeEnum.KNOWLEDGE).processStream(clarification);
                    }
                    return agents.get(decision.agent()).processStream(context);
                }));
    }

    private Mono<Decision> classify(AgentContext context) {
        return agents.get(AgentTypeEnum.ROUTE).processStream(context)
                .map(this::routeText)
                .reduce(new StringBuilder(), (text, chunk) -> {
                    if (text.length() + chunk.length() > MAX_ROUTE_CHARS) {
                        throw new IllegalStateException("路由响应过长");
                    }
                    return text.append(chunk);
                })
                .map(text -> parseDecision(text.toString()))
                .timeout(Duration.ofSeconds(15))
                .onErrorMap(RoutingException::new);
    }

    private String routeText(ChatResponse response) {
        if (response.getResult() == null) return "";
        var output = response.getResult().getOutput();
        if (!output.getToolCalls().isEmpty()) throw new IllegalStateException("路由返回了工具调用");
        return output.getText() == null ? "" : output.getText();
    }

    private Decision parseDecision(String text) {
        try {
            Decision decision = JSON.readValue(text, Decision.class);
            if (decision == null) throw new IllegalStateException("路由结果为空");
            boolean clarify = StringUtils.hasText(decision.clarification());
            if (decision.agent() == null) {
                // COURSE_INTAKE/BASICS/GOAL 的展示文案由服务端固定生成，不因模型 clarification 空/过长整轮失败。
                // GENERAL/COURSE_REFERENCE 及无类型澄清仍走非空且 ≤200 的硬校验；不放宽 legacy 无类型分支。
                boolean intakeType = decision.clarificationType() == ClarificationType.COURSE_INTAKE
                        || decision.clarificationType() == ClarificationType.COURSE_BASICS
                        || decision.clarificationType() == ClarificationType.COURSE_GOAL;
                if (!intakeType && (!clarify || decision.clarification().length() > 200)) {
                    throw new IllegalStateException("澄清问题为空或过长");
                }
            } else if (decision.agent() == AgentTypeEnum.ROUTE || clarify || decision.clarificationType() != null) {
                throw new IllegalStateException("路由结果不属于有效业务分支");
            }
            return decision;
        } catch (Exception error) {
            throw new IllegalStateException("无法解析路由结果", error);
        }
    }

    public static boolean isRoutingFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof RoutingException) return true;
        }
        return false;
    }

    private boolean legacyCourseIntake(AgentContext context) {
        String question = context.question();
        return question.matches("(?s).*(?:量身定制|选课|课程推荐|推荐.{0,4}课程|按我的情况推荐).*" )
                || question.matches("\\s*我?也?不(?:知道|懂)[。！？?！\\s]*")
                && (!AgentHistory.latestCards(context.history()).isEmpty() || context.recommendations().intakeQuestions() > 0);
    }

    private enum ClarificationType { COURSE_INTAKE, COURSE_BASICS, COURSE_GOAL, COURSE_REFERENCE, GENERAL }
    private record Decision(AgentTypeEnum agent, String clarification, ClarificationType clarificationType) { }

    private static class RoutingException extends RuntimeException {
        RoutingException(Throwable cause) { super("意图路由失败", cause); }
    }
}
