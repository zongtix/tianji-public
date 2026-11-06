package com.tianji.aigc.tools.result;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.NumberUtil;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.tianji.api.dto.course.CourseBaseInfoDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Optional;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CourseInfo {

    @JsonPropertyDescription("课程id")
    private Long id;
    @JsonPropertyDescription("课程名称")
    private String name;
    @JsonPropertyDescription("课程价格，单位为元，货币为人民币；null 表示价格未提供，不代表免费")
    private Double price;
    @JsonPropertyDescription("课程学习有效期，单位：月")
    private Integer validDuration;
    @JsonPropertyDescription("适用人群，例如：初学者")
    private String usePeople;
    @JsonPropertyDescription("课程详细介绍")
    private String detail;
    @JsonPropertyDescription("当前正式章节标题；仅证明目录列出这些主题，不代表已核实完整教学内容或掌握程度；null 表示未提供")
    private List<String> catalogue;

    /**
     * 将CourseBaseInfoDTO转换为CourseInfo对象
     *
     * @param courseBaseInfoDTO 课程基础信息数据传输对象（包含原始课程数据）
     * @return 转换后的课程信息实体对象（包含转换为元的价格）
     */
    public static CourseInfo of(CourseBaseInfoDTO courseBaseInfoDTO) {
        if (null == courseBaseInfoDTO) {
            return null;
        }
        // 基础对象属性拷贝（忽略转换错误）
        CourseInfo courseInfo = BeanUtil.toBeanIgnoreError(courseBaseInfoDTO, CourseInfo.class);

        // 免费标记优先；缺失或非法价格保留未知，避免被解释成免费。
        courseInfo.setPrice(Boolean.TRUE.equals(courseBaseInfoDTO.getFree()) ? Double.valueOf(0.0d)
                : Optional.ofNullable(courseBaseInfoDTO.getPrice())
                .filter(num -> num >= 0)
                .map(num -> num.doubleValue() / 100d)
                .map(num -> NumberUtil.round(num, 2).doubleValue())
                .orElse(null));

        return courseInfo;
    }

}
