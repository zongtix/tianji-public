package com.tianji.course.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.validate.Checker;
import com.tianji.course.constants.CourseErrorInfo;
import com.tianji.course.utils.CourseSaveBaseGroup;
import lombok.Data;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 课程基本信息
 *
 * @ClassName CourseBaseInfoSaveDTO
 * @author wusongsong
 * @since 2022/7/11 11:39
 * @version 1.0.0
 **/

@Data
@Schema(description = "课程基本信息保存")
public class CourseBaseInfoSaveDTO implements Checker {
    @Schema(description = "课程id，新课程该值不能传，老课程必填")
    private Long id;
    @Schema(description = "课程名称")
    @NotNull(message = CourseErrorInfo.Msg.COURSE_SAVE_NAME_NULL)
    private String name;
    @Schema(description = "三级课程分类id")
    @NotNull(message = CourseErrorInfo.Msg.COURSE_SAVE_CATEGORY_NULL)
    private Long thirdCateId;
    @Schema(description = "封面链接url")
    @NotNull(message = CourseErrorInfo.Msg.COURSE_SAVE_COVER_URL_NULL, groups = CourseSaveBaseGroup.class)
    private String coverUrl;
    @Schema(description = "是否是免费")
    @NotNull(message = CourseErrorInfo.Msg.COURSE_SAVE_FREE_NULL)
    private Boolean free;
    @Schema(description = "课程价格")
    @Min(value = 0, message = CourseErrorInfo.Msg.COURSE_SAVE_PRICE_NEGATIVE)
    private Integer price;
//    @Schema(description = "购买周期开始时间")
//    @NotNull(message = CourseErrorInfo.Msg.COURSE_SAVE_PURCHASE_TIME_NULL)
    private LocalDateTime purchaseStartTime;
    @Schema(description = "购买周期结束时间")
    @NotNull(message = CourseErrorInfo.Msg.COURSE_SAVE_PURCHASE_TIME_NULL)
    private LocalDateTime purchaseEndTime;
    @Schema(description = "课程介绍")
    @NotNull(message = CourseErrorInfo.Msg.COURSE_SAVE_INTRODUCE_NULL, groups = CourseSaveBaseGroup.class)
    private String introduce;
    @Schema(description = "使用人群")
    @NotNull(message = CourseErrorInfo.Msg.COURSE_SAVE_USE_PEOPLE_NULL, groups = CourseSaveBaseGroup.class)
    private String usePeople;
    @Schema(description = "详情")
    @NotNull(message = CourseErrorInfo.Msg.COURSE_SAVE_DETAIL_NULL, groups = CourseSaveBaseGroup.class)
    private String detail;
    @Schema(description = "学习周期，0或不传表示没有期限，其他表示月数")
    @NotNull(message = CourseErrorInfo.Msg.COURSE_SAVE_DURATION_NULL)
    private Integer validDuration;

    @Override
    public void check() {
        if(!free) { //非免费
            if(price == null) { //付费课程未设置价格
                throw new BadRequestException(CourseErrorInfo.Msg.COURSE_SAVE_PRICE_NULL);
            }
            if(price <= 0){ //付费课程设置价格小于0
                throw new BadRequestException(CourseErrorInfo.Msg.COURSE_SAVE_PRICE_NEGATIVE);
            }
        }else { //免费
            if(price != null && price > 0){ //免费课程设置了价格
                throw new BadRequestException(CourseErrorInfo.Msg.COURSE_SAVE_PRICE_FREE);
            }
        }
        if(purchaseEndTime.isBefore(DateUtils.now())){
            throw new BadRequestException(CourseErrorInfo.Msg.COURSE_SAVE_PURCHASE_ILLEGAL);
        }
//        if (purchaseStartTime.isAfter(purchaseEndTime)) {
//            throw new BadRequestException(CourseErrorInfo.Msg.COURSE_SAVE_PURCHASE_ILLEGAL);
//        }
//        if(id == null && purchaseStartTime.isBefore(LocalDateTime.now())){
//            throw new BadRequestException(CourseErrorInfo.Msg.COURSE_SAVE_PURCHASE_ILLEGAL2);
//        }
    }
}
