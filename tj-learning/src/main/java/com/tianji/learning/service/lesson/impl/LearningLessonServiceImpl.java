package com.tianji.learning.service.lesson.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.client.course.CatalogueClient;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.IdAndNumDTO;
import com.tianji.api.dto.course.CataSimpleInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.common.utils.AssertUtils;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.vo.LearningLessonVO;
import com.tianji.learning.domain.vo.LearningPlanPageVO;
import com.tianji.learning.domain.vo.LearningPlanVO;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.enums.PlanStatus;
import com.tianji.learning.mapper.lesson.LearningLessonMapper;
import com.tianji.learning.mapper.lesson.LearningRecordMapper;
import com.tianji.learning.service.lesson.ILearningLessonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 学生课程表 服务实现类
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class LearningLessonServiceImpl extends ServiceImpl<LearningLessonMapper, LearningLesson>
        implements ILearningLessonService {

    private final CourseClient courseClient;
    private final CatalogueClient catalogueClient;
    /** 查学习记录表：本方法的「本周完成小节数」两处统计都要用它（selectCount + countLearnedSections） */
    private final LearningRecordMapper recordMapper;

    @Override
    @Transactional
    public void addUserLessons(Long userId, List<Long> courseIds) {
        List<CourseSimpleInfoDTO> simpleInfoList = courseClient.getSimpleInfoList(courseIds);
        if (CollUtils.isEmpty(simpleInfoList)) {
            log.error("课程为空！");
            return;
        }
        // 幂等处理：先查出该用户已报名的课程，避免重复插入撞唯一索引 idx_user_id(user_id, course_id)
        Set<Long> enrolledCourseIds = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .in(LearningLesson::getCourseId, courseIds)
                .list().stream()
                .map(LearningLesson::getCourseId)
                .collect(Collectors.toSet());

        List<LearningLesson> list = new ArrayList<>(courseIds.size());
        for (CourseSimpleInfoDTO infoDTO : simpleInfoList) {
            if (enrolledCourseIds.contains(infoDTO.getId())) {
                // 已报名，跳过，保证消息重复投递（重试/重发）时不会失败
                log.info("用户{}已存在课程{}的学习记录，跳过", userId, infoDTO.getId());
                continue;
            }
            LearningLesson learningLesson = new LearningLesson();
            Integer validDuration = infoDTO.getValidDuration();
            if (validDuration != null && validDuration > 0) {
                LocalDateTime now = LocalDateTime.now();
                learningLesson.setCreateTime(now);
                learningLesson.setExpireTime(now.plusMonths(validDuration));
            }
            learningLesson.setUserId(userId);
            learningLesson.setCourseId(infoDTO.getId());
            list.add(learningLesson);
        }
        if (CollUtils.isEmpty(list)) {
            return;
        }
        saveBatch(list);
    }

    @Override
    @Transactional
    public void deleteUserLessons(Long userId, List<Long> courseIds) {
        if (userId == null || CollUtils.isEmpty(courseIds)) {
            return;
        }
        // 不能传 lambdaQuery()（ChainWrapper），remove 需要普通 LambdaQueryWrapper
        remove(new LambdaQueryWrapper<LearningLesson>()
                .eq(LearningLesson::getUserId, userId)
                .in(LearningLesson::getCourseId, courseIds));
    }

    @Override
    public PageDTO<LearningLessonVO> queryMyLessons(PageQuery query) {
        Long userId = UserContext.getUser();
        Page<LearningLesson> page = lambdaQuery().eq(LearningLesson::getUserId, userId)
                .page(query.toMpPage("latest_learn_time", false));
        List<LearningLesson> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        Set<Long> cIds = records.stream().map(LearningLesson::getCourseId).collect(Collectors.toSet());
        // 课程被删除时该id不会出现在结果中，属于脏数据，按条跳过，不影响课表本身返回
        List<CourseSimpleInfoDTO> simpleInfoList = courseClient.getSimpleInfoList(cIds);
        Map<Long, CourseSimpleInfoDTO> cMap = CollUtils.isEmpty(simpleInfoList)
                ? CollUtils.emptyMap()
                : simpleInfoList.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));

        // 批量查一次最近学习的小节信息，避免在循环里逐条 Feign（一页 20 条就是 20 次远程调用）
        Map<Long, CataSimpleInfoDTO> sectionMap = querySectionMap(
                records.stream().map(LearningLesson::getLatestSectionId).collect(Collectors.toList()));

        List<LearningLessonVO> list = new ArrayList<>(records.size());
        for (LearningLesson record : records) {
            LearningLessonVO vo = toLessonVO(record);
            CourseSimpleInfoDTO infoDTO = cMap.get(record.getCourseId());
            if (infoDTO == null) {
                log.warn("课程信息缺失，lessonId={}, courseId={}", record.getId(), record.getCourseId());
                continue;
            }
            fillCourseInfo(vo, infoDTO);
            fillLatestSection(vo, record, sectionMap);
            list.add(vo);
        }
        return PageDTO.of(page, list);
    }

    @Override
    public LearningLessonVO queryMyCurrentLesson() {
        Long userId = UserContext.getUser();
        // 优先查学习中的课程，按最近学习时间倒序取 1 条
        LearningLesson lesson = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getStatus, LessonStatus.LEARNING.getValue())
                .orderByDesc(LearningLesson::getLatestLearnTime)
                .last("limit 1")
                .one();
        if (lesson == null) {
            return null;
        }
        LearningLessonVO vo = buildLessonVO(lesson, true);
        if (vo == null) {
            return null;
        }
        // 统计用户课表中的课程总数
        Long courseAmount = lambdaQuery().eq(LearningLesson::getUserId, userId).count();
        vo.setCourseAmount(courseAmount.intValue());
        return vo;
    }

    @Override
    public LearningLessonVO queryLessonByCourseId(Long courseId) {
        Long userId = UserContext.getUser();
        LearningLesson lesson = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        if (lesson == null) {
            return null;
        }
        return buildLessonVO(lesson, true);
    }

    @Override
    public void deleteCourseFromLesson(Long courseId) {
        Long userId = UserContext.getUser();
        remove(new LambdaQueryWrapper<LearningLesson>()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId));
    }

    @Override
    public Long isLessonValid(Long courseId) {
        Long userId = UserContext.getUser();
        LearningLesson lesson = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        if (lesson == null) {
            return null;
        }
        // 已过期则视为无效
        if (LessonStatus.EXPIRED.equalsValue(lesson.getStatus())) {
            return null;
        }
        if (lesson.getExpireTime() != null && lesson.getExpireTime().isBefore(LocalDateTime.now())) {
            return null;
        }
        return lesson.getId();
    }

    @Override
    public Integer countLearningLessonByCourse(Long courseId) {
        return lambdaQuery().eq(LearningLesson::getCourseId, courseId).count().intValue();
    }

    @Override
    public LearningLesson queryByUserIdAndCourseId(Long userId, Long courseId) {
        LearningLesson lesson = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        return lesson;
    }

    @Override
    public void createLearningPlan(Long courseId, Integer freq) {
        //1、获取登录用户
        Long userId = UserContext.getUser();
        //2、查询课表：按 userId + courseId 查，别人的课程查不到，天然带归属校验
        LearningLesson lesson = queryByUserIdAndCourseId(userId, courseId);
        AssertUtils.isNotNull(lesson, "课程信息不存在！");
        //3、更新学习计划：写入每周学习频率
        LearningLesson update = new LearningLesson()
                .setId(lesson.getId())
                .setWeekFreq(freq);
        if (PlanStatus.NO_PLAN.equalsValue(lesson.getPlanStatus())) {
            //3.1、原本没有计划的课表，流转为「计划进行中」
            update.setPlanStatus(PlanStatus.PLAN_RUNNING.getValue());
        }
        //4、保存（MP 只更新非 null 字段，不会覆盖课表其它信息）
        updateById(update);
    }

    @Override
    public LearningPlanPageVO queryMyPlans(PageQuery query) {
        LearningPlanPageVO result = new LearningPlanPageVO();
        // 1、当前登录用户（网关解析 token 后写进 user-info 请求头，拦截器再塞进 ThreadLocal）
        Long userId = UserContext.getUser();

        /* ==================== 2、本周边界 ====================
         * 口径：本周一 00:00:00 <= finish_time < 下周一 00:00:00（左闭右开）
         *
         * 为什么不用 DateUtils.getWeekEndTime()：它内部是 now.plusDays(8 - dayOfWeek) + LocalTime.MAX，
         * 今天周四(dayOfWeek=4) 会算成「下周一 23:59:59」，等于把下周一整天也圈进本周。
         * 直接 begin + 1 周语义最清楚，也不依赖 dayOfWeek 的偏移量。
         */
        LocalDateTime begin = DateUtils.getWeekBeginTime(LocalDate.now());
        LocalDateTime end = begin.plusWeeks(1);

        /* ==================== 3、全局统计（与分页无关，只查一次） ====================
         * 这三个数字是页面顶部的概览，翻页时不应该变，
         * 所以放在分页查询之前单独算，而不是塞进下面组装 VO 的循环里。
         */

        // 3.1、本周已学完的小节数（只算「计划内课程」的完成记录）
        //      必须和 3.2 的分母同源：分母限定在 plan_status = 1 的课上，
        //      分子如果不管课程范围，把「没制定计划的课」的完成数也收进来，
        //      就会出现「完成数 > 计划数」这种解释不通的数字。
        //      时间左闭右开：finish_time >= 本周一 00:00:00 且 < 下周一 00:00:00
        result.setWeekFinished(recordMapper.countWeekFinished(userId, begin, end));

        // 3.2、本周计划学习的小节总数 = 本周有效计划课程的 week_freq 之和
        //      这里要传 begin/end：SQL 得靠「本周有没有完成记录」来判断一门课是不是「本周刚学完」。
        //      如果只按 status in (0,1) 算，一门课学完(status→2)的瞬间就会从分母里掉出去，
        //      本周计划量凭空缩水，看起来像越学越少。
        //      查不到时由 IFNULL 兜成 0，所以不会是 null
        result.setWeekTotalPlan(getBaseMapper().queryTotalPlan(userId, begin, end));

        // 3.3、本周积分：积分相关的库表要 day07 才建，这里先显式占位 0。
        //      前端是 {{ totalPoints || 0 }}，给 null 也能显示 0，但显式写出来更清楚，也免得以后忘补。
        result.setWeekPoints(0);

        /* ==================== 4、分页查课表 ==================== */
        // 4.1、只查「有计划」且「未结束」的课：
        //      - plan_status = 1   没制定计划的课 week_freq 是 NULL，显示出来前端会渲染成 "0/null"
        //      - status in (0,1)   已学完(2) / 已失效(3) 的课不再算作进行中的计划
        //      toMpPage("latest_learn_time", false) = 按最近学习时间排序，false 表示倒序
        Page<LearningLesson> p = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getPlanStatus, PlanStatus.PLAN_RUNNING)
                .in(LearningLesson::getStatus, LessonStatus.NOT_BEGIN, LessonStatus.LEARNING)
                .page(query.toMpPage("latest_learn_time", false));
        List<LearningLesson> records = p.getRecords();
        if (CollUtils.isEmpty(records)) {
            // 一条都没有：只带统计数字返回，list 留空。
            // 用讲义版 VO 已有的 pageInfo(PageDTO)，别照 master 的 emptyPage(Page) —— 这个类里没有那方法
            return result.pageInfo(PageDTO.empty(p));
        }

        /* ==================== 5、批量补数据（重点：两次批量，杜绝 N+1） ==================== */

        // 5.1、课程信息：一次 Feign 把整页课程id查回来。
        //      courseName 和 sections 需要的 name / sectionNum 都在 CourseSimpleInfoDTO 里，
        //      所以不需要再调 catalogueClient。别放进 for 里逐个查 —— 一页 N 条就是 N 次远程调用。
        Set<Long> courseIds = records.stream()
                .map(LearningLesson::getCourseId)
                .collect(Collectors.toSet());
        List<CourseSimpleInfoDTO> simpleInfoList = courseClient.getSimpleInfoList(courseIds);
        // 课程被删除时该 id 不会出现在结果里（脏数据），下面按 key 取值时判 null 跳过即可
        Map<Long, CourseSimpleInfoDTO> cMap = CollUtils.isEmpty(simpleInfoList)
                ? CollUtils.emptyMap()
                : simpleInfoList.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));

        // 5.2、本周每门课完成的小节数：一次 GROUP BY 查回来。
        // key 是 lessonId 不是 courseId！learning_record.lesson_id 存的是 learning_lesson.id
        Map<Long, Integer> countMap = IdAndNumDTO.toMap(
                recordMapper.countLearnedSections(userId, begin, end));

        /* ==================== 6、组装 VO ==================== */
        List<LearningPlanVO> voList = new ArrayList<>(records.size());
        for (LearningLesson r : records) {
            // 6.1、课表里同名字段（id / courseId / weekFreq / learnedSections / latestLearnTime）
            //      整体拷过来 —— 字段名和 VO 一致，所以可以用课程自己的 BeanUtils 省掉手写 set
            LearningPlanVO vo = BeanUtils.copyBean(r, LearningPlanVO.class);
            // 6.2、课程名 + 章节总数（来自课程服务）
            CourseSimpleInfoDTO cInfo = cMap.get(r.getCourseId());
            if (cInfo != null) {
                vo.setCourseName(cInfo.getName());
                vo.setSections(cInfo.getSectionNum());
            } else {
                log.warn("课程信息缺失，lessonId={}, courseId={}", r.getId(), r.getCourseId());
            }
            // 6.3、本周完成数：本周一节没学时这条课不会出现在 countMap 里，用 getOrDefault 兜 0；
            //      直接用 get() 会拿到 null，前端渲染成空白
            vo.setWeekLearnedSections(countMap.getOrDefault(r.getId(), 0));
            voList.add(vo);
        }
        // total / pages 来自分页对象，不是 voList.size()
        return result.pageInfo(p.getTotal(), p.getPages(), voList);
    }


    /**
     * 组装课表 VO：填充课程信息，可选填充最近学习小节
     */
    private LearningLessonVO buildLessonVO(LearningLesson lesson, boolean withLatestSection) {
        LearningLessonVO vo = toLessonVO(lesson);
        List<CourseSimpleInfoDTO> infoList = courseClient.getSimpleInfoList(
                CollUtils.singletonList(lesson.getCourseId()));
        if (CollUtils.isEmpty(infoList)) {
            log.warn("课程信息缺失，lessonId={}, courseId={}", lesson.getId(), lesson.getCourseId());
            return null;
        }
        fillCourseInfo(vo, infoList.get(0));
        if (withLatestSection) {
            fillLatestSection(vo, lesson, querySectionMap(CollUtils.singletonList(lesson.getLatestSectionId())));
        }
        return vo;
    }

    private LearningLessonVO toLessonVO(LearningLesson lesson) {
        LearningLessonVO vo = BeanUtils.copyBean(lesson, LearningLessonVO.class);
        vo.setStatus(LessonStatus.of(lesson.getStatus()));
        vo.setPlanStatus(PlanStatus.of(lesson.getPlanStatus()));
        return vo;
    }

    private void fillCourseInfo(LearningLessonVO vo, CourseSimpleInfoDTO infoDTO) {
        vo.setCourseName(infoDTO.getName());
        vo.setCourseCoverUrl(infoDTO.getCoverUrl());
        vo.setSections(infoDTO.getSectionNum());
    }

    /**
     * 批量查询小节信息，返回「小节id -> 小节信息」的 Map；集合为空或查不到时返回空 Map
     */
    private Map<Long, CataSimpleInfoDTO> querySectionMap(List<Long> sectionIds) {
        if (CollUtils.isEmpty(sectionIds)) {
            return CollUtils.emptyMap();
        }
        // 从未学习过的课表记录 latestSectionId 为空，先过滤掉再查
        Set<Long> ids = sectionIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (CollUtils.isEmpty(ids)) {
            return CollUtils.emptyMap();
        }
        List<CataSimpleInfoDTO> catalogues = catalogueClient.batchQueryCatalogue(ids);
        if (CollUtils.isEmpty(catalogues)) {
            return CollUtils.emptyMap();
        }
        return catalogues.stream()
                .filter(cata -> cata.getId() != null)
                .collect(Collectors.toMap(CataSimpleInfoDTO::getId, cata -> cata, (older, newer) -> older));
    }

    private void fillLatestSection(LearningLessonVO vo, LearningLesson lesson, Map<Long, CataSimpleInfoDTO> sectionMap) {
        Long latestSectionId = lesson.getLatestSectionId();
        if (latestSectionId == null || sectionMap == null || sectionMap.isEmpty()) {
            return;
        }
        // 小节被删除时查不到，保持为空即可
        CataSimpleInfoDTO cata = sectionMap.get(latestSectionId);
        if (cata == null) {
            return;
        }
        vo.setLatestSectionName(cata.getName());
        vo.setLatestSectionIndex(cata.getCIndex());
    }



}
