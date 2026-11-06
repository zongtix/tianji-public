package com.tianji.aigc.tools;

import com.tianji.aigc.tools.result.CourseCard;
import com.tianji.api.dto.course.CourseBaseInfoDTO;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/** 每次订阅创建一个实例，通过 ToolContext 传递，不共享跨请求状态。 */
public class CourseRecommendations {
    public static final String CONTEXT_KEY = "courseRecommendations";
    public static final String INTAKE_STATE = "courseIntakeState";
    private final Map<String, CourseCard> candidates = new LinkedHashMap<>();
    private final Map<String, CourseCard> detailCards = new LinkedHashMap<>();
    private final Set<String> verifiedDetails = new HashSet<>();
    private final Map<String, DetailFacts> detailFacts = new LinkedHashMap<>();
    private List<CourseCard> cards = List.of();
    private boolean selected;
    private boolean queried;
    private boolean closed;
    private int intakeState;
    private boolean statedBasics;
    private boolean statedGoal;
    private boolean rememberedBasics;
    private boolean rememberedGoal;
    private boolean hadPriorCards;
    private String intakeReply;
    private final Map<String, Integer> enrollmentCounts = new LinkedHashMap<>();

    /** 只恢复服务端成功保存的标记；助手文字和用户自称的状态不参与计数。 */
    public synchronized void restoreIntake(List<Message> history) {
        if (closed || intakeReply != null) return;
        intakeState = 0;
        statedBasics = rememberedBasics;
        statedGoal = rememberedGoal;
        hadPriorCards = false;
        boolean restored = false;
        boolean previousRecommendation = false;
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if (message.getMessageType() == MessageType.USER) {
                String text = message.getText() == null ? "" : message.getText();
                statedBasics |= text.matches("(?s).*(?:我|本人)(?:已经|已|目前|现在)?(?:不会(?!选课|选择课程|挑选课程)|会|能用|学过|熟练|熟悉|掌握|没学过|没接触|有.{0,8}基础).*" )
                        || text.matches("(?s).*(?:我是零基础|我没有.{0,8}基础).*" );
                if (!previousRecommendation) statedGoal |= text.matches("(?s).*(?:想|希望|打算)(?:要)?(?:学|做|开发|解决|用|实现).{2,}")
                        && !text.matches("(?s).*(?:不知道|不清楚|没想好|学什么|做什么).*");
            }
            if (message.getMessageType() != MessageType.ASSISTANT) continue;
            Object cards = message.getMetadata().get(com.tianji.aigc.memory.MessageUtil.COURSE_CARDS);
            if (cards instanceof List<?> values && !values.isEmpty()) hadPriorCards = true;
            Object value = message.getMetadata().get(INTAKE_STATE);
            if (!restored && value instanceof Number number && number.doubleValue() == number.intValue()
                    && number.intValue() >= 0 && number.intValue() <= 3) {
                intakeState = number.intValue();
                restored = true;
            }
            if (value instanceof Number number && number.doubleValue() == 0) previousRecommendation = true;
        }
    }

    public synchronized int intakeQuestions() { return Integer.bitCount(intakeState); }

    /** 用户自己管理的跨会话背景可用于避免重复询问；不改变课程查询或推荐的事实来源。 */
    public synchronized void restoreKnownFacts(boolean basics, boolean goal) {
        if (closed || intakeReply != null) return;
        rememberedBasics |= basics;
        rememberedGoal |= goal;
        statedBasics |= basics;
        statedGoal |= goal;
    }

    /** 同一请求幂等；最多完成两次提问，然后给出信息不足的结论，不继续换词追问。 */
    public synchronized String nextIntakeReply() {
        if (intakeReply != null) return intakeReply;
        if (statedBasics && statedGoal) {
            intakeReply = "你已提供基础和学习目标，但本次尚未完成课程查询，暂时不能给出有依据的个性化推荐。";
            return intakeReply;
        }
        if (!statedBasics && (intakeState & 1) == 0) return nextIntakeReply(false);
        if (!statedGoal && (intakeState & 2) == 0) return nextIntakeReply(true);
        return intakeConclusion();
    }

    public synchronized String nextIntakeReply(boolean goal) {
        if (intakeReply != null) return intakeReply;
        int field = goal ? 2 : 1;
        if ((intakeState & field) != 0 || goal && statedGoal || !goal && statedBasics) return nextIntakeReply();
        intakeState |= field;
        intakeReply = goal ? "你希望学完后能做出什么，或者解决什么实际问题？"
                : "你目前学过哪些编程知识，能独立做点什么？";
        return intakeReply;
    }

    private String intakeConclusion() {
        if (intakeReply == null) intakeReply = "目前的信息还不足以判断哪门课程更适合你。"
                + (hadPriorCards ? "前面展示的课程可先作为浏览选项，暂不作个性化匹配结论。"
                : "可以先通过课程列表了解方向，暂不作个性化匹配结论。");
        return intakeReply;
    }

    /** 仅随正常完成的最终回答保存；有个人基础和目标的推荐完成后才开启新一轮，普通浏览不清空提问记录。 */
    public synchronized Integer responseIntakeState() {
        if (hasSelectedCourses() && statedBasics && statedGoal) return 0;
        return intakeReply == null || intakeState == 0 ? null : Integer.valueOf(intakeState);
    }

    public synchronized void rememberEnrollment(String id, Integer count) {
        if (!closed && count != null) enrollmentCounts.put(id, count);
    }

    public synchronized Integer enrollmentCount(String id) { return enrollmentCounts.get(id); }

    public static CourseRecommendations from(ToolContext context) {
        Object value = context.getContext().get(CONTEXT_KEY);
        if (!(value instanceof CourseRecommendations recommendations)) {
            throw new IllegalStateException("当前请求不支持课程推荐");
        }
        return recommendations;
    }

    public synchronized void addCandidates(List<CourseCard> values) {
        if (closed) return;
        for (CourseCard value : values) {
            if (candidates.size() < 20 || candidates.containsKey(value.getCourseId())) {
                candidates.put(value.getCourseId(), value);
            }
        }
    }

    public synchronized void markQueried() {
        if (!closed) queried = true;
    }

    public synchronized boolean hasQueried() {
        return queried;
    }

    public synchronized void addDetailCard(CourseCard card) {
        if (closed) return;
        if (detailCards.size() < 3 || detailCards.containsKey(card.getCourseId())) {
            detailCards.put(card.getCourseId(), card);
        }
    }

    public synchronized void invalidateDetail(String id) {
        if (closed) return;
        detailCards.remove(id);
        verifiedDetails.remove(id);
        detailFacts.remove(id);
    }

    public synchronized void markDemo(String id) {
        if (closed) return;
        detailCards.remove(id);
        verifiedDetails.remove(id);
        var fact = detailFacts.get(id);
        if (fact != null) detailFacts.put(id, new DetailFacts(fact.courseId(), fact.name(), fact.usePeople(),
                fact.detail(), fact.catalogue(), true));
    }

    public synchronized boolean contains(String id) {
        return !closed && candidates.containsKey(id);
    }

    public synchronized void markDetailVerified(String id) {
        if (!closed) verifiedDetails.add(id);
    }

    /** 只由业务工具在查询成功后保存原始字段，不从模型消息或历史重建事实。 */
    public synchronized void rememberDetail(CourseBaseInfoDTO detail) {
        rememberDetail(detail, false);
    }

    public synchronized void rememberDetail(CourseBaseInfoDTO detail, boolean demoName) {
        if (closed || detail == null || detail.getId() == null) return;
        String id = detail.getId().toString();
        if (detailFacts.size() < 20 || detailFacts.containsKey(id)) {
            detailFacts.put(id, detailFacts(detail, demoName));
        }
    }

    public static DetailFacts detailFacts(CourseBaseInfoDTO detail, boolean demoName) {
        return new DetailFacts(detail.getId().toString(), detail.getName(), detail.getUsePeople(), detail.getDetail(),
                detail.getCatalogue() == null ? null : detail.getCatalogue().stream()
                        .filter(StringUtils::hasText).toList(),
                demoName || CourseTools.isDemo(detail.getName()) || CourseTools.isDemo(detail.getDetail()));
    }

    public synchronized List<DetailFacts> detailFacts() {
        return closed ? List.of() : List.copyOf(detailFacts.values());
    }

    /** 纯事实回答不附带模型生成的推荐理由，卡片业务字段仍保留原值。 */
    public synchronized void clearCardReasons() {
        if (closed) return;
        cards.forEach(card -> card.setReason(null));
        detailCards.values().forEach(card -> card.setReason(null));
    }

    public record DetailFacts(String courseId, String name, String usePeople, String detail, List<String> catalogue,
                              boolean demo) { }

    /** 卡片只引用本轮原始业务事实，模型传入的自由理由不再成为用户可见事实出口。 */
    public synchronized String cardReason(String id) {
        var fact = detailFacts.get(id);
        if (closed || fact == null) return null;
        if (StringUtils.hasText(fact.usePeople())) return "适用人群：" + CourseFactAnswer.display(fact.usePeople());
        return null;
    }

    public synchronized boolean canRecommend(String id) {
        return contains(id) && verifiedDetails.contains(id);
    }

    public synchronized List<CourseCard> select(List<CourseCard> values) {
        if (closed) return List.of();
        selected = true;
        cards = values.stream().filter(card -> candidates.containsKey(card.getCourseId())).limit(3).toList();
        return cards;
    }

    public synchronized List<CourseCard> snapshot() {
        return selected ? cards : List.copyOf(detailCards.values());
    }

    public synchronized boolean hasSelectedCourses() {
        return !closed && selected && !cards.isEmpty();
    }

    public synchronized List<CourseCard> finish(boolean current) {
        if (!current) {
            cards = List.of();
            detailCards.clear();
        }
        closed = true;
        return snapshot();
    }

    public synchronized void close() {
        closed = true;
    }
}
