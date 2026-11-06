package com.tianji.aigc.knowledge.course;

import com.tianji.api.client.course.CourseClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.Assert;

import java.time.LocalDateTime;

/** 每次处理一页；首轮初始化，以后的全量巡检修复漏消息和售卖时间变化。 */
@Slf4j
public class CourseIndexSyncTask {
    private final CourseClient courses;
    private final CourseIndexSyncService sync;
    private final int pageSize;
    private long afterId;
    /** null 表示分页未停滞；0L 表示停滞在第一页。 */
    private volatile Long stalledAfterId;
    /** 分页处理完成时间，不代表该页所有课程都同步成功。 */
    private volatile LocalDateTime lastPageCompletedAt;
    private volatile int lastPageSyncFailures;
    private int consecutivePageFailures;

    public CourseIndexSyncTask(CourseClient courses, CourseIndexSyncService sync, int pageSize) {
        Assert.isTrue(pageSize >= 1 && pageSize <= 200, "索引分页大小必须在 1 到 200 之间");
        this.courses = courses;
        this.sync = sync;
        this.pageSize = pageSize;
    }

    @Scheduled(initialDelayString = "${tj.ai.course-retrieval.sync.initial-delay-ms:5000}",
            fixedDelayString = "${tj.ai.course-retrieval.sync.fixed-delay-ms:60000}")
    public synchronized void reconcile() {
        try {
            var page = courses.querySemanticIndexPage(afterId, pageSize);
            Assert.state(page != null && page.size() <= pageSize, "课程分页响应无效");
            long last = afterId;
            for (var item : page) {
                Assert.state(item != null && item.getId() != null && item.getId() > last, "课程分页未按 ID 递增");
                last = item.getId();
            }
            int failed = 0;
            for (var item : page) {
                try {
                    sync.synchronize(item.getId());
                } catch (Exception failure) {
                    failed++;
                    // 单门失败不阻塞后续课程，下一轮巡检再次尝试。
                    log.warn("课程索引同步失败 courseId={}", item.getId(), failure);
                }
            }
            afterId = page.size() < pageSize ? 0 : last;
            lastPageCompletedAt = LocalDateTime.now();
            lastPageSyncFailures = failed;
            stalledAfterId = null;
            consecutivePageFailures = 0;
            log.info("课程索引巡检完成 count={}, failed={}, nextAfterId={}", page.size(), failed, afterId);
        } catch (Exception failure) {
            consecutivePageFailures++;
            stalledAfterId = afterId;
            if (consecutivePageFailures == 1 || consecutivePageFailures % 10 == 0) {
                log.error("课程索引巡检分页失败 afterId={}，连续失败 {} 次；若分页契约异常请检查课程服务排序",
                        afterId, consecutivePageFailures, failure);
            }
        }
    }

    public Long stalledAfterId() { return stalledAfterId; }

    public LocalDateTime lastPageCompletedAt() { return lastPageCompletedAt; }

    public int lastPageSyncFailures() { return lastPageSyncFailures; }
}
