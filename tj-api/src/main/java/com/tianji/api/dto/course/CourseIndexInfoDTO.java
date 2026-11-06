package com.tianji.api.dto.course;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 正式课程的索引快照；包含删除标记，用于修复漏掉的下架/删除通知。 */
@Data
public class CourseIndexInfoDTO {
    private Long id;
    private String name;
    private Integer status;
    private Integer deleted;
    private LocalDateTime purchaseStartTime;
    private LocalDateTime purchaseEndTime;
    private String introduce;
    private String usePeople;
    private String detail;
    private List<String> catalogue;
}
