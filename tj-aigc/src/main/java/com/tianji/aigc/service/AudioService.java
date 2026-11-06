package com.tianji.aigc.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tianji.aigc.config.BailianAudioProperties;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.CommonException;
import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
public class AudioService {
    private final BailianAudioProperties properties;
    private final BailianAudioClient client;
    private final StringRedisTemplate redis;
    private final Cache<String, byte[]> audioCache = Caffeine.newBuilder().maximumWeight(16 * 1024 * 1024)
            .weigher((String key, byte[] value) -> value.length).expireAfterWrite(Duration.ofMinutes(30)).build();
    private static final DefaultRedisScript<Long> BUDGET = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            if tonumber(redis.call('GET', KEYS[2]) or '0') >= tonumber(ARGV[2]) then return 0 end
            if tonumber(redis.call('GET', KEYS[3]) or '0') >= tonumber(ARGV[3]) then return 0 end
            redis.call('SET', KEYS[1], '1', 'EX', ARGV[1])
            redis.call('INCR', KEYS[2]); redis.call('EXPIRE', KEYS[2], 172800)
            redis.call('INCR', KEYS[3]); redis.call('EXPIRE', KEYS[3], 172800)
            return 1
            """, Long.class);

    public AudioService(BailianAudioProperties properties, BailianAudioClient client, StringRedisTemplate redis) {
        this.properties = properties; this.client = client; this.redis = redis;
    }

    public record Options(boolean enabled, int maxDurationSeconds, int maxTextLength) { }

    public Options options() {
        return new Options(properties.isEnabled() && StringUtils.hasText(properties.getApiKey()), 60, 3000);
    }

    public String stt(MultipartFile file) {
        Long userId = requireReady();
        if (file == null || file.isEmpty() || file.getSize() > 2 * 1024 * 1024) {
            throw new BadRequestException("请上传不超过 60 秒的 WAV 录音");
        }
        byte[] wav;
        try { wav = file.getBytes(); } catch (Exception e) { throw new BadRequestException("无法读取录音文件"); }
        validateWav(wav);
        budget(userId);
        try {
            String text = client.transcribe(wav);
            if (!StringUtils.hasText(text)) throw new BadRequestException("没有识别到语音，请靠近麦克风再试");
            if (text.length() > 3000) throw new BadRequestException("识别内容过长，请缩短录音");
            return text.strip();
        } catch (CommonException e) { throw e; }
        catch (Exception e) {
            log.warn("百炼语音识别失败：{}", e.getClass().getSimpleName());
            throw new CommonException(503, "语音识别暂不可用，请稍后再试");
        }
    }

    public byte[] tts(String text) {
        Long userId = requireReady();
        if (!StringUtils.hasText(text) || text.length() > 3000) throw new BadRequestException("播报文本需为 1 至 3000 字符");
        String content = text.strip();
        String key;
        try {
            key = userId + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((properties.getSpeechModel() + "\n" + properties.getVoice() + "\n" + content).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
        return audioCache.get(key, ignored -> {
            budget(userId);
            try {
                byte[] bytes = client.synthesize(content);
                if (bytes == null || bytes.length == 0 || bytes.length > 8 * 1024 * 1024) throw new IllegalStateException("空或过大的语音响应");
                return bytes;
            } catch (Exception e) {
                log.warn("百炼语音合成失败：{}", e.getClass().getSimpleName());
                throw new CommonException(503, "语音播报暂不可用，请稍后再试");
            }
        });
    }

    private Long requireReady() {
        Long user = UserContext.getUser();
        if (user == null) throw new UnauthorizedException("请先登录");
        if (!options().enabled()) throw new CommonException(503, "语音功能暂未启用，请配置百炼语音密钥");
        return user;
    }

    private void budget(Long user) {
        String date = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        Long permitted;
        try {
            permitted = redis.execute(BUDGET, List.of("aigc:audio:cooldown:" + user,
                    "aigc:audio:daily:" + date + ":" + user, "aigc:audio:daily:" + date + ":all"),
                    "3", String.valueOf(properties.getUserDailyLimit()), String.valueOf(properties.getGlobalDailyLimit()));
        } catch (Exception e) { throw new CommonException(503, "语音服务暂不可用，请稍后再试"); }
        if (!Long.valueOf(1).equals(permitted)) throw new BadRequestException("语音调用过于频繁或今日额度已用完，请稍后再试");
    }

    private static void validateWav(byte[] wav) {
        if (wav.length < 44) throw new BadRequestException("录音格式错误");
        ByteBuffer data = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (!tag(wav, 0).equals("RIFF") || !tag(wav, 8).equals("WAVE") || Integer.toUnsignedLong(data.getInt(4)) != wav.length - 8L) {
            throw new BadRequestException("请上传有效的 PCM WAV 录音");
        }
        boolean format = false, audio = false;
        long bytes = 0;
        for (int offset = 12; offset < wav.length;) {
            if (offset + 8 > wav.length) throw new BadRequestException("WAV 数据不完整");
            String chunk = tag(wav, offset);
            long length = Integer.toUnsignedLong(data.getInt(offset + 4));
            long end = offset + 8L + length;
            if (end > wav.length) throw new BadRequestException("WAV 数据不完整");
            if (chunk.equals("fmt ")) {
                if (format || length < 16 || data.getShort(offset + 8) != 1 || data.getShort(offset + 10) != 1
                        || data.getInt(offset + 12) != 16000 || data.getInt(offset + 16) != 32000
                        || data.getShort(offset + 20) != 2 || data.getShort(offset + 22) != 16) {
                    throw new BadRequestException("录音须为 16kHz 单声道 16bit PCM WAV");
                }
                format = true;
            } else if (chunk.equals("data")) {
                if (audio || !format || length % 2 != 0) throw new BadRequestException("WAV 数据格式错误");
                bytes = length; audio = true;
            }
            offset = (int) (end + (length & 1));
        }
        if (!format || !audio || bytes < 3200 || bytes > 60L * 32000) throw new BadRequestException("录音长度须为 0.1 至 60 秒");
    }

    private static String tag(byte[] wav, int offset) {
        return new String(wav, offset, 4, StandardCharsets.US_ASCII);
    }
}
