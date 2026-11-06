package com.tianji.learning.service.lesson.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CatalogueDTO;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.api.dto.leanring.LearningRecordDTO;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.exceptions.DbException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.LearningRecordFormDTO;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.enums.LessonStatus;
import com.tianji.learning.enums.SectionType;
import com.tianji.learning.mapper.lesson.LearningRecordMapper;
import com.tianji.learning.mq.message.LearningPointsMessage;
import com.tianji.learning.service.lesson.ILearningLessonService;
import com.tianji.learning.service.lesson.ILearningRecordService;
import com.tianji.learning.service.points.IPointsMessageService;
import com.tianji.learning.utils.LearningRecordDelayTaskHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * <p>
 * 学习记录表 服务实现类
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningRecordServiceImpl extends ServiceImpl<LearningRecordMapper, LearningRecord>
        implements ILearningRecordService {

    private final ILearningLessonService learningLessonService;

    private final CourseClient courseClient;

    private final LearningRecordDelayTaskHandler taskHandler;

    /** 学完小节时与学习记录一起保存待发送积分消息，提交后由任务投递。 */
    private final IPointsMessageService pointsMessageService;


    @Override
    public LearningLessonDTO queryLearningRecordByCourse(Long courseId) {
        //1、获取登录用户
        Long userId = UserContext.getUser();
        //2、查询课表
        LearningLesson lesson = learningLessonService.queryByUserIdAndCourseId(userId, courseId);
        if (lesson==null){
            return null;
        }
        //3、查询学习记录
        List<LearningRecord> records = lambdaQuery().eq(LearningRecord::getLessonId, lesson.getId()).list();
        //4、封装结果
        LearningLessonDTO dto = new LearningLessonDTO();
        dto.setId(lesson.getId());
        dto.setLatestSectionId(lesson.getLatestSectionId());
        dto.setRecords(BeanUtils.copyList(records, LearningRecordDTO.class));
        return dto;
    }

    @Override
    @Transactional
    public void addLearningRecord(LearningRecordFormDTO recordDTO) {
        //1、获取登录用户
        Long userId = UserContext.getUser();
        //2、校验课表：必须存在、属于当前用户、且未失效未过期
        LearningLesson lesson = learningLessonService.getById(recordDTO.getLessonId());
        checkLesson(lesson, userId);
        CourseFullInfoDTO courseInfo = checkSection(lesson, recordDTO);
        //3、处理学习记录，返回值表示本次提交是否「首次学完」该小节
        boolean finished;
        if (SectionType.EXAM.equals(recordDTO.getSectionType())) {
            //3.1、考试：提交即判定学完
            finished = handleExamRecord(userId, recordDTO);
        } else {
            //3.2、视频：播放进度超过50%才判定学完
            finished = handleVideoRecord(userId, recordDTO);
        }
        //4、更新课表
        if (finished) {
            //4.1、首次学完：累计已学小节数、流转课程状态、更新最近学习信息
            handleLessonFinished(lesson, recordDTO, courseInfo);
            //4.2、积分消息与学习数据同事务保存；发送失败、进程中断都能从数据库重试。
            pointsMessageService.saveMessage("learning:" + recordDTO.getLessonId() + ":" + recordDTO.getSectionId(),
                    MqConstants.Key.LEARN_SECTION,
                    LearningPointsMessage.of(userId, recordDTO.getLessonId(), recordDTO.getSectionId()));
        }
        //未学完：课表的「最近学习小节/时间」不在同步链路里更新，
        //由延迟任务在播放进度落库时一并写入，避免每次提交都产生一次课表UPDATE
    }

    /**
     * 校验课表是否可以提交学习记录：存在、归属当前用户、未失效未过期
     */
    private void checkLesson(LearningLesson lesson, Long userId) {
        if (lesson == null) {
            throw new BizIllegalException("课表不存在，无法提交学习记录");
        }
        if (!Objects.equals(lesson.getUserId(), userId)) {
            // 防止越权提交他人课表的学习记录，刷学习进度和学习积分
            log.warn("用户{}尝试提交课表{}的学习记录，已拦截", userId, lesson.getId());
            throw new BizIllegalException("无权提交该课程的学习记录");
        }
        if (LessonStatus.EXPIRED.equalsValue(lesson.getStatus())) {
            throw new BizIllegalException("课程已失效，无法提交学习记录");
        }
        if (lesson.getExpireTime() != null && lesson.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BizIllegalException("课程已过期，无法提交学习记录");
        }
    }

    /**
     * 用课程服务的真实目录校验小节归属和类型，视频完成条件使用服务端时长，避免伪造提交刷分。
     */
    private CourseFullInfoDTO checkSection(LearningLesson lesson, LearningRecordFormDTO recordDTO) {
        CourseFullInfoDTO course = courseClient.getCourseInfoById(lesson.getCourseId(), true, false);
        if (course == null || course.getChapters() == null) {
            throw new BizIllegalException("课程目录不存在，无法提交学习记录");
        }
        CatalogueDTO target = null;
        for (CatalogueDTO chapter : course.getChapters()) {
            if (chapter.getSections() == null) {
                continue;
            }
            for (CatalogueDTO section : chapter.getSections()) {
                if (Objects.equals(section.getId(), recordDTO.getSectionId())) {
                    target = section;
                    break;
                }
            }
            if (target != null) {
                break;
            }
        }
        if (target == null) {
            throw new BizIllegalException("小节不存在或不属于当前课程");
        }
        // 课程目录类型 2=视频小节、3=练习；学习提交类型 1=VIDEO、2=EXAM。
        boolean video = Integer.valueOf(2).equals(target.getType());
        boolean exam = Integer.valueOf(3).equals(target.getType());
        if ((!video || recordDTO.getSectionType() != SectionType.VIDEO)
                && (!exam || recordDTO.getSectionType() != SectionType.EXAM)) {
            throw new BizIllegalException("提交的小节类型与课程目录不一致");
        }
        if (video) {
            recordDTO.setDuration(target.getMediaDuration());
        }
        return course;
    }

    /**
     * 处理视频学习记录
     *
     * @return 本次提交是否「首次学完」该小节
     */
    private boolean handleVideoRecord(Long userId, LearningRecordFormDTO recordDTO) {
        //1、查询该小节已有的学习记录
        LearningRecord old = queryRecord(recordDTO.getLessonId(), recordDTO.getSectionId());
        //2、本次提交是否达到了完成条件
        boolean reachedEnd = isVideoFinished(recordDTO);
        //3、没有记录：新增（用户第一次提交该小节的播放进度）
        if (old == null) {
            LearningRecord record = new LearningRecord()
                    .setLessonId(recordDTO.getLessonId())
                    .setSectionId(recordDTO.getSectionId())
                    .setUserId(userId)
                    .setMoment(safeMoment(recordDTO.getMoment()))
                    .setFinished(reachedEnd)
                    .setCreateTime(commitTimeOf(recordDTO))
                    .setFinishTime(reachedEnd ? commitTimeOf(recordDTO) : null);
            if (!save(record)) {
                throw new DbException("新增学习记录失败！");
            }
            return reachedEnd;
        }
        //4、已学完的小节再次提交（回看）：进度只写缓存，由延迟任务合并后落库
        if (Boolean.TRUE.equals(old.getFinished())) {
            addRecordDelayTask(old, recordDTO);
            return false;
        }
        //5、首次学完：条件更新（finished = 0）保证并发提交时只有一次能成功，避免课表重复计数
        if (reachedEnd) {
            boolean success = lambdaUpdate()
                    .set(LearningRecord::getMoment, safeMoment(recordDTO.getMoment()))
                    .set(LearningRecord::getFinished, true)
                    .set(LearningRecord::getFinishTime, commitTimeOf(recordDTO))
                    .eq(LearningRecord::getId, old.getId())
                    .eq(LearningRecord::getFinished, false)
                    .update();
            if (success) {
                // 学完状态必须立即落库，同时清掉缓存，避免延迟任务再拿旧进度把它覆盖回去
                taskHandler.cleanRecordCache(recordDTO.getLessonId(), recordDTO.getSectionId());
                log.info("用户{}学完视频小节：lessonId={}, sectionId={}, moment={}",
                        userId, recordDTO.getLessonId(), recordDTO.getSectionId(), recordDTO.getMoment());
            }
            return success;
        }
        //6、仍未学完：进度只写缓存，由延迟任务合并后落库
        addRecordDelayTask(old, recordDTO);
        return false;
    }

    /**
     * 播放进度只写Redis缓存并投递延迟检测任务，由延迟任务合并后落库
     * <p>
     * 注意：缓存里只存 id/moment/finished，lessonId 与 sectionId 必须显式补上
     * —— 下一次查询会优先命中缓存，那时这两个字段是缺失的
     */
    private void addRecordDelayTask(LearningRecord old, LearningRecordFormDTO recordDTO) {
        LearningRecord record = new LearningRecord()
                .setId(old.getId())
                .setLessonId(recordDTO.getLessonId())
                .setSectionId(recordDTO.getSectionId())
                .setMoment(safeMoment(recordDTO.getMoment()))
                .setFinished(Boolean.TRUE.equals(old.getFinished()));
        taskHandler.addLearningRecordTask(record);
    }

    /**
     * 处理考试学习记录
     *
     * @return 本次提交是否「首次学完」该小节
     */
    private boolean handleExamRecord(Long userId, LearningRecordFormDTO recordDTO) {
        //1、查询该小节已有的学习记录，避免重复提交产生多条记录
        LearningRecord old = queryRecord(recordDTO.getLessonId(), recordDTO.getSectionId());
        //2、已存在：只补完成状态
        if (old != null) {
            if (Boolean.TRUE.equals(old.getFinished())) {
                return false;
            }
            boolean success = lambdaUpdate()
                    .set(LearningRecord::getMoment, safeMoment(recordDTO.getMoment()))
                    .set(LearningRecord::getFinished, true)
                    .set(LearningRecord::getFinishTime, commitTimeOf(recordDTO))
                    .eq(LearningRecord::getId, old.getId())
                    .eq(LearningRecord::getFinished, false)
                    .update();
            if (success) {
                log.info("用户{}学完考试小节：lessonId={}, sectionId={}",
                        userId, recordDTO.getLessonId(), recordDTO.getSectionId());
            }
            return success;
        }
        //3、不存在：考试提交即判定学完
        LearningRecord record = new LearningRecord()
                .setLessonId(recordDTO.getLessonId())
                .setSectionId(recordDTO.getSectionId())
                .setUserId(userId)
                .setMoment(safeMoment(recordDTO.getMoment()))
                .setFinished(true)
                .setCreateTime(commitTimeOf(recordDTO))
                .setFinishTime(commitTimeOf(recordDTO));
        if (!save(record)) {
            throw new DbException("新增考试记录失败！");
        }
        log.info("用户{}学完考试小节：lessonId={}, sectionId={}",
                userId, recordDTO.getLessonId(), recordDTO.getSectionId());
        return true;
    }

    /**
     * 首次学完后更新课表：已学小节数+1、课程状态流转、更新最近学习信息
     */
    private void handleLessonFinished(LearningLesson lesson, LearningRecordFormDTO recordDTO, CourseFullInfoDTO courseInfo) {
        //1、复用已校验的课程总小节数，用于判断课程是否已全部学完
        if (courseInfo == null || courseInfo.getSectionNum() == null) {
            throw new BizIllegalException("课程信息不存在，无法更新学习进度！");
        }
        //2、计算学完该小节后的已学小节数，以及课表应有的状态
        int learnedSections = lesson.getLearnedSections() == null ? 0 : lesson.getLearnedSections();
        boolean allLearned = learnedSections + 1 >= courseInfo.getSectionNum();
        Integer status = null;
        if (allLearned) {
            status = LessonStatus.FINISHED.getValue();
        } else if (learnedSections == 0) {
            // 第一个学完的小节：未学习 -> 学习中
            status = LessonStatus.LEARNING.getValue();
        }
        //3、更新课表：learned_sections 用SQL原子自增，避免并发下的丢更新
        boolean success = learningLessonService.lambdaUpdate()
                .setSql("learned_sections = learned_sections + 1")
                .set(status != null, LearningLesson::getStatus, status)
                .set(LearningLesson::getLatestSectionId, recordDTO.getSectionId())
                .set(LearningLesson::getLatestLearnTime, commitTimeOf(recordDTO))
                .eq(LearningLesson::getId, lesson.getId())
                .update();
        if (!success) {
            throw new DbException("更新课表学习进度失败！");
        }
        log.info("用户{}的课表更新完成：lessonId={}, 已学小节数={}, 课程是否学完={}",
                lesson.getUserId(), lesson.getId(), learnedSections + 1, allLearned);
    }

    /**
     * 查询指定小节的学习记录，缓存优先
     * <p>
     * 缓存里可能有尚未落库的最新播放进度，先读缓存才能保证续播位置和完成判定拿到最新值；
     * 表中 (lesson_id, section_id) 没有唯一约束，取最新一条，避免历史脏数据导致 TooManyResultsException
     */
    private LearningRecord queryRecord(Long lessonId, Long sectionId) {
        //1、先查缓存：延迟任务还没落库的播放进度在这里
        LearningRecord cache = taskHandler.readRecordCache(lessonId, sectionId);
        if (cache != null) {
            return cache;
        }
        //2、缓存未命中，查数据库
        LearningRecord record = lambdaQuery()
                .eq(LearningRecord::getLessonId, lessonId)
                .eq(LearningRecord::getSectionId, sectionId)
                .orderByDesc(LearningRecord::getId)
                .last("limit 1")
                .one();
        //3、回写缓存，供后续提交比对；记录不存在时跳过
        if (record != null) {
            taskHandler.writeRecordCache(record);
        }
        return record;
    }

    /**
     * 视频是否学完：播放进度超过50%
     */
    private boolean isVideoFinished(LearningRecordFormDTO recordDTO) {
        Integer duration = recordDTO.getDuration();
        Integer moment = recordDTO.getMoment();
        if (duration == null || duration <= 0 || moment == null) {
            // 缺少视频时长或进度时无法判定，按未学完处理
            return false;
        }
        // 用long运算，避免moment接近int上限时溢出
        return moment * 2L >= duration;
    }

    private Integer safeMoment(Integer moment) {
        return moment == null ? 0 : moment;
    }

    private LocalDateTime commitTimeOf(LearningRecordFormDTO recordDTO) {
        // 提交时间由客户端传入，缺失时用服务端当前时间兜底
        return recordDTO.getCommitTime() == null ? LocalDateTime.now() : recordDTO.getCommitTime();
    }

}
