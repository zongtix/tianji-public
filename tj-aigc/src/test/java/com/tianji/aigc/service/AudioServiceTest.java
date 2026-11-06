package com.tianji.aigc.service;

import com.tianji.aigc.config.BailianAudioProperties;
import com.tianji.common.utils.UserContext;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AudioServiceTest {
    private BailianAudioProperties config;
    private BailianAudioClient client;
    private StringRedisTemplate redis;
    private AudioService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        config = new BailianAudioProperties();
        config.setApiKey("test-only");
        client = mock(BailianAudioClient.class);
        redis = mock(StringRedisTemplate.class);
        doReturn(1L).when(redis).execute(any(RedisScript.class), anyList(), any(), any(), any());
        service = new AudioService(config, client, redis);
        UserContext.setUser(1L);
    }
    @AfterEach void cleanup() { UserContext.removeUser(); }

    @Test void validRecordingIsTranscribedWithoutSendingChat() {
        when(client.transcribe(any())).thenReturn("解释一下 Spring 事务");
        assertThat(service.stt(file(wav(16000)))).isEqualTo("解释一下 Spring 事务");
        verify(client).transcribe(any());
    }

    @Test void rejectsFakeWavAndExcessiveDurationBeforeSpending() {
        for (byte[] bytes : new byte[][] { "not a WAV".getBytes(), wav(16000 * 61), wav(0) }) {
            assertThatThrownBy(() -> service.stt(file(bytes))).isInstanceOf(RuntimeException.class);
        }
        byte[] stereo = wav(16000);
        stereo[22] = 2;
        assertThatThrownBy(() -> service.stt(file(stereo))).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(client, redis);
    }

    @Test void rejectsEmptyAndOverlongSpeechTextBeforeSpending() {
        assertThatThrownBy(() -> service.tts(" ")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.tts("字".repeat(3001))).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(client, redis);
    }

    @Test void cachesRepeatedPlaybackButIsolatesUsersAndVoiceSettings() {
        when(client.synthesize(anyString())).thenReturn(new byte[]{1, 2, 3});
        assertThat(service.tts("回答内容")).containsExactly(1, 2, 3);
        service.tts("回答内容");
        verify(client, times(1)).synthesize(anyString());
        UserContext.setUser(2L);
        service.tts("回答内容");
        config.setVoice("another-voice");
        service.tts("回答内容");
        verify(client, times(3)).synthesize(anyString());
    }

    @Test @SuppressWarnings("unchecked") void rateLimitAndRedisFailureDoNotCallProvider() {
        doReturn(0L).when(redis).execute(any(RedisScript.class), anyList(), any(), any(), any());
        assertThatThrownBy(() -> service.tts("回答")).isInstanceOf(RuntimeException.class);
        doThrow(new IllegalStateException("redis unavailable")).when(redis)
                .execute(any(RedisScript.class), anyList(), any(), any(), any());
        assertThatThrownBy(() -> service.stt(file(wav(16000)))).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(client);
    }

    @Test void disabledOrUnconfiguredAudioDoesNotAffectApplicationStartup() {
        config.setApiKey("");
        assertThat(service.options().enabled()).isFalse();
        assertThatThrownBy(() -> service.tts("回答")).isInstanceOf(RuntimeException.class);
        config.setApiKey("test-only"); config.setEnabled(false);
        assertThat(service.options().enabled()).isFalse();
        verifyNoInteractions(client, redis);
    }

    @Test void providerFailureAndEmptyResultsAreNotCachedOrAutomaticallyRetried() {
        when(client.synthesize(anyString())).thenThrow(new IllegalStateException("provider failed"));
        assertThatThrownBy(() -> service.tts("回答")).isInstanceOf(RuntimeException.class);
        verify(client, times(1)).synthesize(anyString());
        doReturn(new byte[0]).when(client).synthesize(anyString());
        assertThatThrownBy(() -> service.tts("回答")).isInstanceOf(RuntimeException.class);
    }

    @Test void anonymousCallsAreRejected() {
        UserContext.removeUser();
        assertThatThrownBy(() -> service.tts("回答")).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(client, redis);
    }

    private MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("audioFile", "voice.wav", "audio/wav", bytes);
    }
    public static byte[] wav(int samples) {
        var out = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples * 2);
        out.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        out.putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16);
        out.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples * 2);
        return out.array();
    }
}
