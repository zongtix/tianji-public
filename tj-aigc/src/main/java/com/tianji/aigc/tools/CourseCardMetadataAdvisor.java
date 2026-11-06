package com.tianji.aigc.tools;

import com.tianji.aigc.memory.MessageUtil;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.function.BooleanSupplier;

/** 正常完成且仍拥有请求时，把卡片交给外层记忆 Advisor 一起聚合保存。 */
public class CourseCardMetadataAdvisor implements StreamAdvisor {
    private final CourseRecommendations recommendations;
    private final BooleanSupplier isCurrent;

    public CourseCardMetadataAdvisor(CourseRecommendations recommendations, BooleanSupplier isCurrent) {
        this.recommendations = recommendations;
        this.isCurrent = isCurrent;
    }

    @Override
    public String getName() {
        return "courseCardMetadata";
    }

    @Override
    public int getOrder() {
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 1;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(request).concatWith(Flux.defer(() -> {
            Integer intakeQuestions = recommendations.responseIntakeState();
            if (recommendations.snapshot().isEmpty() && intakeQuestions == null) {
                recommendations.close();
                return Flux.empty();
            }
            // 阻塞 Redis 检查放在 boundedElastic；取消或错误不会进入这个完成分支。
            return Mono.fromCallable(isCurrent::getAsBoolean).subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(current -> {
                        var cards = recommendations.finish(current);
                        var metadata = new LinkedHashMap<String, Object>();
                        if (!cards.isEmpty()) metadata.put(MessageUtil.COURSE_CARDS, cards);
                        if (current && intakeQuestions != null) metadata.put(CourseRecommendations.INTAKE_STATE, intakeQuestions);
                        if (metadata.isEmpty()) return Flux.empty();
                        var message = AssistantMessage.builder().content("")
                                .properties(metadata).build();
                        return Flux.just(ChatClientResponse.builder().context(request.context())
                                .chatResponse(new ChatResponse(List.of(new Generation(message)))).build());
                    });
        }));
    }
}
