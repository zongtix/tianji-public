package com.tianji.aigc.tools;

import com.tianji.aigc.constants.Constant;
import com.tianji.aigc.mcp.McpCallScope;
import com.tianji.aigc.knowledge.course.CourseSemanticSearch;
import com.tianji.aigc.tools.result.CourseCard;
import com.tianji.aigc.tools.result.CourseInfo;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.api.dto.course.CourseBaseInfoDTO;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class CourseTools {
    public static final String DETAIL_SCOPE = "courseDetailScope";
    /** 与 PublicRuleTools 相同：计数器放在每回合 toolContext，避免单例 CourseTools 跨轮累计。 */
    public static final String TURN_CALLS_KEY = "courseToolTurnCalls";
    /**
     * 单回合对外工具入口上限。选 8：对齐 MAX_TOOL_ROUNDS，覆盖 browse/search + 多门详情 + recommend
     * 的合法路径，同时硬挡住单响应多 tool_calls 放大（8 轮 × N）。比 PublicRule(3)/讲义(2) 宽，因课程链路多步。
     */
    public static final int MAX_CALLS_PER_TURN = 8;
    private final CourseClient courseClient;
    private final SearchClient searchClient;
    private final CourseSemanticSearch semanticSearch;
    private final CourseDetailReader detailReader;
    private final int maxCallsPerTurn;

    public CourseTools(CourseClient courseClient, SearchClient searchClient) {
        this(courseClient, searchClient, null);
    }

    public CourseTools(CourseClient courseClient, SearchClient searchClient, @Nullable CourseSemanticSearch semanticSearch) {
        this(courseClient, searchClient, semanticSearch, (id, scope) -> courseClient.getCourseBaseInfoById(id));
    }

    @Autowired
    public CourseTools(CourseClient courseClient, SearchClient searchClient, @Nullable CourseSemanticSearch semanticSearch,
                       CourseDetailReader detailReader) {
        this(courseClient, searchClient, semanticSearch, detailReader, MAX_CALLS_PER_TURN);
    }

    public CourseTools(CourseClient courseClient, SearchClient searchClient, @Nullable CourseSemanticSearch semanticSearch,
                       CourseDetailReader detailReader, int maxCallsPerTurn) {
        this.courseClient = courseClient;
        this.searchClient = searchClient;
        this.semanticSearch = semanticSearch;
        this.detailReader = detailReader;
        this.maxCallsPerTurn = maxCallsPerTurn;
    }

    public boolean supportsSemanticSearch() { return semanticSearch != null; }

    @Tool(description = "浏览平台课程，按平台记录的报名量降序返回最多10门非演示在售候选及最新详情，需按资料完整度筛选后再推荐。用于热门推荐、随便看看、没有学习目标或浏览免费课；不要将热门、推荐、随便看看作为关键词。报名量不是近期或全网热度；hasEnrollmentData=false 时只能称为课程浏览，不能声称热门榜。此操作不展示卡片，确认后调用 recommendCourses。")
    public BrowseResult browseCourses(
            @ToolParam(description = "是否只看免费课：true 只看免费，false 只看付费，未限制传 null", required = false) Boolean free,
            @ToolParam(description = "用户明确指定的课程方向，例如 Java；没有方向传 null，不传热门或推荐等泛化词", required = false) String keyword,
            ToolContext context) {
        consumeTurnBudget(context);
        CourseRecommendations recommendations = CourseRecommendations.from(context);
        if (keyword != null && keyword.length() > 100) return new BrowseResult(List.of(), false);
        recommendations.markQueried();
        var found = searchClient.queryPopularCourses(free, StringUtils.hasText(keyword) ? keyword.trim() : null);
        if (found == null || found.isEmpty()) return new BrowseResult(List.of(), false);
        var counts = new LinkedHashMap<Long, Integer>();
        found.stream().filter(value -> value != null && value.getId() != null && value.getId() > 0)
                .limit(20).forEach(value -> counts.putIfAbsent(value.getId(), value.getSold()));
        if (counts.isEmpty()) return new BrowseResult(List.of(), false);
        var available = availableCourses(List.copyOf(counts.keySet()));
        List<Long> ids = counts.keySet().stream().filter(available::containsKey)
                .filter(id -> free == null || free.equals(available.get(id).getFree()))
                .filter(id -> !isDemo(available.get(id).getName())).toList();
        if (ids.isEmpty()) return new BrowseResult(List.of(), false);
        Map<Long, CourseBaseInfoDTO> details = new HashMap<>();
        var values = courseClient.getBaseInfoBatch(ids);
        if (values != null) {
            for (var detail : values) {
                if (detail != null && ids.contains(detail.getId())) details.put(detail.getId(), detail);
            }
        }
        var candidates = ids.stream().filter(details::containsKey)
                .filter(id -> !isDemo(details.get(id).getName()) && !isDemo(details.get(id).getDetail()))
                .limit(10).map(id -> new BrowseCandidate(card(available.get(id), null),
                        modelInfo(details.get(id), context), counts.get(id))).toList();
        recommendations.addCandidates(candidates.stream().map(BrowseCandidate::course).toList());
        var accepted = candidates.stream().filter(value -> recommendations.contains(value.course().getCourseId())).toList();
        accepted.forEach(value -> {
            recommendations.markDetailVerified(value.course().getCourseId());
            recommendations.rememberDetail(details.get(Long.valueOf(value.course().getCourseId())), isDemo(value.course().getName()));
            recommendations.rememberEnrollment(value.course().getCourseId(), value.enrollmentCount());
        });
        return new BrowseResult(accepted, accepted.stream()
                .anyMatch(value -> value.enrollmentCount() != null && value.enrollmentCount() > 0));
    }

    static boolean isDemo(String value) {
        return value != null && (value.contains("【AI演示】") || value.contains("AI联调演示数据")
                || value.contains("不提供真实授课"));
    }

    @Tool(description = Constant.Tools.QUERY_COURSE_BY_ID)
    public CourseInfo queryCourseById(@ToolParam(description = Constant.ToolParams.COURSE_ID) Long courseId,
                                     ToolContext context) {
        consumeTurnBudget(context);
        if (courseId == null || courseId <= 0) return null;
        var callScope = (McpCallScope) context.getContext().get(McpCallScope.CONTEXT_KEY);
        detailReader.check(callScope);
        var recommendations = CourseRecommendations.from(context);
        recommendations.markQueried();
        recommendations.invalidateDetail(courseId.toString());
        var detail = detailReader.read(courseId, callScope);
        detailReader.check(callScope);
        if (detail == null || !courseId.equals(detail.getId())) return null;
        CourseInfo info = modelInfo(detail, context);
        var course = availableCourses(List.of(courseId)).get(courseId);
        detailReader.check(callScope);
        if (course != null && !isDemo(course.getName()) && !isDemo(detail.getName()) && !isDemo(detail.getDetail())) {
            recommendations.addDetailCard(card(course, null));
        }
        detailReader.check(callScope);
        recommendations.markDetailVerified(courseId.toString());
        recommendations.rememberDetail(detail, course != null && isDemo(course.getName()));
        return info;
    }

    @Tool(description = "按课程关键词搜索候选课程，最多返回5门。此操作不展示卡片；确认推荐后调用 recommendCourses。可调用 queryCourseById 核实课程介绍。")
    public List<CourseCard> searchCourses(@ToolParam(description = "课程关键词，例如 Java、Spring；不要传入整段用户问题") String keyword,
                                           ToolContext context) {
        consumeTurnBudget(context);
        CourseRecommendations recommendations = CourseRecommendations.from(context);
        if (!StringUtils.hasText(keyword) || keyword.length() > 100) return List.of();
        recommendations.markQueried();
        List<Long> found = searchClient.queryCoursesIdByName(keyword.trim());
        if (found == null) return List.of();
        List<Long> ids = found.stream().filter(id -> id != null && id > 0).distinct().limit(10).toList();
        if (ids.isEmpty()) return List.of();
        Map<Long, CourseSimpleInfoDTO> courses = availableCourses(ids);
        List<CourseCard> candidates = ids.stream().filter(courses::containsKey).limit(5)
                .map(id -> card(courses.get(id), null)).toList();
        recommendations.addCandidates(candidates);
        return candidates.stream().filter(value -> recommendations.contains(value.getCourseId())).toList();
    }

    @Tool(description = "按用户的学习目标、已有基础或遇到的问题查找课程，最多返回5门候选及最新课程详情。保留用户的具体需求，不要缩减成课程名称关键词。此操作不展示卡片，确认后调用 recommendCourses。")
    public List<SemanticCandidate> searchCoursesByGoal(
            @ToolParam(description = "完整学习需求，例如：已经会 Java 基础，想解决多个请求同时扣库存的问题") String goal,
            ToolContext context) {
        consumeTurnBudget(context);
        CourseRecommendations recommendations = CourseRecommendations.from(context);
        if (!StringUtils.hasText(goal) || goal.length() > 1000) return List.of();
        if (semanticSearch == null) throw new IllegalStateException("课程语义检索尚未启用");
        recommendations.markQueried();
        try {
            List<Long> ids = semanticSearch.search(goal);
            if (ids.isEmpty()) return List.of();
            Map<Long, CourseSimpleInfoDTO> courses = availableCourses(ids);
            List<Long> availableIds = ids.stream().filter(courses::containsKey).toList();
            if (availableIds.isEmpty()) return List.of();
            Map<Long, CourseBaseInfoDTO> details = new HashMap<>();
            for (var detail : courseClient.getBaseInfoBatch(availableIds)) {
                if (detail != null) details.put(detail.getId(), detail);
            }
            List<SemanticCandidate> candidates = availableIds.stream().filter(details::containsKey)
                    .map(id -> new SemanticCandidate(card(courses.get(id), null), modelInfo(details.get(id), context)))
                    .toList();
            recommendations.addCandidates(candidates.stream().map(SemanticCandidate::course).toList());
            var accepted = candidates.stream().filter(value -> recommendations.contains(value.course().getCourseId())).toList();
            accepted.forEach(value -> {
                recommendations.markDetailVerified(value.course().getCourseId());
                recommendations.rememberDetail(details.get(Long.valueOf(value.course().getCourseId())), isDemo(value.course().getName()));
            });
            return accepted;
        } catch (RuntimeException error) {
            if (error instanceof CourseSemanticSearch.RetrievalException) throw error;
            throw new CourseSemanticSearch.RetrievalException(error);
        }
    }

    @Tool(description = "提交最终课程推荐并展示卡片。必须先 browseCourses、searchCourses 或 searchCoursesByGoal，仅可选择当前请求找到且已返回详情的课程；searchCourses 的关键词候选须先 queryCourseById 核实，未取得详情的课程会被排除。最多3门。卡片说明由系统引用本轮课程适用人群原文生成，reason不作为展示依据；个性化解释留在正文，不改写前置熟练程度。重复调用会替换本次最终推荐。")
    public List<CourseCard> recommendCourses(@ToolParam(description = "最终推荐的课程ID及推荐理由，最多3门") List<Recommendation> selections,
                                             ToolContext context) {
        consumeTurnBudget(context);
        CourseRecommendations recommendations = CourseRecommendations.from(context);
        Map<Long, String> selected = new LinkedHashMap<>();
        if (selections != null) {
            for (Recommendation selection : selections) {
                if (selection == null || selection.courseId() == null
                        || !recommendations.canRecommend(selection.courseId())) continue;
                var facts = recommendations.detailFacts().stream()
                        .filter(fact -> selection.courseId().equals(fact.courseId())).findFirst().orElse(null);
                if (facts == null || facts.demo()) continue;
                try {
                    long id = Long.parseLong(selection.courseId());
                    selected.putIfAbsent(id, recommendations.cardReason(selection.courseId()));
                    if (selected.size() == 3) break;
                } catch (NumberFormatException ignored) {
                    // 非法 ID 不会成为课程卡片。
                }
            }
        }
        if (selected.isEmpty()) return recommendations.select(List.of());
        // 再次核实课程状态及价格，避免推荐已下架课程或沿用过期搜索数据。
        Map<Long, CourseSimpleInfoDTO> courses = availableCourses(List.copyOf(selected.keySet()));
        var callScope = (McpCallScope) context.getContext().get(McpCallScope.CONTEXT_KEY);
        var result = new ArrayList<CourseCard>();
        for (var entry : selected.entrySet()) {
            var course = courses.get(entry.getKey());
            if (course == null) {
                recommendations.invalidateDetail(entry.getKey().toString());
                continue;
            }
            if (isDemo(course.getName())) {
                recommendations.markDemo(entry.getKey().toString());
                continue;
            }
            var previous = recommendations.detailFacts().stream()
                    .filter(fact -> entry.getKey().toString().equals(fact.courseId())).findFirst().orElse(null);
            detailReader.check(callScope);
            CourseBaseInfoDTO detail;
            try {
                detail = detailReader.read(entry.getKey(), callScope);
            } catch (RuntimeException error) {
                recommendations.invalidateDetail(entry.getKey().toString());
                throw error;
            }
            detailReader.check(callScope);
            if (detail == null || !entry.getKey().equals(detail.getId()) || previous == null
                    || !previous.equals(CourseRecommendations.detailFacts(detail, false))) {
                recommendations.invalidateDetail(entry.getKey().toString());
                if (detail != null && entry.getKey().equals(detail.getId())) recommendations.rememberDetail(detail);
                continue;
            }
            result.add(card(course, entry.getValue()));
        }
        return recommendations.select(result);
    }

    /** 仅对外 @Tool 入口计数；内部 availableCourses/modelInfo/detailReader 不重复扣额度。缓存命中与远程调用同等计 1 次入口。 */
    private void consumeTurnBudget(ToolContext context) {
        AtomicInteger calls = (AtomicInteger) context.getContext().get(TURN_CALLS_KEY);
        if (calls != null && calls.incrementAndGet() > maxCallsPerTurn) {
            throw new CourseToolCallLimitException();
        }
    }

    private Map<Long, CourseSimpleInfoDTO> availableCourses(List<Long> ids) {
        List<CourseSimpleInfoDTO> values = courseClient.getSimpleInfoList(ids);
        Map<Long, CourseSimpleInfoDTO> result = new LinkedHashMap<>();
        if (values == null) return result;
        LocalDateTime now = LocalDateTime.now();
        for (CourseSimpleInfoDTO value : values) {
            if (value == null || !ids.contains(value.getId())) continue;
            if (!Integer.valueOf(2).equals(value.getStatus()) && !Integer.valueOf(4).equals(value.getStatus())) continue;
            if (value.getPurchaseStartTime() != null && value.getPurchaseStartTime().isAfter(now)) continue;
            if (value.getPurchaseEndTime() != null && !value.getPurchaseEndTime().isAfter(now)) continue;
            if (!StringUtils.hasText(value.getName())) continue;
            if (!Boolean.TRUE.equals(value.getFree()) && (value.getPrice() == null || value.getPrice() < 0)) continue;
            result.put(value.getId(), value);
        }
        return result;
    }

    private CourseCard card(CourseSimpleInfoDTO value, String reason) {
        return new CourseCard(value.getId().toString(), value.getName(), value.getCoverUrl(),
                Boolean.TRUE.equals(value.getFree()) ? 0 : value.getPrice(), value.getValidDuration(), reason);
    }

    private CourseInfo modelInfo(CourseBaseInfoDTO detail, ToolContext context) {
        CourseInfo info = CourseInfo.of(detail);
        if (!(context.getContext().get(DETAIL_SCOPE) instanceof DetailScope scope) || scope.fullContent()) return info;
        var selected = new ScopedCourseInfo();
        selected.setId(info.getId()); selected.setName(info.getName());
        selected.setPrice(info.getPrice()); selected.setValidDuration(info.getValidDuration());
        selected.setUsePeople(info.getUsePeople());
        selected.setDetail(scope.introduction(info.getDetail()));
        if (scope.topics().isEmpty() && info.getCatalogue() != null) {
            selected.overviewTopics = info.getCatalogue().stream().filter(StringUtils::hasText)
                    .filter(title -> !title.contains("/")).distinct().limit(2).toList();
        }
        var notExpanded = new ArrayList<String>();
        var partiallyExpanded = new ArrayList<String>();
        if (!scope.topics().isEmpty() && info.getCatalogue() != null) {
            selected.setCatalogue(info.getCatalogue().stream().map(scope::matchingTitle)
                    .filter(StringUtils::hasText).distinct().toList());
        }
        if (info.getCatalogue() != null && !info.getCatalogue().isEmpty()) {
            if (selected.getCatalogue() == null || selected.getCatalogue().isEmpty()) {
                notExpanded.add("catalogue");
            } else if (!selected.getCatalogue().equals(info.getCatalogue())) {
                partiallyExpanded.add("catalogue");
            }
        }
        if (StringUtils.hasText(info.getDetail())) {
            if (!StringUtils.hasText(selected.getDetail())) {
                notExpanded.add("detail");
            } else if (!info.getDetail().equals(selected.getDetail())) {
                partiallyExpanded.add("detail");
            }
        }
        selected.notExpanded = List.copyOf(notExpanded);
        selected.partiallyExpanded = List.copyOf(partiallyExpanded);
        return selected;
    }

    /** 范围标记仅说明本轮投影；源资料为空时不标记为裁剪。卡片始终从原始业务事实生成。 */
    public static class ScopedCourseInfo extends CourseInfo {
        private List<String> notExpanded = List.of();
        private List<String> partiallyExpanded = List.of();
        private List<String> overviewTopics = List.of();

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public List<String> getNotExpanded() { return notExpanded; }
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public List<String> getPartiallyExpanded() { return partiallyExpanded; }
        /** 只提供用于认识课程主题的章标题摘录，不代表完整目录。 */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public List<String> getOverviewTopics() { return overviewTopics; }
        @Override
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public List<String> getCatalogue() { return super.getCatalogue(); }
        @Override
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public String getDetail() { return super.getDetail(); }
    }

    /** 请求范围由用户原话决定，不接受模型生成的搜索参数作为扩展资料范围的依据。 */
    public record DetailScope(boolean fullContent, List<String> topics) {
        private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9+#.]*");
        private static final Pattern HEADING = Pattern.compile("(?is)<h[1-6]\\b[^>]*>(.*?)</h[1-6]>");
        private static final Pattern FULL_CONTENT = Pattern.compile(
                "目录|章节|大纲|多深|深度|深入|完整(?!的?(?:课程)?名(?:称)?)|全面|系统性|系统地|覆盖|实战|"
                + "(?:是否|有没有|有无|会不会).{0,12}(?:教|讲|学|包含|涉及)|教不教|讲不讲|学不学|"
                + "(?:教|讲|包含|涉及).{0,30}(?:吗|么|没|不)|\\b(?:syllabus|curriculum|depth|coverage)\\b",
                Pattern.CASE_INSENSITIVE);

        public static DetailScope forQuestion(String question) {
            String text = question == null ? "" : question;
            if (FULL_CONTENT.matcher(text).find()) return new DetailScope(true, List.of());
            if (text.matches("(?s).*(?:是啥|是什么|什么课|区别|方向|怎么选|哪个好|适合我吗|介绍一下|多少钱|有效期).*")) {
                return new DetailScope(false, List.of());
            }
            // 已有基础用于匹配适用人群；学习目标决定本轮需要哪些教学内容。
            var goal = Pattern.compile("(?:想要|想|希望|目标是|打算)(.+)").matcher(text);
            if (goal.find()) text = goal.group(1);
            text = normalize(text).replaceAll("(?:按我的情况|按这个|按刚才|根据这些|按照这些|照这个|继续|"
                    + "有哪些|有没有|有无|热门|普通|免费|付费|量身定制|适合我|随便|看看|什么|不知道|不清楚|没想好|"
                    + "我|现在|想要|学习|了解|学|补|请|帮我|给我|推荐|[一二两三四五六七八九十几\\d]+门|一些|课程?|做网页|做页面|相关|基础|知识)", " ");
            var topics = new ArrayList<String>();
            var tokens = Pattern.compile("[a-z][a-z0-9+#.]*|[\\p{IsHan}]{2,}").matcher(text);
            while (tokens.find()) topics.add(tokens.group());
            return new DetailScope(false, List.copyOf(topics));
        }

        private static String normalize(String text) {
            return text.toLowerCase(Locale.ROOT).replaceAll("spring\\s+(boot|cloud)", "spring$1");
        }

        boolean matches(String value) {
            if (value == null) return false;
            String text = normalize(value);
            var identifiers = IDENTIFIER.matcher(text).results().map(result -> result.group()).toList();
            return topics.stream().anyMatch(topic -> topic.matches("[a-z].*")
                    ? identifiers.contains(topic) : text.contains(topic));
        }

        // Prefer the original title. If an unrelated parent masks a matching child,
        // expose only the child text, which is still an exact part of the source title.
        String matchingTitle(String value) {
            if (!StringUtils.hasText(value)) return null;
            if (matches(value) && hasOnlyRequestedIdentifiers(value)) return value;
            int separator = value.lastIndexOf('/');
            if (separator < 0) return null;
            String child = value.substring(separator + 1).strip();
            return matches(child) && hasOnlyRequestedIdentifiers(child) ? child : null;
        }

        boolean hasOnlyRequestedIdentifiers(String value) {
            return topics.stream().noneMatch(topic -> topic.matches("[a-z].*"))
                    || IDENTIFIER.matcher(normalize(value)).results().allMatch(token -> topics.contains(token.group()));
        }

        String introduction(String html) {
            if (!StringUtils.hasText(html)) return html;
            var sections = new ArrayList<String>();
            var headings = HEADING.matcher(html);
            int start = 0;
            String heading = "";
            while (headings.find()) {
                addSection(sections, heading, html.substring(start, headings.start()));
                heading = headings.group(1).replaceAll("<[^>]*>", "");
                start = headings.end();
            }
            addSection(sections, heading, html.substring(start));
            String result = String.join("\n", sections);
            return result.isBlank() ? null : result;
        }

        private void addSection(List<String> sections, String heading, String html) {
            if (heading.matches(".*(?:目录|章节|资料范围|资料边界|深度|覆盖|实战|前置基础).*")) return;
            if (topics.isEmpty() && heading.matches(".*(?:声明|其他内容).*")) return;
            String text = html.replaceAll("(?is)<(?:ul|ol|table)\\b[^>]*>.*?</(?:ul|ol|table)>", "")
                    .replaceAll("(?i)</(?:p|div)>|<br\\s*/?>", "\n").replaceAll("<[^>]*>", "")
                    .replace("&nbsp;", " ").replace("&amp;", "&");
            String selected = Pattern.compile("[。！？；;\\n]+").splitAsStream(text).map(String::strip)
                    .filter(StringUtils::hasText)
                    .filter(part -> !part.matches(".*(?:深度|深入|完整|系统性|实战|后续章节|待.{0,8}核对|未.{0,12}目录|目录.{0,12}未).*"))
                    .filter(part -> !part.matches(".*(?:未细化|没有细化|未明确|未说明|未提供|资料不足).*"))
                    .filter(part -> topics.isEmpty() || matches(part))
                    // 混合主题的句子整体省略，不把删词后的残句当作新的课程声明。
                    .filter(this::hasOnlyRequestedIdentifiers)
                    .collect(Collectors.joining("。"));
            if (!selected.isBlank()) sections.add(selected);
        }
    }

    public record Recommendation(
            @ToolParam(description = "搜索结果中的课程ID字符串") String courseId,
            @ToolParam(description = "兼容旧调用的理由字段，不直接展示；卡片说明由系统引用本轮适用人群原文生成", required = false) String reason) {
    }

    public record SemanticCandidate(CourseCard course, CourseInfo info) { }

    public record BrowseCandidate(CourseCard course, CourseInfo info, Integer enrollmentCount) { }

    public record BrowseResult(List<BrowseCandidate> courses, boolean hasEnrollmentData) { }
}
