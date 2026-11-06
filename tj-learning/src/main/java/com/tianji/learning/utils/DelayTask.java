package com.tianji.learning.utils;

import lombok.Data;

import java.time.Duration;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * 延迟任务
 * <p>
 * 把业务数据和到期时间包装成一个可放入 DelayQueue 的任务，
 * 消费者线程调用 take() 时会阻塞，直到该任务到期才会被取出。
 *
 * @param <D> 业务数据类型
 */
@Data
public class DelayTask<D> implements Delayed {

    /**
     * 业务数据
     */
    private D data;

    /**
     * 到期时间，单位纳秒
     * 用 System.nanoTime() 计算，不受系统时间被调整的影响
     */
    private long deadlineNanos;

    public DelayTask(D data, Duration delayTime) {
        this.data = data;
        this.deadlineNanos = System.nanoTime() + delayTime.toNanos();
    }

    /**
     * 距离到期还剩多久，已到期或过期返回 0
     */
    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    /**
     * 到期时间早的排在队列前面，DelayQueue 依赖它决定出队顺序
     */
    @Override
    public int compareTo(Delayed o) {
        long l = getDelay(TimeUnit.NANOSECONDS) - o.getDelay(TimeUnit.NANOSECONDS);
        if (l > 0) {
            return 1;
        } else if (l < 0) {
            return -1;
        } else {
            return 0;
        }
    }
}
