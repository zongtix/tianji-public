package com.tianji.aigc.knowledge;

import com.tianji.aigc.config.KnowledgeRagProperties;
import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/** 强制限定检索范围；空召回直接说明资料不足，检索失败不降级成模型自由回答。 */
public class KnowledgeRetrievalAdvisor implements BaseAdvisor {
    public static final String GENERATION_ACTIVE = "knowledge_generation_active";
    private static final String CLARIFICATION = "knowledge_clarification";
    private static final Duration DEFAULT_SYNC_TIMEOUT = Duration.ofSeconds(30);
    private final Duration syncTimeout;
    private final VectorStore vectorStore;
    private final SearchRequest searchRequest;
    private final PromptTemplate promptTemplate;
    private final KnowledgeQueryRewriter rewriter;
    private KnowledgeRetrievalPipeline pipeline;
    private KnowledgeContextSelector selector;
    private KnowledgeRagProperties settings;
    private RagTelemetry telemetry;
    private static final String INCOMPLETE = "knowledge_retrieval_incomplete";
    private static final String BUDGET_EMPTY = "knowledge_context_budget_empty";
    public static final String RETRIEVAL_RESULT = "knowledge_retrieval_result";

    public KnowledgeRetrievalAdvisor(VectorStore vectorStore, SearchRequest searchRequest,
                                     PromptTemplate promptTemplate, KnowledgeQueryRewriter rewriter) {
        this(vectorStore, searchRequest, promptTemplate, rewriter, DEFAULT_SYNC_TIMEOUT);
    }

    KnowledgeRetrievalAdvisor(VectorStore vectorStore, SearchRequest searchRequest,
                              PromptTemplate promptTemplate, KnowledgeQueryRewriter rewriter, Duration syncTimeout) {
        this.syncTimeout = syncTimeout;
        this.vectorStore = vectorStore;
        this.searchRequest = searchRequest;
        this.promptTemplate = promptTemplate;
        this.rewriter = rewriter;
    }

    public KnowledgeRetrievalAdvisor(VectorStore vectorStore, SearchRequest searchRequest,
            PromptTemplate promptTemplate, KnowledgeQueryRewriter rewriter, KnowledgeRetrievalPipeline pipeline,
            KnowledgeContextSelector selector, KnowledgeRagProperties settings, RagTelemetry telemetry) {
        this(vectorStore, searchRequest, promptTemplate, rewriter);
        this.pipeline = pipeline;
        this.selector = selector;
        this.settings = settings;
        this.telemetry = telemetry;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Duration timeout = settings == null || syncTimeout.compareTo(settings.getPreparationTimeout()) < 0
                ? syncTimeout : settings.getPreparationTimeout();
        // 同步路径取消必须立刻以 CancellationException 结束，不能落到 Mono.never() 等到 preparation 超时。
        return prepare(request, () -> isActive(request), true).timeout(timeout).block();
    }

    private Mono<ChatClientRequest> prepare(ChatClientRequest request, BooleanSupplier active) {
        return prepare(request, active, false);
    }

    /**
     * @param failFastOnCancel true=同步 Advisor：取消以异常完成，避免 block() 空等超时；
     *                         false=流式：保留 Mono.never()，由上层心跳 takeUntilOther 收束，
     *                         且迟到的阻塞召回成功不得启动回答模型。
     */
    private Mono<ChatClientRequest> prepare(ChatClientRequest request, BooleanSupplier active, boolean failFastOnCancel) {
        return Mono.defer(() -> {
            AtomicBoolean expired = new AtomicBoolean();
            BooleanSupplier current = () -> !expired.get() && active.getAsBoolean();
            RagRequestGuard guard = settings == null ? null : new RagRequestGuard(current, settings.getPreparationTimeout());
            Object filter = request.context().get(QuestionAnswerAdvisor.FILTER_EXPRESSION);
            Assert.state(filter != null && StringUtils.hasText(filter.toString()), "知识库检索必须指定授权文档范围");
            if (!active.getAsBoolean()) return cancelSignal(failFastOnCancel);
            if (selector != null) selector.check(request);
            return rewrite(request).publishOn(getScheduler()).flatMap(result -> {
                if (!active.getAsBoolean()) return cancelSignal(failFastOnCancel);
                if (guard != null) guard.check();
                if (result.clarification()) {
                    return Mono.just(request.mutate().context(CLARIFICATION, true)
                            .context(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, List.of()).build());
                }
                return Mono.fromCallable(() -> retrieve(request, result.query(), guard))
                        // 流式取消保留 never（由 takeUntilOther 收束）；同步取消转为异常，避免空等超时。
                        .onErrorResume(java.util.concurrent.CancellationException.class,
                                error -> cancelSignal(failFastOnCancel))
                        // 阻塞向量客户端可能在取消后才返回，禁止继续调用回答模型。
                        .flatMap(augmented -> active.getAsBoolean() ? Mono.just(augmented) : cancelSignal(failFastOnCancel));
            }).doOnCancel(() -> expired.set(true));
        }).subscribeOn(getScheduler());
    }

