package com.tianji.upgrade;

import com.xxl.job.core.executor.XxlJobExecutor;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@SpringBootApplication
@EnableFeignClients(clients = ProbeApplication.IdentityClient.class)
public class ProbeApplication {
    public static final AtomicBoolean CANCELLED = new AtomicBoolean();

    public static void main(String[] args) { SpringApplication.run(ProbeApplication.class, args); }

    @Bean(destroyMethod = "close")
    RestHighLevelClient elasticsearchClient() {
        return new RestHighLevelClient(RestClient.builder(new HttpHost("127.0.0.1", 19200, "http")));
    }

    @Bean(destroyMethod = "")
    RestClient elasticsearchLowLevelClient(RestHighLevelClient client) { return client.getLowLevelClient(); }

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:16379").setConnectionPoolSize(4).setConnectionMinimumIdleSize(1);
        return Redisson.create(config);
    }

    @Bean(initMethod = "start", destroyMethod = "destroy")
    XxlJobExecutor probeExecutor() {
        XxlJobExecutor executor = new XxlJobExecutor();
        executor.setAppname("platform-upgrade-probe");
        executor.setIp("127.0.0.1");
        executor.setPort(19998);
        executor.setAccessToken("upgrade-probe-token");
        executor.setLogPath("target/xxl-probe");
        return executor;
    }

    @Bean @Primary
    ChatModel probeChatModel() {
        return new ChatModel() {
            public ChatResponse call(Prompt prompt) { return response("probe"); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(response("first"), response("second")).concatWith(Flux.never())
                    .doOnCancel(() -> CANCELLED.set(true));
            }
            private ChatResponse response(String text) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
            }
        };
    }

    @FeignClient(name = "platform-upgrade-web-probe")
    public interface IdentityClient {
        @GetMapping("/probe/identity") String identity(@RequestHeader("user-info") String userId);
    }

    @RestController
    static class ProbeController {
        private final ChatClient chat;
        ProbeController(ChatModel model) { this.chat = ChatClient.builder(model).build(); }
        @GetMapping("/probe/identity") String identity(@RequestHeader("user-info") String userId) { return userId; }
        @GetMapping(value = "/probe/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        Flux<String> chat() { return chat.prompt().user("test").stream().content().take(2); }
    }
}
