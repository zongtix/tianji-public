package com.tianji.course.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Hidden;

import com.tianji.api.dto.course.*;
import com.tianji.common.utils.CollUtils;
import com.tianji.course.service.ICategoryService;
import com.tianji.course.service.ICourseCatalogueService;
import com.tianji.course.service.ICourseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内部服务接口调用
 *
 * @ClassName CourseInfoController
 * @Author wusongsong
 * @Date 2022/7/18 15:19
 * @Version
 **/
@RestController
@RequestMapping("course")
@Tag(name = "课程相关接口，内部调用")
public class CourseInfoController {

    @Hidden
    @GetMapping("/semantic-index")
    public List<CourseIndexInfoDTO> querySemanticIndexPage(
            @RequestParam(value = "afterId", defaultValue = "0") @Min(0) long afterId,
            @RequestParam(value = "size", defaultValue = "50") @Min(1) @Max(200) int size) {
        return courseService.querySemanticIndexPage(afterId, size);
    }

    @Hidden
    @GetMapping("/semantic-index/{id}")
    public CourseIndexInfoDTO getSemanticIndexInfo(@PathVariable("id") @Min(1) Long id) {
        return courseService.getSemanticIndexInfo(id);
    }

    @Autowired
    private ICourseCatalogueService courseCatalogueService;

    @Autowired
    private ICourseService courseService;

    @Autowired
    private ICategoryService categoryService;

    @GetMapping("infoByTeacherIds")
    @Operation(summary = "通过老师id获取老师负责的课程和出的题目数量")
    public List<SubNumAndCourseNumDTO> infoByTeacherIds(@RequestParam("teacherIds") List<Long> teacherIds) {

        if (CollUtils.isEmpty(teacherIds)) {
            return new ArrayList<>();
        }
        return courseService.countSubjectNumAndCourseNumOfTeacher(teacherIds);
    }

    /**
     * 根据小节id获取小节对应的mediaId和课程id
     *
     * @param sectionId 小节id
     * @return 小节对应的mediaId和课程id
     */
    @GetMapping("/section/{id}")
    @Parameter(name = "id", description = "小节id，不支持章id或者练习id查询")
    public SectionInfoDTO sectionInfo(@PathVariable("id") Long sectionId) {
        return courseCatalogueService.getSimpleSectionInfo(sectionId);
    }

    /**
     * 根据媒资Id列表查询媒资被引用的次数
     *
     * @param mediaIds 媒资id列表
     * @return 媒资id和媒资被引用的次数的列表
     */
    @GetMapping("/media/useInfo")
    public List<MediaQuoteDTO> mediaUserInfo(@RequestParam("mediaIds") List<Long> mediaIds) {
        return courseCatalogueService.countMediaUserInfo(mediaIds);
    }

    @GetMapping("/{id}/searchInfo")
    @Operation(summary = "课程上架时，需要查询课程信息，加入索引库")
    public CourseDTO getSearchInfo(@Parameter(description = "课程id") @PathVariable("id") Long id) {
        return courseService.getCourseDTOById(id);
    }

    @GetMapping("/{id}/baseInfo")
    @Operation(summary = "获取课程基本信息及正式课程内容")
    public CourseBaseInfoDTO getBaseInfoById(@PathVariable("id") Long id) {
        return courseService.getBaseInfoById(id);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取课程信息")
    @Parameters({
            @Parameter(name = "id", description = "获取课程信息"),
            @Parameter(name = "withCatalogue", description = "是否要查询目录信息"),
            @Parameter(name = "withTeachers", description = "是否查询课程老师信息")
    })
    public CourseFullInfoDTO getById(
            @PathVariable("id") Long id,
            @RequestParam(value = "withCatalogue", required = false) boolean withCatalogue,
            @RequestParam(value = "withTeachers", required = false) boolean withTeachers) {
        return courseService.getInfoById(id, withCatalogue, withTeachers);
    }


    @GetMapping("/getCateNameMap")
    @Hidden
    public Map<Long, String> queryByThirdCateIds(@RequestParam("thirdCateIdList") List<Long> thirdCateIdList) {
        return categoryService.queryByThirdCateIds(thirdCateIdList);
    }

    @GetMapping("/name")
    public List<Long> queryCoursesIdByName(@RequestParam("name") String name){
        return courseService.queryCourseIdByName(name);
    }
}
