package com.tianji.aigc.tools;

import com.tianji.aigc.knowledge.KnowledgeBaseAccess;
import com.tianji.aigc.knowledge.KnowledgeRetrievalPipeline;
import com.tianji.aigc.knowledge.RagCandidate;
import com.tianji.aigc.knowledge.RagRequestGuard;
import com.tianji.aigc.mcp.McpCallScope;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 公共规则（积分/优惠券）按需检索工具；仅挂普通会话。
 * 授权与 document 范围一律服务端决定，不合并白名单。
 */
@Component
@ConditionalOnProperty(prefix = "tj.ai", name = {"knowledge.enabled", "public-rules.enabled"}, havingValue = "true")
@ConditionalOnBean(KnowledgeRetrievalPipeline.class)
public class PublicRuleTools {
    public static final String USER_ID_KEY = "publicRuleUserId";
    public static final String TURN_CALLS_KEY = "publicRuleTurnCalls";
    /** 本回合已调用过的 domain 集合（{@link Set}{@code <String>}），每域最多一次。 */
    public static final String DOMAIN_CALLS_KEY = "publicRuleDomainCalls";
    /** 旧 knowledgeBaseId 会话标志；为 true 时拒绝工具（Task 5 在 knowledgeFilter!=null 时设置）。 */
    public static final String LEGACY_SESSION_KEY = "publicRuleLegacySession";

    private static final Map<String, String> DOMAIN_TO_BASE = Map.of(
            "POINTS", "tianji-points",
            "COUPONS", "tianji-coupons");

    private final KnowledgeRetrievalPipeline pipeline;
    private final KnowledgeBaseAccess access;
    private final int maxCallsPerTurn;
    private final int maxEvidence;

    public PublicRuleTools(KnowledgeRetrievalPipeline pipeline, KnowledgeBaseAccess access,
            @Value("${tj.ai.public-rules.max-calls-per-turn:3}") int maxCallsPerTurn,
            @Value("${tj.ai.public-rules.max-evidence:3}") int maxEvidence) {
        this.pipeline = pipeline;
        this.access = access;
        this.maxCallsPerTurn = maxCallsPerTurn;
        this.maxEvidence = maxEvidence;
    }

    @Tool(description = "检索平台公共规则原文。domain 仅选 POINTS（积分/签到）或 COUPONS（优惠券）；"
            + "每次只查一个域。返回可引用片段；不要编造未返回的内容，不要跨域引用。")
    public PublicRuleSearchResult searchPublicRules(
            @ToolParam(description = "规则域：POINTS 或 COUPONS") String domain,
            @ToolParam(description = "用户关于该域规则的问题") String question,
            ToolContext context) {
        var callScope = (McpCallScope) context.getContext().get(McpCallScope.CONTEXT_KEY);
        Assert.notNull(callScope, "缺少请求生命周期");
        callScope.check();

        AtomicInteger calls = (AtomicInteger) context.getContext().get(TURN_CALLS_KEY);
        if (calls != null && calls.incrementAndGet() > maxCallsPerTurn) {
            // 仅返回 TEMPORARILY_UNAVAILABLE 时，KNOWLEDGE 走 OpenAiChatModel.internalStream
            // 会无限续轮；必须抛出并由 ToolCallingManager 重抛以终止循环。
            throw new PublicRuleCallLimitException();
        }
        if (Boolean.TRUE.equals(context.getContext().get(LEGACY_SESSION_KEY))) {
            return PublicRuleSearchResult.denied(null, null);
        }
        Object rawUserId = context.getContext().get(USER_ID_KEY);
        if (!(rawUserId instanceof Long) || !StringUtils.hasText(domain) || !StringUtils.hasText(question)) {
            return PublicRuleSearchResult.denied(null, null);
        }
        String normalized = domain.strip().toUpperCase(Locale.ROOT);
        String knowledgeBaseId = DOMAIN_TO_BASE.get(normalized);
        if (knowledgeBaseId == null) {
            return PublicRuleSearchResult.denied(normalized, null);
        }
        String filterExpression = access.filterFor(knowledgeBaseId);
        if (filterExpression == null) {
            return PublicRuleSearchResult.denied(normalized, knowledgeBaseId);
        }
        List<String> allowedDocuments = access.getPublicBases().getOrDefault(knowledgeBaseId, List.of());

        @SuppressWarnings("unchecked")
        Set<String> domainCalls = (Set<String>) context.getContext().get(DOMAIN_CALLS_KEY);
        if (domainCalls != null && !domainCalls.add(normalized)) {
            return unavailable(normalized, knowledgeBaseId);
        }

        try {
            callScope.check();
            var scope = new FilterExpressionTextParser().parse(filterExpression);
            var guard = new RagRequestGuard(() -> {
                callScope.checkActive();
                return true;
            }, Duration.ofSeconds(25));
            var retrieved = pipeline.retrieve(question.strip(), scope, guard);
            callScope.checkActive();
            List<Evidence> evidence = new ArrayList<>();
            for (RagCandidate candidate : retrieved.candidates()) {
                if (evidence.size() >= maxEvidence) break;
                Evidence item = toEvidence(candidate, allowedDocuments, normalized, evidence.size() + 1);
                if (item == null) continue;
                evidence.add(item);
            }
            if (evidence.isEmpty()) {
                return new PublicRuleSearchResult("EMPTY", normalized, knowledgeBaseId, List.of());
            }
            return new PublicRuleSearchResult("OK", normalized, knowledgeBaseId, List.copyOf(evidence));
        } catch (RuntimeException error) {
            if (McpCallScope.isCancellation(error) || RagRequestGuard.terminal(error)) throw error;
            return unavailable(normalized, knowledgeBaseId);
        }
    }

    private static PublicRuleSearchResult unavailable(String domain, String knowledgeBaseId) {
        return new PublicRuleSearchResult("TEMPORARILY_UNAVAILABLE", domain, knowledgeBaseId, List.of());
    }

    private Evidence toEvidence(RagCandidate candidate, List<String> allowedDocuments,
            String domain, int citationOrdinal) {
        var meta = candidate.document().getMetadata();
        Object documentId = meta.get("documentId");
        if (!(documentId instanceof String id) || !StringUtils.hasText(id) || !allowedDocuments.contains(id)) {
            return null;
        }
        String excerpt = candidate.document().getText();
        if (!StringUtils.hasText(excerpt)) {
            return null;
        }
        Object chapter = meta.get("chapter");
        if (!(chapter instanceof String) || !StringUtils.hasText((String) chapter)) {
            chapter = meta.get("headingPath");
        }
        String chapterTitle = chapter instanceof String s && StringUtils.hasText(s) ? s : null;
        Object chunkIndex = meta.get("chunkIndex");
        int index = chunkIndex instanceof Number n ? n.intValue() : 0;
        Object contentHash = meta.get("contentHash");
        String hash = contentHash instanceof String h ? h : null;
        return new Evidence(id, chapterTitle, index, hash, excerpt, domain + "-" + citationOrdinal);
    }

    public record PublicRuleSearchResult(
            String status,
            String domain,
            String knowledgeBaseId,
            List<Evidence> evidence) {
        static PublicRuleSearchResult denied(String domain, String knowledgeBaseId) {
            return new PublicRuleSearchResult("ACCESS_DENIED", domain, knowledgeBaseId, List.of());
        }
    }

    public record Evidence(
            String documentId,
            String chapterTitle,
            int chunkIndex,
            String contentHash,
            String excerpt,
            String citationId) { }
}
