package com.tianji.coursemcp;

import com.tianji.api.client.course.CourseClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableConfigurationProperties(CourseMcpProperties.class)
@EnableFeignClients(clients = CourseClient.class)
public class CourseMcpApplication {
    public static void main(String[] args) { SpringApplication.run(CourseMcpApplication.class, args); }
}
