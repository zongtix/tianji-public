package com.tianji.remark.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * MyBatis-Plus 持久化实体（PO），映射 remark 数据库的 liked_record 点赞关系表。
 * 一行表示“某用户点赞某类型的某对象”；取消点赞删除行，不保存 liked=false 的历史记录。
 * 迁移脚本定义唯一键 (user_id, biz_type, biz_id)，防止批次重试插入重复关系；id 使用数据库自增。
 * 只保存身份/对象 ID 与时间，不保存评论内容、昵称或总数；总数来自 Redis ZSet，并同步至业务表。
 * Mapper 用它读取历史关系重建缓存/对账；后台批量写入由 JdbcTemplate 执行，不直接返回此 PO 给前端。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("liked_record")
public class LikedRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户id
     */
    private Long userId;

    /**
     * 点赞的业务id
     */
    private Long bizId;

    /**
     * 点赞的业务类型
     */
    private String bizType;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
