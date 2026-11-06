package com.tianji.aigc.tools;

import com.tianji.aigc.knowledge.KnowledgeBaseAccess;
import com.tianji.aigc.knowledge.KnowledgeRetrievalPipeline;
import com.tianji.aigc.knowledge.RagCandidate;
import com.tianji.aigc.knowledge.RagRequestGuard;
import com.tianji.aigc.mcp.McpCallScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicRuleToolsTest {
    private final KnowledgeRetrievalPipeline pipeline = mock(KnowledgeRetrievalPipeline.class);
    private final KnowledgeBaseAccess access = new KnowledgeBaseAccess();
    private PublicRuleTools tools;

    @BeforeEach
    void setUp() {
        access.setPublicBases(Map.of(
                "tianji-points", List.of("tianji-points-guide"),
                "tianji-coupons", List.of("tianji-coupon-guide")));
        tools = new PublicRuleTools(pipeline, access, 3, 3);
    }

    @Test
    void pointsMapsToTianjiPointsAndRetrievesWithGuideFilter() {
        when(pipeline.retrieve(eq("每日签到积分"), any(Filter.Expression.class), any(RagRequestGuard.class)))
                .thenReturn(result(candidate("tianji-points-guide", "签到规则", 1, "hash-p", "每日签到得积分")));

        var result = tools.searchPublicRules("POINTS", "每日签到积分", context(1L));

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.domain()).isEqualTo("POINTS");
        assertThat(result.knowledgeBaseId()).isEqualTo("tianji-points");
        assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().get(0)).satisfies(e -> {
            assertThat(e.documentId()).isEqualTo("tianji-points-guide");
            assertThat(e.chapterTitle()).isEqualTo("签到规则");
            assertThat(e.chunkIndex()).isEqualTo(1);
            assertThat(e.contentHash()).isEqualTo("hash-p");
            assertThat(e.excerpt()).contains("每日签到");
            assertThat(e.citationId()).isEqualTo("POINTS-1");
        });
        verify(pipeline).retrieve(eq("每日签到积分"),
                eq(new FilterExpressionTextParser().parse("documentId in ['tianji-points-guide']")),
                any(RagRequestGuard.class));
    }

    @Test
    void couponsMapsToTianjiCouponsAndRetrievesWithGuideFilter() {
        when(pipeline.retrieve(eq("优惠券怎么用"), any(Filter.Expression.class), any(RagRequestGuard.class)))
                .thenReturn(result(candidate("tianji-coupon-guide", "使用规则", 2, "hash-c", "满减券不可叠加")));

        var result = tools.searchPublicRules("COUPONS", "优惠券怎么用", context(1L));

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.domain()).isEqualTo("COUPONS");
        assertThat(result.knowledgeBaseId()).isEqualTo("tianji-coupons");
        assertThat(result.evidence()).singleElement().satisfies(e -> {
            assertThat(e.documentId()).isEqualTo("tianji-coupon-guide");
            assertThat(e.citationId()).isEqualTo("COUPONS-1");
        });
        verify(pipeline).retrieve(eq("优惠券怎么用"),
                eq(new FilterExpressionTextParser().parse("documentId in ['tianji-coupon-guide']")),
                any(RagRequestGuard.class));
    }

    @Test
    void deniesWhenUserIdMissingWithoutCallingPipeline() {
        var result = tools.searchPublicRules("POINTS", "签到", context(null));

        assertThat(result.status()).isEqualTo("ACCESS_DENIED");
        assertThat(result.evidence()).isEmpty();
        verify(pipeline, never()).retrieve(anyString(), any(), any());
    }

    @Test
    void deniesUnknownDomainWithoutCallingPipeline() {
        var result = tools.searchPublicRules("ORDERS", "订单规则", context(1L));

        assertThat(result.status()).isEqualTo("ACCESS_DENIED");
        assertThat(result.evidence()).isEmpty();
        verify(pipeline, never()).retrieve(anyString(), any(), any());
    }

    @Test
    void deniesLegacySessionWithoutCallingPipeline() {
        Map<String, Object> values = baseContext(1L);
        values.put(PublicRuleTools.LEGACY_SESSION_KEY, Boolean.TRUE);

        var result = tools.searchPublicRules("POINTS", "签到", new ToolContext(values));

        assertThat(result.status()).isEqualTo("ACCESS_DENIED");
        verify(pipeline, never()).retrieve(anyString(), any(), any());
    }

    @Test
    void discardsEvidenceOutsideDomainWhitelistAndReturnsEmptyWhenAllDropped() {
        when(pipeline.retrieve(anyString(), any(), any()))
                .thenReturn(result(candidate("tianji-coupon-guide", "券规则", 1, "hash-x", "优惠券原文不应出现在积分域")));

        var result = tools.searchPublicRules("POINTS", "签到", context(1L));

        assertThat(result.status()).isEqualTo("EMPTY");
        assertThat(result.domain()).isEqualTo("POINTS");
        assertThat(result.knowledgeBaseId()).isEqualTo("tianji-points");
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void fourthCallInSameTurnThrowsCallLimit() {
        when(pipeline.retrieve(anyString(), any(), any()))
                .thenReturn(result(candidate("tianji-points-guide", "签到", 1, "h", "积分")));
        AtomicInteger calls = new AtomicInteger(0);
        Set<String> domains = ConcurrentHashMap.newKeySet();
        ToolContext ctx = context(1L, calls, domains);

        assertThat(tools.searchPublicRules("POINTS", "签到1", ctx).status()).isEqualTo("OK");
        // 同域第二次先耗尽域预算；换域再调可继续消耗总预算
        assertThat(tools.searchPublicRules("POINTS", "签到2", ctx).status())
                .isEqualTo("TEMPORARILY_UNAVAILABLE");
        assertThat(tools.searchPublicRules("COUPONS", "券1", ctx).status()).isIn("OK", "EMPTY");
        when(pipeline.retrieve(anyString(), any(), any()))
                .thenReturn(result()); // 不会走到：总预算已满前再凑一次
        // 预置到第 3 次已用完：再调第 4 次必须终止模型循环
        calls.set(3);
        assertThatThrownBy(() -> tools.searchPublicRules("COUPONS", "券2", ctx))
                .isInstanceOf(PublicRuleCallLimitException.class);
        verify(pipeline, times(2)).retrieve(anyString(), any(), any());
    }

    @Test
    void secondCallSameDomainIsTemporarilyUnavailable() {
        when(pipeline.retrieve(anyString(), any(), any()))
                .thenReturn(result(candidate("tianji-points-guide", "签到", 1, "h", "积分")));
        ToolContext ctx = context(1L);

        assertThat(tools.searchPublicRules("POINTS", "第一次", ctx).status()).isEqualTo("OK");
        var second = tools.searchPublicRules("POINTS", "第二次", ctx);

        assertThat(second.status()).isEqualTo("TEMPORARILY_UNAVAILABLE");
        verify(pipeline, times(1)).retrieve(anyString(), any(), any());
    }

    private ToolContext context(Long userId) {
        return context(userId, new AtomicInteger(0), ConcurrentHashMap.newKeySet());
    }

    private ToolContext context(Long userId, AtomicInteger calls, Set<String> domains) {
        return new ToolContext(baseContext(userId, calls, domains));
    }

    private Map<String, Object> baseContext(Long userId) {
        return baseContext(userId, new AtomicInteger(0), ConcurrentHashMap.newKeySet());
    }

    private Map<String, Object> baseContext(Long userId, AtomicInteger calls, Set<String> domains) {
        Map<String, Object> values = new HashMap<>();
        values.put(McpCallScope.CONTEXT_KEY, new McpCallScope(() -> true, () -> true));
        values.put(PublicRuleTools.TURN_CALLS_KEY, calls);
        values.put(PublicRuleTools.DOMAIN_CALLS_KEY, domains);
        if (userId != null) {
            values.put(PublicRuleTools.USER_ID_KEY, userId);
        }
        return values;
    }

    private KnowledgeRetrievalPipeline.Result result(RagCandidate... candidates) {
        return new KnowledgeRetrievalPipeline.Result(List.of(candidates), List.of(), "not_executed");
    }

    private RagCandidate candidate(String documentId, String chapter, int chunk, String hash, String text) {
        var meta = new HashMap<String, Object>();
        meta.put("documentId", documentId);
        meta.put("chapter", chapter);
        meta.put("chunkIndex", chunk);
        meta.put("contentHash", hash);
        return new RagCandidate(new Document("id-" + documentId + chunk, text, meta),
                documentId + ":" + hash + ":" + chunk, Map.of(), 1.0, null);
    }
}