    private static Mono<ChatClientRequest> cancelSignal(boolean failFastOnCancel) {
        return failFastOnCancel
                ? Mono.error(new java.util.concurrent.CancellationException("知识库请求已失效"))
                : Mono.never();
    }

    private Mono<KnowledgeQueryRewriter.Result> rewrite(ChatClientRequest request) {
        if (telemetry == null) return rewriter.rewrite(request);
        return Mono.defer(() -> {
            Observation observation = Observation.start("rag.rewrite", telemetry.registry());
            return rewriter.rewrite(request)
                    .doOnNext(result -> observation.highCardinalityKeyValue("rag.rewrite.result",
                            result.clarification() ? "CLARIFY" : result.query().equals(request.prompt().getUserMessage().getText()) ? "KEEP" : "REWRITE"))
                    .doOnError(error -> observation.error(new IllegalStateException(error.getClass().getSimpleName())))
                    .doFinally(signal -> { observation.highCardinalityKeyValue("rag.stage.status", signal.name()); observation.stop(); })
                    .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, observation));
        });
    }

    private ChatClientRequest retrieve(ChatClientRequest request, String query, RagRequestGuard guard) {
        try {
            if (pipeline != null) return retrievePipeline(request, query, guard);
            List<Document> documents = vectorStore.similaritySearch(SearchRequest.from(searchRequest).query(query)
                    .filterExpression(new FilterExpressionTextParser().parse(
                            request.context().get(QuestionAnswerAdvisor.FILTER_EXPRESSION).toString())).build());
            Map<String, Object> context = new HashMap<>(request.context());
            context.put(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, documents);
            String documentContext = documents.stream().map(Document::getText)
                    .collect(Collectors.joining(System.lineSeparator()));
            // 改写文本只进入 SearchRequest；最终回答请求始终使用用户原话。
            String augmentedText = promptTemplate.render(Map.of("query", request.prompt().getUserMessage().getText(),
                    "question_answer_context", documentContext));
            return request.mutate().prompt(request.prompt().augmentUserMessage(augmentedText)).context(context).build();
        } catch (RuntimeException error) {
            // 取消必须保留专用语义；禁止返回 null（fromCallable 空信号 + never 会拖死同步 block）。
            if (error instanceof java.util.concurrent.CancellationException) throw error;
            if (error instanceof KnowledgeContextSelector.ContextTooLongException) throw error;
            throw new RetrievalException(error);
        }
    }

    private ChatClientRequest retrievePipeline(ChatClientRequest request, String query, RagRequestGuard guard) {
        var result = pipeline.retrieve(query, new FilterExpressionTextParser().parse(
                request.context().get(QuestionAnswerAdvisor.FILTER_EXPRESSION).toString()), guard);
        guard.check();
        boolean compact = settings.getMode() == KnowledgeRagProperties.Mode.D
                || settings.getMode() == KnowledgeRagProperties.Mode.B_CONTEXT;
        boolean hybrid = settings.getMode() != KnowledgeRagProperties.Mode.A
                && settings.getMode() != KnowledgeRagProperties.Mode.APRIME;
        int limit = hybrid ? settings.getRetrieval().getContextTopK() : result.candidates().size();
        List<Document> documents = telemetry.stage("context", () -> compact
                ? selector.select(result.candidates(), limit, docs -> assemble(request, docs))
                : result.candidates().stream().limit(limit).map(RagCandidate::document).toList());
        ChatClientRequest augmented = assemble(request, documents);
        // A/A′ 沿用原拼接，所有模式共用最终超限保护，不偷偷压缩基线。
        selector.check(augmented);
        telemetry.attribute("context.selected", documents.stream().map(RagCandidate::identity).toList());
        telemetry.attribute("context.estimated_input_tokens", selector.requestTokens(augmented));
        telemetry.attribute("context.estimator", "cl100k_base_estimate");
        guard.check();
        return augmented.mutate().context(RETRIEVAL_RESULT, result)
                .context(BUDGET_EMPTY, documents.isEmpty() && !result.candidates().isEmpty())
                .context(INCOMPLETE, !result.failedRoutes().isEmpty()).build();
    }

    private ChatClientRequest assemble(ChatClientRequest request, List<Document> documents) {
        String documentContext = documents.stream().map(Document::getText).collect(Collectors.joining(System.lineSeparator()));
        String augmentedText = promptTemplate.render(Map.of("query", request.prompt().getUserMessage().getText(),
                "question_answer_context", documentContext));
        return request.mutate().prompt(request.prompt().augmentUserMessage(augmentedText))
                .context(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, documents).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        var builder = response.chatResponse() == null ? ChatResponse.builder()
                : ChatResponse.builder().from(response.chatResponse());
        return response.mutate().chatResponse(builder.metadata(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS,
                response.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS)).build()).build();
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Observation root = requestObservation(request);
        boolean success = false;
        try (var scope = root == null ? null : root.openScope()) {
            var augmented = before(request, chain);
            if (!isActive(request)) throw new java.util.concurrent.CancellationException("知识库请求已失效");
            var result = after(hasDocuments(augmented) ? chain.nextCall(augmented) : directResponse(augmented), chain);
            success = true;
            return result;
        } catch (RuntimeException error) {
            if (root != null) root.error(new IllegalStateException(error.getClass().getSimpleName()));
            throw error;
        } finally {
            if (root != null) { root.highCardinalityKeyValue("rag.final.status", success ? "ON_COMPLETE" : "ON_ERROR"); root.stop(); }
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            AtomicBoolean cancelled = new AtomicBoolean();
            Observation root = requestObservation(request);
            Mono<ChatClientRequest> preparation = prepare(request, () -> !cancelled.get() && isActive(request));
            if (settings != null) preparation = preparation.timeout(settings.getPreparationTimeout())
                    .onErrorMap(java.util.concurrent.TimeoutException.class, RetrievalException::new);
            Flux<ChatClientResponse> responses = preparation
                    .flatMapMany(augmented -> cancelled.get() || !isActive(request) ? Flux.never() : hasDocuments(augmented)
                            ? chain.nextStream(augmented) : Flux.just(directResponse(augmented)))
                    .map(response -> after(response, chain))
                    .doOnCancel(() -> cancelled.set(true));
            if (root == null) return responses;
            return responses.doOnError(error -> root.error(new IllegalStateException(error.getClass().getSimpleName())))
                    .doFinally(signal -> { root.highCardinalityKeyValue("rag.final.status", signal.name()); root.stop(); })
                    .contextWrite(context -> context.put(ObservationThreadLocalAccessor.KEY, root));
        });
    }

    private Observation requestObservation(ChatClientRequest request) {
        return telemetry == null ? null : Observation.createNotStarted("rag.request", telemetry.registry())
                    .highCardinalityKeyValue("langfuse.session.id", String.valueOf(request.context().get(ChatMemory.CONVERSATION_ID)))
                    .highCardinalityKeyValue("rag.request.id", UUID.randomUUID().toString())
                    .highCardinalityKeyValue("rag.mode", settings.getMode().name())
                    .highCardinalityKeyValue("rag.config.version", settings.getConfigVersion())
                    .highCardinalityKeyValue("rag.experiment.id", settings.getExperimentId())
                    .highCardinalityKeyValue("rag.code.version", settings.getCodeVersion())
                    .highCardinalityKeyValue("rag.prompt.version", settings.getPromptVersion())
                    .highCardinalityKeyValue("rag.data.version", settings.getDataVersion()).start();
    }

    private boolean isActive(ChatClientRequest request) {
        return !(request.context().get(GENERATION_ACTIVE) instanceof BooleanSupplier active) || active.getAsBoolean();
    }

    private boolean hasDocuments(ChatClientRequest request) {
        return request.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS) instanceof List<?> docs
                && !docs.isEmpty();
    }

    private ChatClientResponse directResponse(ChatClientRequest request) {
        String text = Boolean.TRUE.equals(request.context().get(CLARIFICATION))
                ? "请补充一下你指的是哪项规则或哪个问题，以便我在当前知识库中准确查找。"
                : Boolean.TRUE.equals(request.context().get(INCOMPLETE))
                ? "本次知识库检索未完整完成，暂时无法取得足够资料，请稍后重试。"
                : Boolean.TRUE.equals(request.context().get(BUDGET_EMPTY))
                ? "已找到相关资料，但当前会话无法容纳完整证据，请缩小问题范围或新建会话后重试。"
                : "当前知识库中没有找到足够相关的资料，暂时无法根据该知识库回答这个问题。";
        return ChatClientResponse.builder().context(request.context())
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text))))).build();
    }

    @Override
    public int getOrder() { return 0; }

    @Override
    public Scheduler getScheduler() { return Schedulers.boundedElastic(); }

    public static boolean isRetrievalFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof RetrievalException) return true;
        }
        return false;
    }

    private static class RetrievalException extends RuntimeException {
        private RetrievalException(Throwable cause) { super("知识库查询失败", cause); }
    }
}
