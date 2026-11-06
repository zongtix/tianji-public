package com.tianji.aigc.tools;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 常见课程回答直接组合本轮业务事实；复杂或未支持的问题仍交给模型。 */
public final class CourseFactAnswer {
    private static final Pattern AUDIENCE = Pattern.compile(
            "适用人群|适合.{0,12}(?:基础|什么人|哪些人|谁|学员)|"
            + "(?:需要|要求|具备|什么|哪些).{0,8}(?:前置|基础)|前置(?:条件|要求|基础)|基础(?:要求|条件)|"
            + "(?:两门|这俩).{0,8}(?:要求|门槛)|门槛|零基础能(?:学|听)");
    private static final Pattern DEPTH = Pattern.compile(
            "讲(?:得)?多深|(?:讲解|教学|内容|课程)深度|深不深|深度(?:如何|怎么样)|"
            + "完整.{0,8}覆盖|覆盖.{0,8}完整|(?:是否|有没有|能否).{0,8}(?:讲全|全面覆盖|系统覆盖)");
    private static final Pattern OTHER_DIMENSIONS = Pattern.compile(
            "多少钱|价格|费用|有效期|优惠|购买|退款|订单|推荐|适合我|怎么选|选哪|哪个好|怎么学|怎么用|如何使用|"
            + "讲师|老师|教师|售后|课时|时长|课程介绍|介绍一下|讲什么|学完|收获");
    /** 需要讲义原文依据的问题，不得用目录快捷答吞掉。 */
    private static final Pattern LECTURE_EVIDENCE = Pattern.compile(
            "讲义|资料原文|章节正文|原文依据|缓存击穿|缓存穿透|缓存雪崩|互斥锁");
    private static final Pattern COMPARE = Pattern.compile("区别|差别|对比|比较|两门|两课|这俩");
    private static final Pattern CHOICE = Pattern.compile("哪门|哪个|更贴合|更匹配|更合适|更适合|选哪|怎么选|哪个好");
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9+#.]*");
    private static final Pattern COURSE_DISCOVERY = Pattern.compile("有哪些课程|有什么课程|哪些课程|哪些课|推荐|另找|换一|补充");

    private CourseFactAnswer() { }

    /** 明确的纯课程事实询问直接进入咨询，避免被分到没有课程工具的知识分支。 */
    public static boolean isExplicitCourseFactQuestion(String question) {
        String text = normalize(question);
        return text.contains("课") && !COURSE_DISCOVERY.matcher(text).find() && supportsIntent(text);
    }

    private static boolean requiresLectureEvidence(String text) {
        return LECTURE_EVIDENCE.matcher(text).find();
    }

    private static boolean supportsIntent(String text) {
        if (OTHER_DIMENSIONS.matcher(text).find() || requiresLectureEvidence(text)) return false;
        boolean depth = DEPTH.matcher(text).find();
        return (AUDIENCE.matcher(text).find() || depth)
                && (depth || !text.matches(".*(?:目录|大纲|章节).*"));
    }

    /** 非本轮覆盖的纯事实问题返回 null，继续使用原有模型回答。 */
    static String answer(String question, CourseRecommendations recommendations) {
        return answer(question, recommendations, CourseTools.DetailScope.forQuestion(question));
    }

