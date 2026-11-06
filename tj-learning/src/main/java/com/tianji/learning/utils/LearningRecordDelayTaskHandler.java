package com.tianji.learning.utils;

import com.tianji.common.utils.JsonUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.mapper.lesson.LearningRecordMapper;
import com.tianji.learning.service.lesson.ILearningLessonService;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 播放进度的缓存与延迟检测任务处理器
 * <p>
 * 提交播放进度时不再直接写库，而是：
 * 1. 把最新进度写入 Redis 缓存
 * 2. 投递一个延迟任务到本地 DelayQueue
 * <p>
 * 延迟到期后再比对缓存中的 moment 是否还在变化：
 * - 还在变化（用户仍在播放）→ 放弃本次任务，等下一次提交再投递，避免频繁写库
 * - 不再变化（用户已离开）→ 本次进度落库，同时更新课表的最近学习小节与时间
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LearningRecordDelayTaskHandler {

    /**
     * 播放记录的缓存key模板：learning:record:{lessonId}
     * Hash结构，field为sectionId，value为 {id, moment, finished} 的JSON
     */
    private static final String RECORD_KEY_TEMPLATE = "learning:record:{}";

    /**
     * 延迟检测时长：播放进度停止变化超过该时长才落库
     */
    private static final Duration DELAY_TIME = Duration.ofSeconds(20);

    /**
     * 缓存过期时间：兜底，防止延迟任务丢失后缓存数据长期占用内存
     */
    private static final Duration CACHE_TTL = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;

    private final LearningRecordMapper recordMapper;

    private final ILearningLessonService lessonService;

    /**
     * 延迟队列：存放待检测的播放进度任务
     */
    private final DelayQueue<DelayTask<RecordTaskData>> queue = new DelayQueue<>();

    /**
     * 消费延迟任务的线程池
     */
    private final Executor taskExecutor = Executors.newFixedThreadPool(4);

    /**
     * 线程运行标记，容器关闭时置为false，让消费线程退出循环
     */
    private static volatile boolean begin = true;

    /**
     * 需求④：通过线程，异步执行DelayQueue中的延迟检测任务
     */
    @PostConstruct
    public void init() {
        CompletableFuture.runAsync(this::handleDelayTask, taskExecutor);
    }

    @PreDestroy
    public void destroy() {
        begin = false;
        log.debug("延迟任务停止执行！");
    }

    /**
     * 需求④：延迟检测任务的执行逻辑，检测播放进度是否变化，无变化则写入数据库
     */
    private void handleDelayTask() {
        while (begin) {
            try {
                // 1.获取到期的延迟任务
                DelayTask<RecordTaskData> task = queue.take();
                RecordTaskData data = task.getData();
                // 2.查询Redis缓存
                LearningRecord record = readRecordCache(data.getLessonId(), data.getSectionId());
                if (record == null) {
                    // 缓存已被清理（如该小节刚刚学完、进度已落库），无需处理
                    continue;
                }
                // 3.比较数据，moment值
                if (!Objects.equals(data.getMoment(), record.getMoment())) {
                    // 不一致，说明用户还在持续提交播放进度，放弃旧数据
                    continue;
                }
                // 4.一致，持久化播放进度数据到数据库
                // 4.1.更新学习记录的moment
                record.setFinished(null);
                recordMapper.updateById(record);
                // 4.2.更新课表最近学习信息
                LearningLesson lesson = new LearningLesson();
                lesson.setId(data.getLessonId());
                lesson.setLatestSectionId(data.getSectionId());
                lesson.setLatestLearnTime(LocalDateTime.now());
                lessonService.updateById(lesson);
            } catch (Exception e) {
                log.error("处理延迟任务发生异常", e);
            }
        }
    }

    /**
     * 需求①：添加播放记录到Redis，并添加一个延迟检测任务到DelayQueue
     */
    public void addLearningRecordTask(LearningRecord record) {
        // 1.添加数据到Redis缓存
        writeRecordCache(record);
        // 2.提交延迟任务到延迟队列 DelayQueue
        queue.add(new DelayTask<>(new RecordTaskData(record), DELAY_TIME));
    }

    /**
     * 写入播放记录缓存：不在需求①②③④之内，供本类与查询逻辑复用
     */
    public void writeRecordCache(LearningRecord record) {
        if (record == null || record.getId() == null) {
            return;
        }
        try {
            // 1.数据转换
            String json = JsonUtils.toJsonStr(new RecordCacheData(record));
            // 2.写入Redis
            String key = StringUtils.format(RECORD_KEY_TEMPLATE, record.getLessonId());
            redisTemplate.opsForHash().put(key, record.getSectionId().toString(), json);
            // 3.添加缓存过期时间
            redisTemplate.expire(key, CACHE_TTL);
        } catch (Exception e) {
            log.error("更新学习记录缓存异常", e);
        }
    }

    /**
     * 需求②：查询Redis缓存中的指定小节的播放记录
     *
     * @return 缓存中的播放记录，未命中返回null
     */
    public LearningRecord readRecordCache(Long lessonId, Long sectionId) {
        try {
            // 1.读取Redis数据
            String key = StringUtils.format(RECORD_KEY_TEMPLATE, lessonId);
            Object cacheData = redisTemplate.opsForHash().get(key, sectionId.toString());
            if (cacheData == null) {
                return null;
            }
            // 2.数据检查和转换
            return JsonUtils.toBean(cacheData.toString(), LearningRecord.class);
        } catch (Exception e) {
            log.error("缓存读取异常", e);
            return null;
        }
    }

    /**
     * 需求③：删除Redis缓存中的指定小节的播放记录
     */
    public void cleanRecordCache(Long lessonId, Long sectionId) {
        // 删除数据
        String key = StringUtils.format(RECORD_KEY_TEMPLATE, lessonId);
        redisTemplate.opsForHash().delete(key, sectionId.toString());
    }

    /**
     * 缓存中的数据：只保留落库和合并判断必需的最少字段，降低Redis存储量
     */
    @Data
    @NoArgsConstructor
    private static class RecordCacheData {
        private Long id;
        private Integer moment;
        private Boolean finished;

        public RecordCacheData(LearningRecord record) {
            this.id = record.getId();
            this.moment = record.getMoment();
            this.finished = record.getFinished();
        }
    }

    /**
     * 延迟任务中携带的数据：用于比对缓存中的进度是否还在变化
     */
    @Data
    @NoArgsConstructor
    private static class RecordTaskData {
        private Long lessonId;
        private Long sectionId;
        private Integer moment;

        public RecordTaskData(LearningRecord record) {
            this.lessonId = record.getLessonId();
            this.sectionId = record.getSectionId();
            this.moment = record.getMoment();
        }
    }
}
