package com.tianji.aigc.tools;

import com.tianji.aigc.knowledge.RagCandidate;
import com.tianji.aigc.knowledge.RagRequestGuard;
import com.tianji.aigc.knowledge.material.CourseMaterialAccess;
import com.tianji.aigc.knowledge.material.CourseMaterialSearch;
import com.tianji.aigc.mcp.McpCallScope;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 课程讲义检索工具；仅 CONSULT 白名单开放。授权与范围一律服务端决定。 */
@Component
@ConditionalOnProperty(prefix = "tj.ai", name = {"knowledge.enabled", "course-materials.enabled"}, havingValue = "true")
@ConditionalOnBean(CourseMaterialSearch.class)
public class CourseMaterialTools {
    public static final String USER_ID_KEY = "courseMaterialUserId";
    public static final String TURN_CALLS_KEY = "courseMaterialTurnCalls";

    private final CourseMaterialSearch materialSearch;
    private final CourseMaterialAccess access;
    private final int maxCallsPerTurn;
    private final int maxEvidence;

    /** Spring 注入入口；双构造器时必须标明，否则会落到无参构造并启动失败。 */
    @Autowired
    public CourseMaterialTools(CourseMaterialSearch materialSearch,
            @Value("${tj.ai.course-materials.max-calls-per-turn:2}") int maxCallsPerTurn,
            @Value("${tj.ai.course-materials.max-evidence:3}") int maxEvidence) {
        this(materialSearch, CourseMaterialAccess.fromClasspath("course-materials/manifest.json"),
                maxCallsPerTurn, maxEvidence);
    }

    /** 测试可注入假 access；生产勿直接调用。 */
    public CourseMaterialTools(CourseMaterialSearch materialSearch, CourseMaterialAccess access,
            int maxCallsPerTurn, int maxEvidence) {
        this.materialSearch = materialSearch;
        this.access = access;
        this.maxCallsPerTurn = maxCallsPerTurn;
        this.maxEvidence = maxEvidence;
    }

    @Tool(description = "检索已发布课程讲义片段。courseId 仅用于定位，不是授权证明。返回可引用的原文片段；不要编造未返回的内容。")
    public MaterialSearchResult searchCourseMaterials(
            @ToolParam(description = "待核验的课程 ID") Long courseId,
            @ToolParam(description = "用户关于讲义内容的问题") String question,
            ToolContext context) {
        var callScope = (McpCallScope) context.getContext().get(McpCallScope.CONTEXT_KEY);
        Assert.notNull(callScope, "缺少请求生命周期");
        callScope.check();
        Object rawUserId = context.getContext().get(USER_ID_KEY);
        if (!(rawUserId instanceof Long userId) || courseId == null || !StringUtils.hasText(question)) {
            return MaterialSearchResult.denied();
        }
        var decision = access.authorize(userId, String.valueOf(courseId));
        if (decision.status() != CourseMaterialAccess.Status.OK) {
            return MaterialSearchResult.denied();
        }
        AtomicInteger calls = (AtomicInteger) context.getContext().get(TURN_CALLS_KEY);
        if (calls != null && calls.incrementAndGet() > maxCallsPerTurn) {
            return new MaterialSearchResult("TEMPORARILY_UNAVAILABLE", decision.courseId(),
                    decision.courseName(), decision.materials().isEmpty() ? null
                    : "demo-v1", List.of());
        }
        try {
            callScope.check();
            var scope = new FilterExpressionTextParser().parse(decision.filterExpression());
            var guard = new RagRequestGuard(() -> {
                callScope.checkActive();
                return true;
            }, Duration.ofSeconds(25));
            var retrieved = materialSearch.search(question.strip(), scope, guard);
            callScope.checkActive();
            List<Evidence> evidence = new ArrayList<>();
            for (RagCandidate candidate : retrieved.candidates()) {
                if (evidence.size() >= maxEvidence) break;
                Evidence item = toEvidence(candidate, evidence.size() + 1);
                if (item == null) continue;
                evidence.add(item);
            }
            if (evidence.isEmpty()) {
                return new MaterialSearchResult("EMPTY", decision.courseId(), decision.courseName(),
                        "demo-v1", List.of());
            }
            return new MaterialSearchResult("OK", decision.courseId(), decision.courseName(),
                    "demo-v1", List.copyOf(evidence));
        } catch (RuntimeException error) {
            if (McpCallScope.isCancellation(error) || RagRequestGuard.terminal(error)) throw error;
            return new MaterialSearchResult("TEMPORARILY_UNAVAILABLE", decision.courseId(),
                    decision.courseName(), "demo-v1", List.of());
        }
    }

    private Evidence toEvidence(RagCandidate candidate, int citationOrdinal) {
        var meta = candidate.document().getMetadata();
        Object materialId = meta.get("materialId");
        Object heading = meta.get("headingPath");
        Object chunkIndex = meta.get("chunkIndex");
        Object contentHash = meta.get("contentHash");
        String excerpt = candidate.document().getText();
        if (!(materialId instanceof String id) || !StringUtils.hasText(id)
                || !(heading instanceof String chapter) || !(chunkIndex instanceof Number index)
                || !(contentHash instanceof String hash) || !StringUtils.hasText(excerpt)) {
            return null;
        }
        return new Evidence(id, chapter, index.intValue(), hash, excerpt,
                "C" + citationOrdinal);
    }

    public record MaterialSearchResult(
            String status,
            String courseId,
            String courseName,
            String materialVersion,
            List<Evidence> evidence) {
        static MaterialSearchResult denied() {
            return new MaterialSearchResult("ACCESS_DENIED", null, null, null, List.of());
        }
    }

    public record Evidence(
            String materialId,
            String chapterTitle,
            int chunkIndex,
            String contentHash,
            String excerpt,
            String citationId) { }
}
