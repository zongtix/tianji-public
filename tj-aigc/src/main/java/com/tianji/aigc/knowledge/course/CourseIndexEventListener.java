package com.tianji.aigc.knowledge.course;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.util.Assert;

import java.io.IOException;

public class CourseIndexEventListener {
    private final CourseIndexSyncService sync;

    public CourseIndexEventListener(CourseIndexSyncService sync) {
        this.sync = sync;
    }

    @RabbitListener(queues = "aigc.course.index.sync.queue", containerFactory = "courseIndexListenerContainerFactory")
    public void onCourseChanged(Long courseId) throws IOException {
        Assert.isTrue(courseId != null && courseId > 0, "课程变更缺少合法 ID");
        // 不按 routing key 强行下架；删除草稿也会产生 course.delete。
        sync.synchronize(courseId);
    }
}
