package com.tianji.aigc.tools;

import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Locale;

/** Prevent known model protocol markers from entering the visible answer or chat memory. */
final class CourseOutputGuard {
    private static final List<String> MARKERS = List.of("dsml", "<|tool");
    private static final String FALLBACK = "本次回答中断，请重新提问。";

    private CourseOutputGuard() { }

    static Flux<ChatClientResponse> filter(Flux<ChatClientResponse> source) {
        return Flux.defer(() -> {
            var state = new State();
            return source.<ChatClientResponse>handle((response, sink) -> {
                var chat = response.chatResponse();
                if (chat == null || chat.getResult() == null || chat.getResult().getOutput().getText() == null) {
                    sink.next(response);
                    return;
                }
                state.template = response;
                state.pending += chat.getResult().getOutput().getText();
                String lower = state.pending.toLowerCase(Locale.ROOT);
                int hit = MARKERS.stream().mapToInt(lower::indexOf).filter(i -> i >= 0).min().orElse(-1);
                if (hit >= 0) {
                    // Withhold the whole unconfirmed suffix, including a partial protocol opener.
                    String prefix = state.pending.substring(0, hit).replaceFirst("[<＜][|｜\\s]*$", "");
                    state.blocked = true;
                    state.pending = "";
                    sink.next(withText(response, prefix + FALLBACK));
                    return;
                }
                int keep = 0;
                for (String marker : MARKERS) {
                    for (int n = 1; n < marker.length() && n <= lower.length(); n++) {
                        if (lower.endsWith(marker.substring(0, n))) keep = Math.max(keep, n);
                    }
                }
                // Also retain a possible DSML opener so it cannot leak ahead of the marker.
                int opener = Math.max(lower.lastIndexOf('<'), lower.lastIndexOf('＜'));
                int prefixEnd = lower.length() - keep;
                if (opener >= 0 && opener <= prefixEnd && prefixEnd - opener <= 8
                        && lower.substring(opener, prefixEnd).matches("[<＜|｜\\s]*")) {
                    keep = lower.length() - opener;
                }
                int safeLength = state.pending.length() - keep;
                if (safeLength > 0) {
                    String safe = state.pending.substring(0, safeLength);
                    state.pending = state.pending.substring(safeLength);
                    sink.next(withText(response, safe));
                }
            }).takeUntil(ignored -> state.blocked).concatWith(Flux.defer(() -> {
                if (state.blocked || state.pending.isEmpty() || state.template == null) return Flux.empty();
                return Flux.just(withText(state.template, state.pending));
            }));
        });
    }

    private static ChatClientResponse withText(ChatClientResponse response, String text) {
        var original = response.chatResponse();
        var message = original.getResult().getOutput();
        var replacement = AssistantMessage.builder().content(text).properties(message.getMetadata()).build();
        return response.mutate().chatResponse(new ChatResponse(List.of(new Generation(replacement)), original.getMetadata())).build();
    }

    private static final class State {
        String pending = "";
        boolean blocked;
        ChatClientResponse template;
    }
}
