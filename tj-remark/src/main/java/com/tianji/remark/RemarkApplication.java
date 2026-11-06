package com.tianji.remark;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;


/**
 * remark-service 的启动入口，当前模块提供 QA/NOTE 通用点赞能力，不保存评论正文。
 * MapperScan 为 LikedRecordMapper 创建 MyBatis 代理；EnableScheduling 启用后台落库、补发、监控和对账任务。
 * Spring 启动后还会执行 LikeCacheInitializer（ApplicationRunner），在缓存恢复完成后开放点赞读写。
 */
@Slf4j
@SpringBootApplication
@MapperScan("com.tianji.remark.mapper")
@EnableScheduling
public class RemarkApplication {
    public static void main(String[] args) throws UnknownHostException {
        SpringApplication app = new SpringApplicationBuilder(RemarkApplication.class).build(args);
        Environment env = app.run(args).getEnvironment();
        String protocol = "http";
        if (env.getProperty("server.ssl.key-store") != null) {
            protocol = "https";
        }
        log.info("--/\n---------------------------------------------------------------------------------------\n\t" +
                        "Application '{}' is running! Access URLs:\n\t" +
                        "Local: \t\t{}://localhost:{}\n\t" +
                        "External: \t{}://{}:{}\n\t" +
                        "Profile(s): \t{}" +
                        "\n---------------------------------------------------------------------------------------",
                env.getProperty("spring.application.name"),
                protocol,
                env.getProperty("server.port"),
                protocol,
                InetAddress.getLocalHost().getHostAddress(),
                env.getProperty("server.port"),
                env.getActiveProfiles());
    }
}
