package com.tianji.course.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author wusongsong
 * @since 2022/7/11 11:59
 * @version 1.0.0
 **/
@Data
@Schema(description = "课程基本信息")
public class CourseBaseInfoVO {
    @Schema(description = "课程id")
    private Long id;
    @Schema(description = "一级分类id")
    private Long firstCateId;
    @Schema(description = "二级分类id")
    private Long secondCateId;
    @Schema(description = "三级分类id")
    private Long thirdCateId;
    @Schema(description = "课程创建人")
    private String createrName;
    private Long creater;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    @Schema(description = "封面url")
    private String coverUrl;
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
    @Schema(description = "更新人名称")
    private String updaterName;
    private Long updater;
    @Schema(description = "课时总数量,去掉章，测试，用于编辑回显时该值为空")
    private Integer cataTotalNum;
    @Schema(description = "课程评分，用于编辑回显时该值为空")
    private Double coureScore = 0d;
    @Schema(description = "课程评分")
    private Integer score;
    @Schema(description = "报名人数，用于编辑回显时该值为空")
    private Integer enrollNum = 0;
    @Schema(description = "学习人数，用于编辑回显时该值为空")
    private Integer studyNum = 0;
    @Schema(description = "退款人数，用于编辑回显时该值为空")
    private Integer refundNum = 0;
    @Schema(description = "实付总金额，用于编辑回显时该值为空")
    private Integer realPayAmount = 0;
    @Schema(description = "课程名称")
    private String name;
    @Schema(description = "课程分类名称，中间使用/隔开")
    private String cateNames;
    @Schema(description = "课程价格")
    private Integer price;
    @Schema(description = "购买有效期开始")
    private LocalDateTime purchaseStartTime;
    @Schema
    private LocalDateTime purchaseEndTime;
    @Schema(description = "有效期")
    private Integer validDuration;
    @Schema(description = "课程介绍")
    private String introduce;
    @Schema(description = "使用人群")
    private String usePeople;
    @Schema(description = "详情")
    private String detail;
    //
    @Schema(description = "是否可以修改，默认不能修改")
    private Boolean canUpdate = false;
    @Schema(description = "是否免费")
    private Boolean free;
    @Schema(description = "步骤,1:已保存基本信息，2：已保存课程目录，3：已保存课程视频，4：已保存题目，5：已保存课程老师")
    private Integer step;
    @Schema(description = "课程状态，1：待上架，2：已上架，3：下架，4：已完结")
    private Integer status;

}
