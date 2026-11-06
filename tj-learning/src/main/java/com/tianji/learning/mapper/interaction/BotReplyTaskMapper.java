package com.tianji.learning.mapper.interaction;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tianji.learning.domain.po.BotReplyTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface BotReplyTaskMapper extends BaseMapper<BotReplyTask> {
    @Select("SELECT * FROM bot_reply_task WHERE status = 'PENDING' ORDER BY create_time, source_reply_id LIMIT 5")
    List<BotReplyTask> pending();

    @Update("UPDATE bot_reply_task SET status = 'PROCESSING', update_time = CURRENT_TIMESTAMP " +
            "WHERE source_reply_id = #{id} AND status = 'PENDING'")
    int claim(@Param("id") Long id);

    @Update("UPDATE bot_reply_task SET status = #{status}, response_reply_id = #{responseId}, update_time = CURRENT_TIMESTAMP " +
            "WHERE source_reply_id = #{id} AND status = 'PROCESSING'")
    int finish(@Param("id") Long id, @Param("status") String status, @Param("responseId") Long responseId);

    // 进程中断的请求可能已计费，不重放；保留记录供排查。
    @Update("UPDATE bot_reply_task SET status = 'FAILED', update_time = CURRENT_TIMESTAMP " +
            "WHERE status = 'PROCESSING' AND update_time < #{before}")
    int failInterrupted(@Param("before") LocalDateTime before);
}
