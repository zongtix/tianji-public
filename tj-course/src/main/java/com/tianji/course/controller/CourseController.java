package com.tianji.course.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.api.dto.course.CourseBaseInfoDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.validate.annotations.ParamChecker;
import com.tianji.course.constants.CourseStatus;
import com.tianji.course.domain.dto.*;
import com.tianji.course.domain.vo.*;
import com.tianji.course.service.*;
import com.tianji.course.utils.CourseSaveBaseGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * 课程controller
 *
 * @ClassName CourseController
 * @Author wusongsong
 * @Date 2022/7/10 15:34
 * @Version
 **/
@Tag(name = "课程相关接口")
@RestController
@RequestMapping("courses")
@Slf4j
@Validated
public class CourseController {

    @Autowired
    private ICourseDraftService courseDraftService;

    @Autowired
    private ICourseCatalogueDraftService courseCatalogueDraftService;

    @Autowired
    private ICourseTeacherDraftService courseTeacherDraftService;

    @Autowired
    private ICourseService courseService;

    @Autowired
    private ICourseCatalogueService courseCatalogueService;

    //todo 二期做
//    @GetMapping("statistics")
//    @Operation(summary = "课程数据统计")
    public CourseStatisticsVO statistics() {
        return null;
    }

    @PostMapping("/base-info/batch")
    @Operation(summary = "批量查询正式课程基本信息、正文及目录")
    public List<CourseBaseInfoDTO> getBaseInfoBatch(@RequestBody List<Long> ids) {
        return courseService.getBaseInfoBatch(ids);
    }

    @GetMapping("baseInfo/{id}")
    @Operation(summary = "获取课程基础信息")
    @Parameters({@Parameter(name = "id", description = "课程id"),
            @Parameter(name = "see", description = "是否是用于查看页面查看数据，默认是查看,如果不是界面查看数据就是编辑页面使用")})
    public CourseBaseInfoVO baseInfo(@PathVariable("id") Long id,
                                     @RequestParam(value = "see", required = false, defaultValue = "1") Boolean see) {
        return courseDraftService.getCourseBaseInfo(id, see);
    }

    @PostMapping("baseInfo/save")
    @Operation(summary = "保存课程基本信息")
    @ParamChecker
    //校验非业务限制的字段
    public CourseSaveVO save(@RequestBody @Validated(CourseSaveBaseGroup.class) CourseBaseInfoSaveDTO courseBaseInfoSaveDTO) {
        return courseDraftService.save(courseBaseInfoSaveDTO);
    }

    @GetMapping("catas/{id}")
    @Operation(summary = "获取课程的章节")
    @Parameters({
            @Parameter(name = "id", description = "课程id"),
            @Parameter(name = "see", description = "是否是用于查看页面查看数据，默认是查看,如果不是界面查看数据就是编辑页面使用")
    })
    public List<CataVO> catas(@PathVariable(value = "id", required = false) Long id,
                              @RequestParam(value = "see", required = false, defaultValue = "1") Boolean see,
                              @RequestParam(value = "withPractice", required = false, defaultValue = "1") Boolean withPractice) {
        return courseCatalogueDraftService.queryCourseCatalogues(id, see, withPractice);
    }

    @PostMapping("catas/save/{id}/{step}")
    @Operation(summary = "保存章节")
    @Parameters({
            @Parameter(name = "id", description = "课程id"),
            @Parameter(name = "step", description = "步骤")
    })
    @ParamChecker
    public void catasSave(@RequestBody @Validated List<CataSaveDTO> cataSaveDTOS,
                          @PathVariable("id") Long id, @PathVariable(value = "step",required = false) Integer step) {
        courseCatalogueDraftService.save(id, cataSaveDTOS, step);
    }

    @PostMapping("media/save/{id}")
    @Operation(summary = "课程视频")
    @Parameters({
            @Parameter(name = "id", description = "课程id")
    })
    public void mediaSave(@PathVariable("id") Long id, @RequestBody @Valid List<CourseMediaDTO> courseMediaDTOS) {
        courseCatalogueDraftService.saveMediaInfo(id, courseMediaDTOS);
    }

    @PostMapping("subjects/save/{id}")
    @Operation(summary = "保存小节或练习中的题目")
    @Parameters({
            @Parameter(name = "id", description = "课程id")
    })
    public void saveSuject(@PathVariable("id") Long id, @RequestBody @Validated List<CataSubjectDTO> cataSubjectDTO) {
        courseCatalogueDraftService.saveSuject(id, cataSubjectDTO);
    }

