package com.tianji.learning.mapper.interaction;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tianji.learning.domain.po.InteractionReply;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * learning 的回答/评论持久化入口，BaseMapper 提供常规 CRUD，点赞消费者使用带版本的条件更新。
 * interaction_reply 保存正文、结构关系及冗余计数；谁点赞了谁由 remark 的 liked_record 管理。
 * 更新总数和版本放在同一条 SQL 中，数据库对同一行协调并发，不需要先查版本再由 Java 决定写入。
 */
public interface InteractionReplyMapper extends BaseMapper<InteractionReply> {

    /** 只有消息版本更新时才覆盖点赞数，数据库条件更新可抵御消费者并发和消息乱序。 */
    // version 必须严格更大：相等是重复消息，更小是晚到旧消息，更新行数均为 0。
    // 这是版本门槛的条件更新，没有使用 MyBatis-Plus @Version 乐观锁插件；赋绝对值避免重试重复加一。
    @Update("UPDATE interaction_reply SET liked_times = #{likedTimes}, liked_version = #{version} " +
            "WHERE id = #{replyId} AND liked_version < #{version}")
    int updateLikedTimesIfNewer(@Param("replyId") Long replyId,
                                @Param("likedTimes") Integer likedTimes,
                                @Param("version") Long version);
}
