package com.tianji.aigc.service.impl;

import com.tianji.aigc.agent.AgentContext;
import com.tianji.aigc.agent.AgentDispatcher;
import com.tianji.aigc.knowledge.KnowledgeBaseAccess;
import com.tianji.aigc.knowledge.KnowledgeRetrievalAdvisor;
import com.tianji.aigc.knowledge.KnowledgeQueryRewriter;
import com.tianji.aigc.knowledge.KnowledgeContextSelector;
import com.tianji.aigc.knowledge.course.CourseSemanticSearch;
import com.tianji.aigc.longterm.UserMemoryService;
import com.tianji.aigc.longterm.UserMemoryStore;
import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.service.ChatTitleGenerator;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.aigc.mcp.McpToolException;
import com.tianji.aigc.mcp.McpCallScope;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.memory.MessageUtil;
import com.tianji.aigc.tools.CourseRecommendations;
import com.tianji.aigc.tools.CourseToolPreparationAdvisor;
import com.tianji.aigc.tools.PublicRuleCallLimitException;
import com.tianji.aigc.service.ChatGenerationStore;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.vo.ChatEventVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl  implements ChatService {

    private final AgentDispatcher agentDispatcher;
    private final ChatGenerationStore generationStore;
    private final ChatMemory chatMemory;
    private final ChatTitleGenerator titleGenerator;
    private final ChatSessionService sessionService;
    private final KnowledgeBaseAccess knowledgeBaseAccess;
    private final ObjectProvider<KnowledgeRetrievalAdvisor> knowledgeAdvisor;
    private final ObservationRegistry observationRegistry;
    private UserMemoryService userMemory;
    @Value("${tj.ai.observation.conversation-content:false}")
    private boolean captureConversation;
    @Value("${tj.ai.observation.memory-details:false}")
    private boolean captureMemoryDetails;

    @Autowired(required = false)
    void setUserMemory(UserMemoryService userMemory) { this.userMemory = userMemory; }

    @Override
    public Flux<ChatEventVO> chat(String question, String sessionId) {
        return chat(question, sessionId, false);
    }

    @Override
    public Flux<ChatEventVO> chat(String question, String sessionId, boolean stopAcknowledgement) {
        // 进入响应式调度前读取用户身份与会话范围，后续不依赖线程局部变量。
        var session = sessionService.requireOwnedSession(sessionId);
        String filter = knowledgeBaseAccess.requireFilter(session.getKnowledgeBaseId());
        var rag = filter == null ? null : knowledgeAdvisor.getIfAvailable();
        if (filter != null && rag == null) throw new BadRequestException("知识库功能暂不可用");
        var conversationId = ChatService.conversationId(session.getUserId(), sessionId);
        return Flux.defer(() -> {
            long started = System.nanoTime();
            // 同一轮的路由、工具准备、RAG、回答和标题共享会话标识与父观测。
            Observation observation = Observation.createNotStarted("chat.request", observationRegistry)
                    .highCardinalityKeyValue("langfuse.session.id", conversationId).start();
            StringBuffer answer = new StringBuffer();
            CourseRecommendations recommendations = new CourseRecommendations();
            AtomicBoolean cancelled = new AtomicBoolean();
            AtomicReference<Mono<Void>> partialSave = new AtomicReference<>(Mono.empty());
            return Flux.usingWhen(
                    redisCall(() -> generationStore.acquire(sessionId)).doOnNext(generation -> {
                        // 主动停止后又断连时，共享同一次保存；取消不会中断已启动的保存操作。
                        partialSave.set(Mono.defer(() -> saveStoppedAnswer(generation, conversationId, answer.toString()))
                                .cache());
                    }),
                    generation -> (stopAcknowledgement
                            ? Flux.just(ChatEventVO.builder().eventType(ChatEventTypeEnum.PARAM.getValue())
                                    .eventData(Map.of("state", "ready", "requestId", generation.requestId())).build())
                            : Flux.<ChatEventVO>empty()).concatWith(
                            redisCall(() -> sessionService.requireOwnedSession(sessionId, session.getUserId()))
                            // 排队期间会话可能已被删除；取得占位后复查，禁止重新写出孤立消息。
                            .flatMap(owned -> redisCall(() -> sessionService.claimFirstTurnTitle(sessionId, session.getUserId())))
                            .flatMapMany(firstTurn -> generate(question, generation, conversationId, answer,
                                    partialSave.get(), recommendations, rag, filter, observation, started,
                                    sessionId, session.getUserId(), cancelled)
                                    .concatWith(firstTurn ? generateTitle(question, answer, generation, sessionId,
                                            session.getUserId()) : Flux.empty()))),
                    generation -> release(generation, conversationId, !cancelled.get() && filter == null),
                    (generation, error) -> release(generation, conversationId, false),
                    generation -> partialSave.get()
                            .onErrorResume(error -> {
                                // 连接已关闭，无法向客户端返回错误；记录失败后仍释放占位。
                                log.warn("取消聊天后保存部分回答失败, errorType={}", error.getClass().getSimpleName());
                                return Mono.empty();
                            })
                            .then(release(generation, conversationId, false)))
                    .doFinally(signal -> recommendations.close())
                    .doOnError(error -> {
                        if (McpCallScope.isCancellation(error)) observation.highCardinalityKeyValue("chat.cancelled", "true");
                        else observation.error(new IllegalStateException(error.getClass().getSimpleName()));
                    })
                    .onErrorResume(error -> {
                        // The MCP guard may see STOP before the heartbeat cancels the publisher.
                        // Cleanup has already run; complete through the existing terminal STOP event.
                        if (McpCallScope.isCancellation(error)) return Flux.empty();
                        var mcpError = McpToolException.find(error);
                        if (mcpError != null) {
                            return Flux.just(ChatEventVO.builder().eventType(ChatEventTypeEnum.ERROR.getValue())
                                    .eventData(Map.of("code", mcpError.code(), "message", mcpError.getMessage())).build());
                        }
                        if (KnowledgeContextSelector.isContextTooLong(error)) {
                            return Flux.just(ChatEventVO.builder().eventType(ChatEventTypeEnum.ERROR.getValue())
                                    .eventData(Map.of("code", "KNOWLEDGE_CONTEXT_TOO_LONG",
                                            "message", "上下文过长，请缩短问题或新建会话后重试。")).build());
                        }
                        if (CourseSemanticSearch.isRetrievalFailure(error)) {
                            log.warn("课程语义检索失败，会话 {}, errorType={}", sessionId, error.getClass().getSimpleName());
                            return Flux.just(ChatEventVO.builder().eventType(ChatEventTypeEnum.ERROR.getValue())
                                    .eventData(Map.of("code", "COURSE_RETRIEVAL_FAILED",
                                            "message", "课程查询失败，请稍后重试。")).build());
                        }
                        if (AgentDispatcher.isRoutingFailure(error)) {
                            log.warn("意图路由失败，会话 {}, errorType={}", sessionId, error.getClass().getSimpleName());
                            return Flux.just(ChatEventVO.builder().eventType(ChatEventTypeEnum.ERROR.getValue())
                                    .eventData(Map.of("code", "AGENT_ROUTING_FAILED",
                                            "message", "暂时无法判断你的需求，请补充说明或稍后重试。")).build());
                        }
                        if (CourseToolPreparationAdvisor.isToolRoundLimit(error)) {
                            log.warn("课程资料准备达到轮数上限，会话 {}, errorType={}", sessionId, error.getClass().getSimpleName());
                            return Flux.just(ChatEventVO.builder().eventType(ChatEventTypeEnum.ERROR.getValue())
                                    .eventData(Map.of("code", "COURSE_PREPARATION_INCOMPLETE",
                                            "message", "本次课程查询未能完成，请缩小查询范围或稍后重试。")).build());
                        }
                        if (PublicRuleCallLimitException.is(error)) {
                            log.warn("公共规则检索达到回合预算，会话 {}, errorType={}", sessionId, error.getClass().getSimpleName());
                            return Flux.just(ChatEventVO.builder().eventType(ChatEventTypeEnum.ERROR.getValue())
                                    .eventData(Map.of("code", "PUBLIC_RULE_CALL_LIMIT",
                                            "message", "本轮规则查询次数已用尽，请缩小问题范围或稍后重试。")).build());
                        }
                        if (KnowledgeQueryRewriter.isRewriteFailure(error)) {
                            log.warn("知识库检索问题改写失败，会话 {}, errorType={}", sessionId, error.getClass().getSimpleName());
                            return Flux.just(ChatEventVO.builder().eventType(ChatEventTypeEnum.ERROR.getValue())
                                    .eventData(Map.of("code", "KNOWLEDGE_QUERY_REWRITE_FAILED",
                                            "message", "暂时无法整理追问，请补充完整问题或稍后重试。")).build());
                        }
                        if (!KnowledgeRetrievalAdvisor.isRetrievalFailure(error)) {
                            log.warn("聊天回答生成失败，会话 {}, errorType={}", sessionId, error.getClass().getSimpleName());
                            return Flux.just(ChatEventVO.builder().eventType(ChatEventTypeEnum.ERROR.getValue())
                                    .eventData(Map.of("code", "CHAT_GENERATION_FAILED",
                                            "message", "回答生成失败，请稍后重试。")).build());
                        }
                        log.warn("知识库检索失败，会话 {}, errorType={}", sessionId, error.getClass().getSimpleName());
                        return Flux.just(ChatEventVO.builder().eventType(ChatEventTypeEnum.ERROR.getValue())
                                .eventData(Map.of("code", "KNOWLEDGE_RETRIEVAL_FAILED",
                                        "message", "知识库查询失败，请稍后重试。")).build());
                    })
                    .concatWith(Flux.just(ChatEventVO.builder()
                            .eventType(ChatEventTypeEnum.STOP.getValue())
                            .build()))
                    .doFinally(signal -> {
                        if (captureConversation) {
                            observation.highCardinalityKeyValue("langfuse.observation.input", traceConversationText(question));
                            if (!answer.isEmpty()) observation.highCardinalityKeyValue("langfuse.observation.output", traceConversationText(answer.toString()));
                        }
                        observation.highCardinalityKeyValue("chat.final.status", signal.name());
                        observation.highCardinalityKeyValue("chat.total.ms", elapsedMillis(started));
                        observation.stop();
                    })
                    .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation));
        });
    }

    private Flux<ChatEventVO> generate(String question, ChatGenerationStore.Generation generation, String conversationId,
                                      StringBuffer answer, Mono<Void> partialSave, CourseRecommendations recommendations,
                                      KnowledgeRetrievalAdvisor rag, String filter, Observation observation, long started,
                                      String sessionId, Long userId, AtomicBoolean cancelled) {
        AtomicBoolean firstToken = new AtomicBoolean();
        AtomicReference<Throwable> coordinationError = new AtomicReference<>();
        Mono<Boolean> stopped = renewPeriodically(generation)
                .onErrorResume(error -> {
                    coordinationError.set(error);
                    return Flux.just(false);
                })
                .filter(alive -> !alive)
                .next();

        return Flux.defer(() -> {
            Mono<java.util.Optional<com.tianji.aigc.longterm.UserMemoryContext>> userContext = userMemory == null || filter != null
                    ? Mono.just(java.util.Optional.empty())
                    : redisCall(() -> {
                        var prepared = userMemory.prepare(userId, sessionId, generation.requestId(), conversationId, question);
                        if (!prepared.memories().isEmpty()) {
                            observation.highCardinalityKeyValue("user_memory.keys", prepared.memories().stream()
                                    .map(UserMemoryStore.Memory::key).sorted().collect(java.util.stream.Collectors.joining(",")));
                            if (captureMemoryDetails) observation.highCardinalityKeyValue("user_memory.read", prepared.memories().stream()
                                    .map(item -> item.key() + ": " + traceConversationText(item.content()))
                                    .collect(java.util.stream.Collectors.joining("\n")));
                        }
                        try {
                            if (cancelled.get() || !generationStore.renew(generation)) userMemory.abandon(generation.requestId());
                        } catch (RuntimeException error) {
                            userMemory.abandon(generation.requestId());
                            throw error;
                        }
                        return java.util.Optional.of(prepared);
                    })
                            .onErrorResume(error -> {
                                log.warn("读取用户长期记忆失败，本轮继续聊天: {}", error.getClass().getSimpleName());
                                return Mono.just(java.util.Optional.empty());
                            });
            return userContext.flatMapMany(memory -> {
                var context = new AgentContext(question, conversationId, userId, recommendations,
                        () -> !cancelled.get() && generationStore.renew(generation),
                        () -> generationStore.isCurrent(generation), rag, filter, List.of(), null, memory.orElse(null));
                return agentDispatcher.processStream(context);
            });
        })
                // 放在停止操作符上游才能收到取消；这里只记录，保存流程在下面串行等待。
                .doOnCancel(() -> {
                    cancelled.set(true);
                    recommendations.close();
                })
                .takeWhile(response -> generation.isValid())
                .takeUntilOther(stopped)
                .filter(response -> response.getResult() != null
                        && StringUtils.hasLength(response.getResult().getOutput().getText()))
                .map(chatResponse -> {
                    String elapsed = elapsedMillis(started);
                    if (firstToken.compareAndSet(false, true)) {
                        observation.highCardinalityKeyValue("chat.first_token.ms", elapsed);
                    }
                    observation.highCardinalityKeyValue("chat.last_token.ms", elapsed);
                    //大模型生成的内容
                    var text = chatResponse.getResult().getOutput().getText();
                    answer.append(text);
                    return ChatEventVO.builder()
                            .eventData(text)
                            .eventType(ChatEventTypeEnum.DATA.getValue())
                            .build();
                })
                .doOnComplete(() -> {
                    if (!cancelled.get()) observation.highCardinalityKeyValue("chat.answer_complete.ms", elapsedMillis(started));
                })
                .concatWith(Flux.defer(() -> {
                    if (coordinationError.get() != null) return Flux.error(coordinationError.get());
                    recommendations.close();
                    if (cancelled.get()) {
                        // 未发送的卡片不补存；停止流程只保存已输出的文字。
                        return partialSave.thenMany(Flux.empty());
                    }
                    var cards = recommendations.snapshot();
                    if (cards.isEmpty()) return Flux.empty();
                    return redisCall(() -> generationStore.isCurrent(generation))
                            .flatMapMany(current -> current
                                    ? Flux.just(ChatEventVO.builder()
                                            .eventType(ChatEventTypeEnum.COURSE_CARDS.getValue())
                                            .eventData(Map.of("cards", cards)).build())
                                    : Flux.empty());
                }));
    }

    private Flux<ChatEventVO> generateTitle(String question, StringBuffer answer,
                                            ChatGenerationStore.Generation generation, String sessionId, Long userId) {
        return Mono.defer(() -> {
            if (!StringUtils.hasText(answer)) return Mono.empty();
            return redisCall(() -> generationStore.renew(generation)).flatMap(active -> {
                if (!active) return Mono.empty();
                return titleGenerator.generate(question, answer.toString())
                        // 标题阶段继续续租；用户停止、租约丢失或断连都会取消标题请求。
                        .takeUntilOther(renewPeriodically(generation)
                                .filter(alive -> !alive).next())
                        .flatMap(title -> redisCall(() -> {
                            if (generationStore.renew(generation)) sessionService.saveGeneratedTitle(sessionId, userId, title);
                            return true;
                        })).then();
            });
        }).onErrorResume(error -> {
            // 标题失败只保留临时标题，已经完成的回答仍正常结束。
            log.warn("生成会话标题失败，保留临时标题，会话 {}, errorType={}", sessionId, error.getClass().getSimpleName());
            return Mono.empty();
        }).thenMany(Flux.empty());
    }

    private Mono<Void> saveStoppedAnswer(ChatGenerationStore.Generation generation, String conversationId,
                                         String answer) {
        if (!StringUtils.hasText(answer)) return Mono.empty();
        return redisCall(() -> {
            // 主动停止和断连取消都补存；租约过期或被新请求接管时不写入。
            if (!generationStore.isCurrent(generation)) return false;
            var history = chatMemory.get(conversationId);
            if (!history.isEmpty()) {
                var last = history.get(history.size() - 1);
                // 正常完成恰好先保存时，不再追加同一份回答。
                if (last.getMessageType() == MessageType.ASSISTANT && answer.equals(last.getText())) return false;
            }
            chatMemory.add(conversationId, AssistantMessage.builder().content(answer)
                    .properties(Map.of(MessageUtil.GENERATION_STATUS, MessageUtil.STOPPED)).build());
            return true;
        }).then();
    }

    @Override
    public void stop(String sessionId) {
        generationStore.stop(sessionId);
    }

    @Override
    public boolean stop(String sessionId, String requestId) {
        return generationStore.stop(sessionId, requestId);
    }

    private Mono<Void> release(ChatGenerationStore.Generation generation, String conversationId, boolean completed) {
        return redisCall(() -> {
            if (userMemory != null) {
                try {
                    if (completed && userMemory.isPersisted(generation.requestId(), conversationId)) {
                        if (!generationStore.complete(generation)) {
                            // 停止或租约失效后故意不提取；不能以补偿名义复活任务。
                            log.info("长期记忆未取得完成封存，本轮跳过提取, reason=completion_rejected, requestRef={}",
                                    Integer.toHexString(java.util.Objects.hashCode(generation.requestId())));
                        } else if (!userMemory.markReady(generation.requestId(), conversationId)) {
                            log.warn("长期记忆交接未接受 READY，本轮跳过提取, requestRef={}",
                                    Integer.toHexString(java.util.Objects.hashCode(generation.requestId())));
                        }
                    }
                } catch (RuntimeException error) {
                    log.warn("确认长期记忆任务失败，本轮跳过提取: {}", error.getClass().getSimpleName());
                }
                try { userMemory.abandon(generation.requestId()); }
                catch (RuntimeException error) { log.warn("清理未完成记忆任务失败: {}", error.getClass().getSimpleName()); }
            }
            generationStore.release(generation);
            return true;
        }).then().onErrorResume(error -> {
            // Redis 暂时不可用时保留原始终止结果，未释放的占位由 TTL 回收。
            log.warn("释放聊天生成占位失败，等待租约过期, errorType={}", error.getClass().getSimpleName());
            return Mono.empty();
        });
    }

    private Flux<Boolean> renewPeriodically(ChatGenerationStore.Generation generation) {
        // 等本次 Redis 检查完成后再计时，慢续租时不积压定时事件，也不并发执行续租。
        return redisCall(() -> generationStore.renew(generation))
                .delaySubscription(Duration.ofSeconds(1))
                .repeat();
    }

    private static <T> Mono<T> redisCall(Callable<T> action) {
        // StringRedisTemplate 是阻塞客户端，避免占用模型流的事件线程。
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static String traceConversationText(String text) {
        if (UserMemoryStore.isSensitive(text)) return "[已隐藏可能包含敏感信息的内容]";
        return text.length() <= 8000 ? text : text.substring(0, 8000) + "…[truncated]";
    }

    private static String elapsedMillis(long started) {
        return Long.toString(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
}
