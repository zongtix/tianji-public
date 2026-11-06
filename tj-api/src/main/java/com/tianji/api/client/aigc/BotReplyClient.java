package com.tianji.api.client.aigc;

import com.tianji.api.dto.aigc.BotReplyRequest;
import feign.Request;
import feign.Retryer;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.concurrent.TimeUnit;

/** 失败直接抛出，禁止把降级提示当成模型回答，也不自动重试付费请求。 */
@FeignClient(value = "aigc-service", contextId = "botReply", configuration = BotReplyClient.Config.class)
public interface BotReplyClient {
    String INTERNAL_TOKEN_HEADER = "X-Aigc-Internal-Token";
    @PostMapping("/chat/reply")
    String reply(@RequestBody BotReplyRequest request);

    class Config {
        @Bean
        public RequestInterceptor botReplyAuthentication(
                @Value("${tj.ai.internal-token:${TJ_AI_INTERNAL_TOKEN:}}") String token) {
            return template -> {
                template.removeHeader(INTERNAL_TOKEN_HEADER);
                if (StringUtils.hasText(token)) template.header(INTERNAL_TOKEN_HEADER, token);
            };
        }

        @Bean
        public Request.Options botReplyOptions() {
            return new Request.Options(3, TimeUnit.SECONDS, 40, TimeUnit.SECONDS, true);
        }

        @Bean
        public Retryer botReplyRetryer() {
            return Retryer.NEVER_RETRY;
        }
    }
}