    static String answer(String question, CourseRecommendations recommendations, CourseTools.DetailScope scope) {
        var facts = recommendations.detailFacts();
        String intent = normalize(question);
        for (var fact : facts) {
            if (StringUtils.hasText(fact.name())) intent = intent.replace(normalize(fact.name()), "");
        }
        if (requiresLectureEvidence(normalize(question)) || requiresLectureEvidence(intent)) return null;
        if (COMPARE.matcher(intent).find() && CHOICE.matcher(intent).find()) return null;
        if (!supportsIntent(intent)) return briefAnswer(question, intent, recommendations, facts, scope);
        boolean audience = AUDIENCE.matcher(intent).find();
        boolean depth = DEPTH.matcher(intent).find();
        if (facts.isEmpty()) {
            return recommendations.hasQueried()
                    ? "本次查询未取得可核对的课程详情，暂时无法确认。"
                    : "本轮尚未查询课程详情，暂时无法核对课程资料。";
        }
        String normalized = normalize(question);
        var named = facts.stream().filter(fact -> StringUtils.hasText(fact.name())
                        && normalized.contains(normalize(fact.name())))
                .sorted(Comparator.comparingInt(fact -> normalized.indexOf(normalize(fact.name())))).toList();
        if (!named.isEmpty()) facts = named;
        boolean compare = COMPARE.matcher(intent).find() || named.size() > 1;
        if ((!compare && facts.size() > 1) || facts.size() > 3) {
            return "本次核实了多门课程，请明确要查询或比较哪门课程。";
        }
        var paragraphs = new ArrayList<String>();
        for (var fact : facts) {
            var parts = new ArrayList<String>();
            if (fact.demo()) {
                parts.add("课程资料标记为演示数据，请勿将其视为真实授课或付款依据。");
            }
            if (audience) {
                parts.add(StringUtils.hasText(fact.usePeople())
                        ? "适用人群（课程资料原文）：" + display(fact.usePeople())
                        : "本次课程资料未提供适用人群，暂时无法确认前置要求。");
            }
            if (depth) parts.add(depthEvidence(question, facts, fact));
            paragraphs.add("《" + (StringUtils.hasText(fact.name()) ? display(fact.name()) : "本次查询的课程")
                    + "》\n" + String.join("\n", parts));
        }
        if (compare && facts.size() < 2) {
            paragraphs.add("目前仅取得一门课程的详情，尚不足以比较两门课程的条件。");
        }
        return String.join("\n\n", paragraphs);
    }

    private static String briefAnswer(String question, String intent, CourseRecommendations recommendations,
                                      List<CourseRecommendations.DetailFacts> facts, CourseTools.DetailScope scope) {
        if (scope.fullContent() || intent.matches(".*(?:多少钱|价格|费用|有效期|优惠|购买|退款|订单|怎么学|怎么用|如何使用|讲师|老师|教师|售后|课时|时长|适合我|哪个好|怎么选|选哪|学完|收获).*")) return null;
        boolean compare = intent.matches(".*(?:区别|差别|对比|比较).*" );
        boolean introduce = intent.matches(".*(?:是啥|是什么|什么课|介绍一下|课程介绍).*" );
        boolean recommendation = recommendations.hasSelectedCourses();
        boolean browse = recommendation && scope.topics().isEmpty();
        String background = statedBackground(question);
        boolean personal = recommendations.hasSelectedCourses() && background != null && !scope.topics().isEmpty();
        if (!compare && !introduce && !recommendation) return null;
        if (facts.isEmpty()) return null;
        if (recommendations.hasSelectedCourses()) {
            var selected = recommendations.snapshot().stream().map(card -> card.getCourseId()).toList();
            facts = facts.stream().filter(fact -> selected.contains(fact.courseId())).toList();
        } else {
            String normalized = normalize(question);
            var named = facts.stream().filter(fact -> StringUtils.hasText(fact.name())
                            && normalized.contains(normalize(fact.name())))
                    .sorted(Comparator.comparingInt(fact -> normalized.indexOf(normalize(fact.name())))).toList();
            if (!named.isEmpty()) facts = named;
        }
        if (facts.isEmpty() || facts.size() > 3 || introduce && !compare && facts.size() != 1) return null;
        var paragraphs = new ArrayList<String>();
        if (browse && intent.contains("热门")) {
            boolean hasPositiveEnrollment = facts.stream().anyMatch(fact -> {
                Integer count = recommendations.enrollmentCount(fact.courseId());
                return count != null && count > 0;
            });
            paragraphs.add(hasPositiveEnrollment ? "平台报名量仅供参考，不代表近期趋势或教学质量。"
                    : "暂缺可靠热度依据，以下课程仅供浏览。");
        } else if (browse) {
            paragraphs.add("以下课程供你浏览比较。");
        } else if (personal) {
            paragraphs.add("按你本轮的学习需求推荐。你说明“" + display(background) + "”。");
        }
        for (var fact : facts) {
            var parts = new ArrayList<String>();
            parts.add((recommendations.hasSelectedCourses() ? "推荐《" : "《") + display(fact.name()) + "》。");
            if (fact.demo()) {
                parts.add("课程资料标记为演示数据，请勿将其视为真实授课或付款依据。");
            }
            String introduction = scope.introduction(fact.detail());
            if (StringUtils.hasText(introduction)) {
                String excerpt = Pattern.compile("[。！？\\n]+").splitAsStream(introduction)
                        .filter(StringUtils::hasText).limit(2).collect(java.util.stream.Collectors.joining("。"));
                if (StringUtils.hasText(excerpt)) parts.add("课程内容摘录：" + display(excerpt) + "。");
            }
            if (StringUtils.hasText(fact.usePeople())) parts.add("适用人群（课程资料原文）：" + display(fact.usePeople()));
            if (personal && fact.catalogue() != null) {
                var titles = fact.catalogue().stream().map(scope::matchingTitle).filter(StringUtils::hasText)
                        .sorted(Comparator.comparing(title -> title.contains("/")))
                        .distinct().limit(2).map(CourseFactAnswer::display).toList();
                if (!titles.isEmpty()) parts.add("与本轮学习目标对应的目录包括：" + String.join("、", titles) + "。");
            }
            if (parts.size() == 1) return null;
            paragraphs.add(String.join("\n", parts));
        }
        if (compare && facts.size() < 2) paragraphs.add("目前仅取得一门课程的详情，尚不足以比较两门课程。");
        return String.join("\n\n", paragraphs);
    }

