package com.tianji.learning.service.interaction;

import com.tianji.api.client.aigc.BotReplyClient;
import com.tianji.api.dto.aigc.BotReplyRequest;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.ForbiddenException;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.config.BotReplyProperties;
import com.tianji.learning.domain.po.BotReplyTask;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.vo.ReplySubmissionVO;
import com.tianji.learning.mapper.interaction.BotReplyTaskMapper;
import com.tianji.learning.mapper.interaction.InteractionQuestionMapper;
import com.tianji.learning.mapper.interaction.InteractionReplyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BotReplyService {
    private final BotReplyProperties properties;
    private final BotReplyTaskMapper tasks;
    private final InteractionReplyMapper replies;
    private final InteractionQuestionMapper questions;
    private final BotReplyClient client;
    private final StringRedisTemplate redis;
    private final PlatformTransactionManager transactionManager;

    private static final DefaultRedisScript<Long> BUDGET = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            if tonumber(redis.call('GET', KEYS[2]) or '0') >= tonumber(ARGV[2]) then return 0 end
            if tonumber(redis.call('GET', KEYS[3]) or '0') >= tonumber(ARGV[3]) then return 0 end
            redis.call('SET', KEYS[1], '1', 'EX', ARGV[1])
            redis.call('INCR', KEYS[2]); redis.call('EXPIRE', KEYS[2], 172800)
            redis.call('INCR', KEYS[3]); redis.call('EXPIRE', KEYS[3], 172800)
            return 1
            """, Long.class);

    public record BotInfo(boolean enabled, Long userId, String name) { }
    public record BotStatus(String status, Long responseReplyId, String content) { }

    public BotInfo info() {
        return new BotInfo(properties.isEnabled(), properties.getUserId(), properties.getName());
    }

    /** Completed tasks prove which replies were actually published by the bot. */
    public Set<Long> generatedReplyIds(List<Long> replyIds) {
        if (replyIds.isEmpty()) return Set.of();
        try {
            return tasks.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BotReplyTask>()
                            .select(BotReplyTask::getResponseReplyId)
                            .eq(BotReplyTask::getStatus, "COMPLETED").in(BotReplyTask::getResponseReplyId, replyIds))
                    .stream().map(BotReplyTask::getResponseReplyId).collect(Collectors.toSet());
        } catch (org.springframework.dao.DataAccessException error) {
            // The task table is optional until the feature has been installed.
            if (!properties.isEnabled() && missingTaskTable(error)) return Set.of();
            throw error;
        }
    }

    private boolean missingTaskTable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && "42S02".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    /** 在保存评论的同一个事务里登记；后台独立连接只能看到已提交任务。 */
    public ReplySubmissionVO enqueue(InteractionReply source, List<Long> mentionedUserIds) {
        if (mentionedUserIds == null || !mentionedUserIds.contains(properties.getUserId())
                || Objects.equals(source.getUserId(), properties.getUserId())) {
            return new ReplySubmissionVO(source.getId(), "NOT_REQUESTED");
        }
        if (!properties.isEnabled()) return new ReplySubmissionVO(source.getId(), "UNAVAILABLE");
        context(source); // 检查问题、所属回答、目标评论的归属和可见性，拒绝跨讨论引用。
        var task = new BotReplyTask();
        task.setSourceReplyId(source.getId());
        task.setUserId(source.getUserId());
        task.setStatus("PENDING");
        tasks.insert(task);
        return new ReplySubmissionVO(source.getId(), "PENDING");
    }

    public BotStatus status(Long sourceId) {
        if (!properties.isEnabled()) throw new BadRequestException("机器人暂未启用");
        BotReplyTask task = tasks.selectById(sourceId);
        if (task == null || !Objects.equals(task.getUserId(), UserContext.getUser())) {
            throw new ForbiddenException("无权查询此机器人任务");
        }
        InteractionReply source = replies.selectById(sourceId);
        if (source == null || Boolean.TRUE.equals(source.getHidden())) throw new ForbiddenException("讨论不可见");
        InteractionQuestion question = questions.selectById(source.getQuestionId());
        InteractionReply root = replies.selectById(rootId(source));
        if (question == null || Boolean.TRUE.equals(question.getHidden()) || root == null
                || Boolean.TRUE.equals(root.getHidden())) throw new ForbiddenException("讨论不可见");
        InteractionReply response = task.getResponseReplyId() == null ? null : replies.selectById(task.getResponseReplyId());
        return new BotStatus(task.getStatus(), task.getResponseReplyId(),
                response == null || Boolean.TRUE.equals(response.getHidden()) ? null : response.getContent());
    }

    /** 每条任务只有一次 PENDING -> PROCESSING 的机会，失败不自动重试付费请求。 */
    public void processPending() {
        if (!properties.isEnabled()) return;
        tasks.failInterrupted(LocalDateTime.now().minusMinutes(5));
        for (BotReplyTask task : tasks.pending()) {
            if (tasks.claim(task.getSourceReplyId()) != 1) continue;
            Long previousUser = UserContext.getUser();
            try {
                InteractionReply source = replies.selectById(task.getSourceReplyId());
                if (source == null || !Objects.equals(source.getUserId(), task.getUserId())
                        || Objects.equals(source.getUserId(), properties.getUserId())) {
                    tasks.finish(task.getSourceReplyId(), "SKIPPED", null);
                    continue;
                }
                BotReplyRequest request = context(source);
                if (!reserveBudget(task.getUserId())) {
                    tasks.finish(task.getSourceReplyId(), "RATE_LIMITED", null);
                    continue;
                }
                UserContext.setUser(task.getUserId()); // Feign 在后台线程透传原请求者身份。
                String text = client.reply(request);
                if (!StringUtils.hasText(text) || text.length() > 8000) throw new IllegalStateException("无有效模型回答");
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    context(source); // 生成期间已隐藏/删除的讨论不再发布回答。
                    InteractionReply response = new InteractionReply().setQuestionId(source.getQuestionId())
                            .setAnswerId(rootId(source)).setTargetReplyId(source.getId())
                            .setTargetUserId(source.getUserId()).setUserId(properties.getUserId())
                            .setContent(text.strip()).setAnonymity(false).setHidden(false)
                            .setReplyTimes(0).setLikedTimes(0).setLikedVersion(0L);
                    replies.insert(response);
                    if (replies.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InteractionReply>()
                            .eq(InteractionReply::getId, rootId(source)).eq(InteractionReply::getHidden, false)
                            .setSql("reply_times = reply_times + 1")) != 1
                            || tasks.finish(source.getId(), "COMPLETED", response.getId()) != 1) {
                        throw new IllegalStateException("讨论或任务状态已变化");
                    }
                    // 机器人只写评论，不走学生提交入口，不发放积分、不递归触发机器人。
                });
            } catch (BadRequestException e) {
                tasks.finish(task.getSourceReplyId(), "SKIPPED", null);
            } catch (Exception e) {
                tasks.finish(task.getSourceReplyId(), "FAILED", null);
                log.warn("机器人回复失败，保留任务且不自动重试：sourceReplyId={}", task.getSourceReplyId(), e);
            } finally {
                if (previousUser == null) UserContext.removeUser(); else UserContext.setUser(previousUser);
            }
        }
    }

    private boolean reserveBudget(Long userId) {
        String date = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        return Long.valueOf(1).equals(redis.execute(BUDGET, List.of("qa:bot:cooldown:" + userId,
                        "qa:bot:daily:" + date + ":" + userId, "qa:bot:daily:" + date + ":all"),
                String.valueOf(Math.max(1, properties.getCooldownSeconds())),
                String.valueOf(properties.getUserDailyLimit()), String.valueOf(properties.getGlobalDailyLimit())));
    }

    private BotReplyRequest context(InteractionReply source) {
        InteractionQuestion question = questions.selectById(source.getQuestionId());
        InteractionReply current = replies.selectById(source.getId());
        if (question == null || Boolean.TRUE.equals(question.getHidden()) || current == null
                || Boolean.TRUE.equals(current.getHidden())) throw new BadRequestException("讨论已删除或隐藏");
        InteractionReply root = replies.selectById(rootId(source));
        if (root == null || Boolean.TRUE.equals(root.getHidden()) || !isRoot(root)
                || !Objects.equals(root.getQuestionId(), source.getQuestionId())) {
            throw new BadRequestException("回答不属于当前问题");
        }
        InteractionReply target = null;
        if (source.getTargetReplyId() != null && source.getTargetReplyId() != 0L) {
            target = replies.selectById(source.getTargetReplyId());
            if (target == null || Boolean.TRUE.equals(target.getHidden())
                    || !Objects.equals(target.getQuestionId(), source.getQuestionId())
                    || !Objects.equals(rootId(target), rootId(source))) {
                throw new BadRequestException("目标评论不属于当前讨论");
            }
        }
        var request = new BotReplyRequest();
        request.setTitle(plain(question.getTitle(), 254));
        request.setQuestion(plain(question.getDescription(), 2000));
        request.setAnswer(plain(root.getContent(), 3000));
        request.setTarget(target == null || Objects.equals(target.getId(), root.getId()) ? "" : plain(target.getContent(), 2000));
        request.setComment(plain(source.getContent(), 2000));
        if (!StringUtils.hasText(request.getComment().replace("@" + properties.getName(), ""))) {
            throw new BadRequestException("请在 @机器人后填写问题");
        }
        return request;
    }

    private static boolean isRoot(InteractionReply reply) {
        return reply.getAnswerId() == null || reply.getAnswerId() == 0L;
    }

    private static Long rootId(InteractionReply reply) {
        return isRoot(reply) ? reply.getId() : reply.getAnswerId();
    }

    private static String plain(String text, int max) {
        if (text == null) return "";
        // 当前问答是纯文本输入，保留 List<String>、HTML 示例等代码；前端使用文本插值显示。
        String result = text.strip();
        return result.substring(0, Math.min(result.length(), max));
    }
}
