package com.tianji.aigc.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;

class CourseOutputGuardTest {
    private ChatClientResponse chunk(String text) {
        return ChatClientResponse.builder().chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text))))).build();
    }
    private String result(String... parts) {
        return CourseOutputGuard.filter(Flux.fromArray(parts).map(this::chunk))
                .map(r -> r.chatResponse().getResult().getOutput().getText()).collectList().block()
                .stream().reduce("", String::concat);
    }
    @Test void retainsNormalTextAcrossChunks() {
        assertThat(result("推荐", " Deep", "Seek 课程 <", " 3，", "适合学习。"))
                .isEqualTo("推荐 DeepSeek 课程 < 3，适合学习。");
    }
    @Test void blocksMarkersAcrossAllSplitPositions() {
        String marker = "<｜｜DSML｜｜ calls>";
        for (int i = 0; i <= marker.length(); i++) {
            String output = result("已查到资料。", marker.substring(0, i), marker.substring(i), "不应展示");
            assertThat(output).isEqualTo("已查到资料。本次回答中断，请重新提问。");
        }
        assertThat(result("前文<|to", "ol_call>", "不应展示")).isEqualTo("前文本次回答中断，请重新提问。");
    }
    @Test void cancelsSourceOnceMarkerDetected() {
        var cancelled = new AtomicBoolean();
        var source = Flux.concat(Flux.just(chunk("<｜｜DSML")), Flux.<ChatClientResponse>never())
                .doOnCancel(() -> cancelled.set(true));
        assertThat(CourseOutputGuard.filter(source).collectList().block()).hasSize(1);
        assertThat(cancelled).isTrue();
    }
}
