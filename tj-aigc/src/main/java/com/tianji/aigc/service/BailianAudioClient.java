package com.tianji.aigc.service;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.audio.http_tts.HttpSpeechSynthesizer;
import com.alibaba.dashscope.audio.http_tts.HttpSpeechSynthesisParam;
import com.tianji.aigc.config.BailianAudioProperties;
import io.reactivex.Flowable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/** 独立语音 SDK 适配，不注册新的 ChatModel，不写入聊天记忆。 */
@Component
@RequiredArgsConstructor
public class BailianAudioClient {
    private final BailianAudioProperties properties;

    public String transcribe(byte[] wav) {
        Recognition recognition = new Recognition();
        var param = RecognitionParam.builder().apiKey(properties.getApiKey())
                .model(properties.getRecognitionModel()).format("wav").sampleRate(16000)
                .parameter("language_hints", new String[]{"zh", "en"}).build();
        var chunks = new ArrayList<ByteBuffer>();
        for (int offset = 0; offset < wav.length; offset += 3200) {
            chunks.add(ByteBuffer.wrap(wav, offset, Math.min(3200, wav.length - offset)).slice());
        }
        try {
            return recognition.streamCall(param, Flowable.fromIterable(chunks))
                    .filter(RecognitionResult::isSentenceEnd)
                    .map(result -> result.getSentence().getText())
                    .reduce("", (text, sentence) -> {
                        if (text.length() + sentence.length() > 3000) throw new IllegalStateException("识别文字超过长度限制");
                        return text + sentence;
                    }).timeout(45, TimeUnit.SECONDS).blockingGet();
        } catch (com.alibaba.dashscope.exception.NoApiKeyException e) {
            throw new IllegalStateException("百炼语音密钥不可用", e);
        } finally {
            recognition.getDuplexApi().close(1000, "done");
        }
    }

    public byte[] synthesize(String text) {
        return Mono.fromCallable(() -> {
            var param = HttpSpeechSynthesisParam.builder().apiKey(properties.getApiKey())
                    .model(properties.getSpeechModel()).voice(properties.getVoice())
                    .text(text).format("mp3").sampleRate(24000).build();
            ByteBuffer audio = new HttpSpeechSynthesizer().callAndReturnAudio(param);
            if (audio == null || audio.remaining() > 8 * 1024 * 1024) throw new IllegalStateException("语音响应无效或过大");
            byte[] bytes = new byte[audio.remaining()];
            audio.get(bytes);
            return bytes;
        }).subscribeOn(Schedulers.boundedElastic()).timeout(Duration.ofSeconds(45)).block();
    }
}
