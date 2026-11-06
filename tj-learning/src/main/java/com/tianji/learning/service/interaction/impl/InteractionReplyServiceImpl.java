package com.tianji.learning.service.interaction.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.client.remark.LikedRecordClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.constants.Constant;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;
import com.tianji.learning.domain.vo.ReplySubmissionVO;
import com.tianji.learning.service.interaction.BotReplyService;
import com.tianji.learning.enums.QuestionStatus;
import com.tianji.learning.mapper.interaction.InteractionQuestionMapper;
import com.tianji.learning.mapper.interaction.InteractionReplyMapper;
import com.tianji.learning.mq.message.ReplyPointsMessage;
import com.tianji.learning.service.interaction.IInteractionReplyService;
import com.tianji.learning.service.points.IPointsMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * learning 的回答/评论业务实现，是 remark 点赞对象的内容来源与查询结果的组装方。
 * 本类保存正文和回复结构、维护问题/回答计数、处理隐藏；remark 只管理 QA 对象的点赞关系与总数。
 * 列表从本地数据库分页，再批量调用 UserClient 补用户信息、LikedRecordClient 补即时总数和个人状态。
 * 输入为 ReplyDTO/ReplyPageQuery，输出为 ReplyVO/PageDTO；不要把展示 VO 与持久化 PO 混用。
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class InteractionReplyServiceImpl extends ServiceImpl<InteractionReplyMapper, InteractionReply>
        implements IInteractionReplyService {

    private final InteractionQuestionMapper questionMapper;

    private final UserClient userClient;

    private final LikedRecordClient likedRecordClient;

    /** 新增回答时与正文一起保存积分消息，提交后由任务投递 reply.new。 */
    private final IPointsMessageService pointsMessageService;

    private final BotReplyService botReplyService;

    /**
     * 供 remark 目标校验调用：只查输入集合中存在的回答/评论主键，转换成 Set<Long>。
     * 此查询没有 hidden 过滤，隐藏与物理不存在是两种状态；它只确认存在性。
     */
    @Override
    public Set<Long> queryExistingReplyIds(List<Long> ids) {
        if (CollUtils.isEmpty(ids)) {
            return java.util.Collections.emptySet();
        }
        return lambdaQuery().select(InteractionReply::getId)
                .in(InteractionReply::getId, ids).list().stream()
                .map(InteractionReply::getId).collect(Collectors.toSet());
    }

    /**
     * 用户提交回答或评论的内容流程，独立于 POST /likes。
     * 同一数据库事务包含：保存新回复 → 评论时增加根回答 reply_times → 回答时增加问题 answer_times/最新回答。
     * 学生提交还会把问题标为未查看。没有事务，正文保存成功而上级计数失败会留下不一致的结构统计。
     * 只在本地 learning 数据库提交，不把后续用户点赞或远程服务纳入此事务。
     */
    @Override
    @Transactional
    public ReplySubmissionVO saveReply(ReplyDTO replyDTO) {
        // 1、获取当前登录用户，保存回答或评论
        Long userId = UserContext.getUser();
        // DTO 只表达客户端提交信息，copyBean 将同名字段映射为数据库 PO；登录 userId 随后由服务端补入。
        // 这步不会自动组装昵称、点赞状态，也不是“DTO 可直接作为数据库实体”的含义。
        InteractionReply reply = BeanUtils.copyBean(replyDTO, InteractionReply.class);
        reply.setUserId(userId);
        save(reply);

        // 2、评论需要累加所属回答的评论数
        // 本方法用 answerId == null 判断新增根回答；查询方法则用数据库 answer_id == 0 找根回答。
        // 这里没有显式将 null 转为 0；实际落库默认值需结合数据库 DDL 确认，不能把两者视为同一个值。
        boolean isAnswer = replyDTO.getAnswerId() == null;
        if (!isAnswer) {
            // 在 SQL 中对原值加一，由数据库协调同一行的并发更新，避免 Java 先查再写造成计数覆盖。
            lambdaUpdate()
                    .setSql("reply_times = reply_times + 1")
                    .eq(InteractionReply::getId, replyDTO.getAnswerId())
                    .update();
        }

        // 3、回答需要更新问题的回答数和最新回答；学生回复需要将问题标记为未查看
        boolean isStudent = Boolean.TRUE.equals(replyDTO.getIsStudent());
        if (isAnswer || isStudent) {
            questionMapper.update(
                    null,
                    new LambdaUpdateWrapper<InteractionQuestion>()
                            .set(isAnswer, InteractionQuestion::getLatestAnswerId, reply.getId())
                            .setSql(isAnswer, "answer_times = answer_times + 1")
                            .set(isStudent, InteractionQuestion::getStatus, QuestionStatus.UN_CHECK)
                            .eq(InteractionQuestion::getId, replyDTO.getQuestionId())
            );
        }

        // 4、新增回答发放问答积分(routingKey = reply.new)。只有"回答"(answerId 为空)计分，
        //    对回答的评论不计分——课程评价另属 COMMENT 类型，不由本方法负责。
        //    与正文同事务保存待发送消息，发送失败不再影响已提交的回答，也不会丢失积分事件。
        if (isAnswer) {
            pointsMessageService.saveMessage("qa:" + reply.getId(), MqConstants.Key.WRITE_REPLY,
                    ReplyPointsMessage.of(userId, reply.getId()));
        }
        return botReplyService.enqueue(reply, replyDTO.getMentionedUserIds());
    }

    /**
     * 查询问题下的回答或指定回答下的评论，同时补充 remark 的数量和个人点赞状态。
     * 流程：校验定位参数 → 本地分页 → 批量取点赞快照/关系 → 批量查作者与目标用户 → PO 转 VO → 返回分页信息。
     * forAdmin=true 不过滤隐藏、不查管理员个人点赞关系，并允许组装匿名作者信息。
     * 内容/排序在数据库完成，展示数量再读取 Redis 快照；远程降级时继续使用数据库上次同步的计数。
     */
    @Override
    public PageDTO<ReplyVO> queryReplyPage(ReplyPageQuery query, boolean forAdmin) {
        // 1、问题id和回答id至少传一个
        Long questionId = query.getQuestionId();
        Long answerId = query.getAnswerId();
        if (questionId == null && answerId == null) {
            throw new BadRequestException("问题或回答id不能都为空");
        }

        // 2、分页查询问题下的回答，或指定回答下的评论
        boolean isQueryAnswer = questionId != null;
        // MyBatis-Plus eq(condition, getter, value) 的首参决定是否拼接条件；先按 questionId 决定查询回答或评论。
        // 用户端加 hidden=false，管理端省略该条件；toMpPage 生成分页参数，按库中点赞数降序、创建时间升序。
        // 分页排序发生在 MQ 同步后的数据库计数上，后面覆盖 VO 的即时总数不会重排已经选出的这一页。
        Page<InteractionReply> page = lambdaQuery()
                .eq(isQueryAnswer, InteractionReply::getQuestionId, questionId)
                .eq(InteractionReply::getAnswerId, isQueryAnswer ? 0L : answerId)
                .eq(!forAdmin, InteractionReply::getHidden, false)
                .page(query.toMpPage(
                        OrderItem.desc(Constant.DATA_FIELD_NAME_LIKED_TIME),
                        OrderItem.asc(Constant.DATA_FIELD_NAME_CREATE_TIME)
                ));
        List<InteractionReply> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }

        // map 提取当前页 PO 的主键，collect 得到 List<Long>；后续两次远程查询都按整页传 ID，避免 N+1。
        // 这里没有逐条请求 remark，也没有把整张回复表一次拉到内存。
        List<Long> replyIds = records.stream()
                .map(InteractionReply::getId)
                .collect(Collectors.toList());
        // 总数从点赞服务的 ZSet 读最新值；remark 不可用时 Feign 降级为空表，继续显示数据库快照。
        Map<Long, Integer> likedTimes = likedRecordClient.queryLikedTimes("QA", replyIds);

        // 用户端回答列表需要展示“我是否点过赞”；把当前页的回答 id 一次发给点赞服务，避免逐条远程查询。
        // 管理端没有点赞交互，因此不查询管理员个人的点赞状态。
        Set<Long> likedReplyIds = new HashSet<>();
        if (!forAdmin) {
            likedReplyIds = likedRecordClient.queryMyLikedBizIds("QA", new ArrayList<>(replyIds));
        }

        // 3、收集回复人和被回复人的用户id
        Set<Long> userIds = new HashSet<>();
        Set<Long> targetReplyIds = new HashSet<>();
        for (InteractionReply reply : records) {
            if (!reply.getAnonymity() || forAdmin) {
                userIds.add(reply.getUserId());
            }
            targetReplyIds.add(reply.getTargetReplyId());
        }
        targetReplyIds.remove(null);
        targetReplyIds.remove(0L);
        if (CollUtils.isNotEmpty(targetReplyIds)) {
            List<InteractionReply> targetReplies = listByIds(targetReplyIds);
            Set<Long> targetUserIds = targetReplies.stream()
                    .filter(Predicate.not(InteractionReply::getAnonymity).or(reply -> forAdmin))
                    .map(InteractionReply::getUserId)
                    .collect(Collectors.toSet());
            userIds.addAll(targetUserIds);
        }

        // 4、批量查询用户，避免循环调用用户服务
        Map<Long, UserDTO> userMap = new HashMap<>(userIds.size());
        if (CollUtils.isNotEmpty(userIds)) {
            List<UserDTO> users = userClient.queryUserByIds(userIds);
            // List<UserDTO> 转成 Map<userId, UserDTO>，循环组装 VO 时可直接按用户 ID 查昵称头像。
            // 昵称头像归 user-service 管理，回复表只存 ID，故需批量远程补充；匿名过滤在收集 ID 时已经进行。
            userMap = users.stream().collect(Collectors.toMap(UserDTO::getId, user -> user));
        }

        // 5、封装VO
        var botReplyIds = botReplyService.generatedReplyIds(records.stream()
                .filter(reply -> java.util.Objects.equals(reply.getUserId(), botReplyService.info().userId()))
                .map(InteractionReply::getId).toList());
        List<ReplyVO> voList = new ArrayList<>(records.size());
        for (InteractionReply reply : records) {
            // PO → VO 只复制同名内容字段，不能凭复制得到 userName/userIcon/liked 等跨服务或个人化信息。
            // 随后按 Map/Set 补展示字段；VO 也不携带数据库内部的 likedVersion 版本控制字段。
            ReplyVO vo = BeanUtils.copyBean(reply, ReplyVO.class);
            // Redis 数量是即时快照；若点赞服务降级没有返回该 id，则保留数据库里的上次同步值。
            Integer currentLikedTimes = likedTimes.get(reply.getId());
            if (currentLikedTimes != null) {
                vo.setLikedTimes(currentLikedTimes);
            }
            if (!forAdmin) {
                // likedTimes 是总点赞数；liked 是当前用户的点赞关系，两者来自不同数据。
                vo.setLiked(likedReplyIds.contains(reply.getId()));
            }
            voList.add(vo);

            if (!reply.getAnonymity() || forAdmin) {
                UserDTO user = userMap.get(reply.getUserId());
                if (user != null) {
                    vo.setUserName(user.getName());
                    vo.setUserIcon(user.getIcon());
                    vo.setUserType(user.getType());
                }
            }

            UserDTO targetUser = userMap.get(reply.getTargetUserId());
            if (targetUser != null) {
                vo.setTargetUserName(targetUser.getName());
            }
            if (botReplyIds.contains(reply.getId())) {
                vo.setBot(true);
                vo.setUserName(botReplyService.info().name());
            }
        }
        // 把 MyBatis Page 的总数/页数与组装后的 VO 列表合并为 PageDTO<ReplyVO>，保持分页元信息。
        return PageDTO.of(page, voList);
    }

    /**
     * 管理端显示/隐藏内容，根回答和其下评论的 hidden 更新必须在同一 learning 数据库事务中完成。
     * 此操作不删除正文，也没有调用 remark 取消点赞；目标存在性查询不会把 hidden 当作不存在。
     */
    @Override
    @Transactional
    public void hiddenReply(Long id, Boolean hidden) {
        // 1、查询回答或评论
        InteractionReply oldReply = getById(id);
        if (oldReply == null) {
            return;
        }

        // 2、隐藏或显示当前回答、评论
        lambdaUpdate()
                .set(InteractionReply::getHidden, hidden)
                .eq(InteractionReply::getId, id)
                .update();

        // 3、如果操作的是根回答，同步隐藏或显示其下的所有评论
        Long answerId = oldReply.getAnswerId();
        if (answerId == null || answerId == 0L) {
            lambdaUpdate()
                    .set(InteractionReply::getHidden, hidden)
                    .eq(InteractionReply::getAnswerId, id)
                    .update();
        }
    }

    /**
     * 管理端详情：读取单条 PO，批量补作者与目标用户信息后返回 VO。
     * 此方法未调用 remark 查询即时总数/个人状态，点赞数来自数据库，与分页列表的补充策略不同。
     */
    @Override
    public ReplyVO queryReplyById(Long id) {
        // 1、查询回答或评论
        InteractionReply reply = getById(id);
        if (reply == null) {
            return null;
        }

        // 2、批量查询回复人和目标用户
        Set<Long> userIds = new HashSet<>();
        userIds.add(reply.getUserId());
        if (reply.getTargetUserId() != null && reply.getTargetUserId() != 0L) {
            userIds.add(reply.getTargetUserId());
        }
        List<UserDTO> users = userClient.queryUserByIds(userIds);
        Map<Long, UserDTO> userMap = users.stream()
                .collect(Collectors.toMap(UserDTO::getId, user -> user));

        // 3、封装VO
        ReplyVO vo = BeanUtils.copyBean(reply, ReplyVO.class);
        UserDTO user = userMap.get(reply.getUserId());
        if (user != null) {
            vo.setUserName(user.getName());
            vo.setUserIcon(user.getIcon());
            vo.setUserType(user.getType());
        }
        UserDTO targetUser = userMap.get(reply.getTargetUserId());
        if (targetUser != null) {
            vo.setTargetUserName(targetUser.getName());
        }
        if (java.util.Objects.equals(reply.getUserId(), botReplyService.info().userId())
                && botReplyService.generatedReplyIds(List.of(reply.getId())).contains(reply.getId())) {
            vo.setBot(true);
            vo.setUserName(botReplyService.info().name());
        }
        return vo;
    }

}
