package com.tianji.learning.task;

import com.tianji.learning.service.interaction.BotReplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "tj.ai.reply", name = "enabled", havingValue = "true")
public class BotReplyTaskRunner {
    private final BotReplyService service;

    // 为现有定时任务保留独立的默认调度器，模型请求不占用积分等任务的线程。
    @Bean
    @ConditionalOnMissingBean(name = "taskScheduler")
    public TaskScheduler taskScheduler() {
        return new ThreadPoolTaskScheduler();
    }

    @Bean
    public TaskScheduler botReplyScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("qa-bot-");
        return scheduler;
    }

    @Scheduled(fixedDelay = 2000, scheduler = "botReplyScheduler")
    public void run() {
        try {
            service.processPending();
        } catch (Exception e) {
            log.warn("机器人任务扫描失败，下次扫描继续处理待办", e);
        }
    }
}
