package com.tianji.aigc.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 历史入口只返回展示所需字段，不暴露用户及内部主键。 */
@Data
public class HistorySessionVO {
    private String sessionId;
    private String title;
    private String knowledgeBaseId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