    private static String statedBackground(String question) {
        var goal = Pattern.compile("(?:想要|想|希望|目标是|打算)").matcher(question);
        if (!goal.find()) return null;
        String prefix = question.substring(0, goal.start()).replaceFirst("[，,。；;\\s]*(?:现在|目前)?$", "").strip();
        return prefix.length() <= 300 && prefix.matches("(?s).*(?:我会|我能|学过|熟悉|熟练|掌握|基础|接触|能用).*")
                && !prefix.matches("(?s).*(?:吗|什么|哪些|是否).*") ? prefix : null;
    }

    private static String depthEvidence(String question, List<CourseRecommendations.DetailFacts> facts,
                                        CourseRecommendations.DetailFacts fact) {
        String text = normalize(question);
        for (var course : facts) {
            if (StringUtils.hasText(course.name())) text = text.replace(normalize(course.name()), "");
        }
        var topics = IDENTIFIER.matcher(text).results().map(result -> result.group()).distinct().toList();
        var titles = fact.catalogue() == null ? List.<String>of() : fact.catalogue().stream()
                .filter(title -> topics.isEmpty() || IDENTIFIER.matcher(normalize(title)).results()
                        .anyMatch(token -> topics.contains(token.group())))
                .distinct().limit(6).toList();
        String evidence;
        if (!titles.isEmpty()) {
            evidence = "当前正式目录标题包括：\n" + String.join("\n", titles.stream().map(title -> "- " + display(title)).toList());
        } else {
            evidence = fact.catalogue() == null || fact.catalogue().isEmpty()
                    ? "本次未取得可核对的目录标题。" : "本次目录标题未匹配到所问主题，这不足以判断课程是否教授该主题。";
        }
        return evidence + "\n目录标题只能确认列出的主题；仅凭这些目录信息，无法确认讲解深度，也无法确认是否完整覆盖。";
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    static String display(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
