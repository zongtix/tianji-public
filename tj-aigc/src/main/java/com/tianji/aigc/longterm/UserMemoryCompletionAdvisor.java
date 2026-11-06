package com.tianji.aigc.longterm;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 仅正常完成且仍活跃时追加不可见元数据，交给外层 ChatMemory 聚合。 */
public final class UserMemoryCompletionAdvisor implements StreamAdvisor {
    public static final String REQUEST_ID = "userMemoryRequestId";
    private final String requestId;
    private final BooleanSupplier active;

    public UserMemoryCompletionAdvisor(String requestId, BooleanSupplier active) {
        this.requestId = requestId;
        this.active = active;
    }

    @Override public String getName() { return "userMemoryCompletion"; }
    @Override public int getOrder() { return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 1; }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            AtomicBoolean hasAnswer = new AtomicBoolean();
            return chain.nextStream(request).doOnNext(response -> {
                if (response.chatResponse() != null && response.chatResponse().getResult() != null
                        && StringUtils.hasText(response.chatResponse().getResult().getOutput().getText())) hasAnswer.set(true);
            }).concatWith(Flux.defer(() -> {
                if (!hasAnswer.get()) return Flux.empty();
                return Mono.fromCallable(active::getAsBoolean).subscribeOn(Schedulers.boundedElastic())
                        .onErrorReturn(false).flatMapMany(current -> {
                            if (!current) return Flux.empty();
                            var message = AssistantMessage.builder().content("").properties(Map.of(REQUEST_ID, requestId)).build();
                            return Flux.just(ChatClientResponse.builder().context(request.context())
                                    .chatResponse(new ChatResponse(List.of(new Generation(message)))).build());
                        });
            }));
        });
    }
}