    @GetMapping("subjects/get/{id}")
    @Operation(summary = "获取小节或练习中的题目（用于编辑）")
    @Parameters({
            @Parameter(name = "id", description = "课程id")
    })
    public List<CataSimpleSubjectVO> getSuject(@PathVariable("id") Long id) {
        return courseCatalogueDraftService.getSuject(id);
    }

    @GetMapping("teachers/{id}")
    @Operation(summary = "查询课程相关的老师信息")
    @Parameters({
            @Parameter(name = "id", description = "课程id"),
            @Parameter(name = "see", description = "是否是用于查看页面查看数据，默认是查看,如果不是界面查看数据就是编辑页面使用")
    })
    public List<CourseTeacherVO> teacher(@PathVariable("id") Long id,
                                         @RequestParam(value = "see", required = false, defaultValue = "1") Boolean see) {
        return courseTeacherDraftService.queryTeacherOfCourse(id, see);
    }

    @PostMapping("teachers/save")
    @Operation(summary = "保存老师信息")
    public void teachersSave(@RequestBody @Validated CourseTeacherSaveDTO courseTeacherSaveDTO) {
        courseTeacherDraftService.save(courseTeacherSaveDTO);
    }


    @PostMapping("upShelf")
    @Operation(summary = "课程上架")
    public void upShelf(@RequestBody @Validated CourseIdDTO courseIdDTO) {
        courseDraftService.upShelf(courseIdDTO.getId());
    }

    @GetMapping("checkBeforeUpShelf/{id}")
    @Operation(summary = "课程上架前校验")
    public void checkBeforeUpShelf(@PathVariable("id") Long id){
        courseDraftService.checkBeforeUpShelf(id);
    }

    @PostMapping("downShelf")
    @Operation(summary = "课程下架")
    public void downShelf(@RequestBody @Validated CourseIdDTO courseIdDTO) {
        courseDraftService.downShelf(courseIdDTO.getId());
    }

    /**
     * 先去删除加上数据删除后，再去删除草稿
     *
     * @param id
     */
    @DeleteMapping("delete/{id}")
    @Operation(summary = "课程删除")
    @Parameters({
            @Parameter(name = "id", description = "id")
    })
    public void deleteById(@PathVariable("id") Long id) {
        courseService.delete(id);
    }

    @Operation(summary = "根根条件列表获取课程信息")
    @GetMapping("/simpleInfo/list")
    public List<CourseSimpleInfoDTO> getSimpleInfoList(CourseSimpleInfoListDTO courseSimpleInfoListDTO) {
        return courseService.getSimpleInfoList(courseSimpleInfoListDTO);
    }

    @Operation(summary = "根据课程id，查询所有章节的序号")
    @GetMapping("/catas/index/list/{id}")
    @Parameters(
            @Parameter(name = "id", description = "课程id")
    )
    public List<CataSimpleInfoVO> catasIndexList(@PathVariable("id") Long id) {
        return courseCatalogueService.getCatasIndexList(id);
    }

    @Operation(summary = "生成练习id")
    @GetMapping("generator")
    public CourseCataIdVO generator() {
        return new CourseCataIdVO(IdWorker.getId());
    }

    @Operation(summary = "管理端课程搜索接口")
    @GetMapping("/page")
    public PageDTO<CoursePageVO> queryForPage(CoursePageQuery coursePageQuery) {
        if(CourseStatus.NO_UP_SHELF.equals(coursePageQuery.getStatus()) ||
        CourseStatus.DOWN_SHELF.equals(coursePageQuery.getStatus())){
            //待上架已下架查询草稿
            return courseDraftService.queryForPage(coursePageQuery);
        }else {
            //已上架已完结查询正式数据
            return courseService.queryForPage(coursePageQuery);
        }
    }

    @Operation(summary = "校验课程名称是否已经存在")
    @GetMapping("/checkName")
    @Parameters({
            @Parameter(name = "id", description = "id"),
            @Parameter(name = "name", description = "课程名称")
    })
    public NameExistVO checkNameExist(@RequestParam(value = "id",required = false) Long id,
                                      @RequestParam(value = "name") String name){
        return courseService.checkName(name, id);
    }

    @Operation(summary = "查询课程基本信息、目录、学习进度")
    @GetMapping("/{id}/catalogs")
    public CourseAndSectionVO queryCourseAndCatalogById(@PathVariable("id") Long courseId){
        return courseService.queryCourseAndCatalogById(courseId);
    }
}
