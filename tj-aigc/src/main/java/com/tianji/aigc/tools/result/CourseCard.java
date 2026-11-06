package com.tianji.aigc.tools.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 课程卡片快照；ID 使用字符串，价格单位为分。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CourseCard {
    private String courseId;
    private String name;
    private String coverUrl;
    private Integer priceInCents;
    private Integer validDuration;
    private String reason;
}
