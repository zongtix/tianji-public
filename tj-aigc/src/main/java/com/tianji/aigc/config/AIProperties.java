package com.tianji.aigc.config;

import com.tianji.aigc.enums.AgentTypeEnum;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "tj.ai.prompt")
public class AIProperties {

    private System system = new System(); // 系统提示词的配置来源

    @Data
    public static class System {
        private Chat chat; // 业务智能体共用的系统规则
        /** 按需配置独立 Nacos 提示词；未配置时使用 resources/prompts 下的默认文件。 */
        private Map<AgentTypeEnum, Chat> agents = new EnumMap<>(AgentTypeEnum.class);

        @Data
        public static class Chat {
            private String dataId;
            private String group = "DEFAULT_GROUP";
            private long timeoutMs = 20000L; // 读取的超时时间，单位毫秒
        }
    }
}
