package com.tianji.aigc.longterm;

import com.tianji.aigc.config.UserMemoryKeyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class UserMemoryCipherTest {
    private static final String OLD = key("0123456789abcdef0123456789abcdef");
    private static final String CURRENT = key("abcdef0123456789abcdef0123456789");

    @Test
    void newCiphertextIdentifiesItsKeyAndUsesRandomNoncesAndUserAad() {
        var cipher = new UserMemoryCipher(CURRENT);
        String first = cipher.encrypt(7, "合成记忆");
        String second = cipher.encrypt(7, "合成记忆");
        assertThat(first).matches("v1:[0-9a-f]{64}:[A-Za-z0-9+/=]+").isNotEqualTo(second);
        assertThat(Base64.getDecoder().decode(first.split(":")[2])).hasSize(12 + "合成记忆".getBytes(StandardCharsets.UTF_8).length + 16);
        assertThat(cipher.decrypt(7, first)).isEqualTo("合成记忆");
        assertThatThrownBy(() -> cipher.decrypt(8, first)).hasMessage("无法解密用户记忆").hasNoCause();
        assertThat(cipher.decrypt(7, cipher.encrypt(7, ""))).isEmpty();
    }

    @Test
    void rotationReadsHistoricalAndVersionedOldCiphertextsButWritesOnlyWithCurrentKey() throws Exception {
        var oldCipher = new UserMemoryCipher(OLD);
        var rotated = new UserMemoryCipher(CURRENT, List.of(OLD));
        String legacy = legacy(OLD, 7, "历史密文");
        String versioned = oldCipher.encrypt(7, "版本密文");
        assertThat(rotated.decrypt(7, legacy)).isEqualTo("历史密文");
        assertThat(rotated.decrypt(7, versioned)).isEqualTo("版本密文");
        assertThat(new UserMemoryCipher(OLD).decrypt(7, legacy)).isEqualTo("历史密文");
        assertThatThrownBy(() -> rotated.decrypt(8, legacy)).hasMessage("无法解密用户记忆");
        assertThatThrownBy(() -> new UserMemoryCipher(CURRENT).decrypt(7, versioned)).hasMessage("无法解密用户记忆");
        String written = rotated.encrypt(7, "新记忆");
        assertThat(new UserMemoryCipher(CURRENT).decrypt(7, written)).isEqualTo("新记忆");
        assertThatThrownBy(() -> oldCipher.decrypt(7, written)).hasMessage("无法解密用户记忆");
    }

    @Test
    void damagedUnknownVersionAndUnknownKeyAreRejectedWithoutEchoingContent() {
        var cipher = new UserMemoryCipher(CURRENT);
        String encrypted = cipher.encrypt(7, "PRIVATE_MEMORY_SENTINEL");
        String[] parts = encrypted.split(":");
        byte[] damaged = Base64.getDecoder().decode(parts[2]);
        damaged[damaged.length - 1] ^= 1;
        for (String invalid : List.of("PRIVATE_CIPHER_SENTINEL", "v2:" + parts[1] + ":" + parts[2],
                "v1:unknown:" + parts[2], "v1:" + parts[1] + ":" + Base64.getEncoder().encodeToString(damaged))) {
            assertThatThrownBy(() -> cipher.decrypt(7, invalid))
                    .hasMessage("无法解密用户记忆").hasNoCause();
        }
    }

    @Test
    void configurationBindsPreviousKeysWithoutExposingThemInToStringOrValidationErrors() throws Exception {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "tj.ai.long-term-memory.decryption-keys[0]", OLD)));
        var properties = binder.bind("tj.ai.long-term-memory", Bindable.of(UserMemoryKeyProperties.class)).get();
        assertThat(properties.getDecryptionKeys()).containsExactly(OLD);
        assertThat(properties.toString()).doesNotContain(OLD);
        var configured = new com.tianji.aigc.config.UserMemoryConfig().userMemoryCipher(CURRENT, "mysql", false, false, properties);
        assertThat(configured.decrypt(7, legacy(OLD, 7, "配置旧钥"))).isEqualTo("配置旧钥");
        String invalid = "PRIVATE_KEY_SENTINEL";
        for (boolean previous : List.of(false, true)) {
            Throwable failure = catchThrowable(() -> new UserMemoryCipher(previous ? CURRENT : invalid,
                    previous ? List.of(invalid) : List.of()));
            var trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            assertThat(trace.toString()).doesNotContain(invalid, CURRENT, OLD);
            assertThat(failure).hasNoCause();
        }
    }

    @Test
    void invalidPreviousKeyFailsBeanCreationWithoutAValueBearingBindException() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(com.tianji.aigc.config.UserMemoryConfig.class)
                .withBean(org.springframework.jdbc.core.JdbcTemplate.class, () -> org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class))
                .withBean(org.springframework.transaction.PlatformTransactionManager.class, () -> org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class))
                .withBean(org.springframework.ai.chat.memory.ChatMemory.class, () -> org.mockito.Mockito.mock(org.springframework.ai.chat.memory.ChatMemory.class))
                .withBean(org.springframework.ai.chat.model.ChatModel.class, () -> org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class))
                .withPropertyValues("tj.ai.long-term-memory.enabled=true", "tj.ai.long-term-memory.encryption-key=" + CURRENT,
                        "tj.ai.long-term-memory.decryption-keys[0]=PRIVATE_KEY_SENTINEL")
                .run(context -> {
                    assertThat(context).hasFailed();
                    var trace = new StringWriter();
                    context.getStartupFailure().printStackTrace(new PrintWriter(trace));
                    assertThat(trace.toString()).contains("长期记忆密钥必须是 Base64 编码的 32 字节")
                            .doesNotContain("PRIVATE_KEY_SENTINEL", CURRENT);
                });
    }

    private static String key(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 独立生成旧版 nonce+ciphertext，不依赖新 encrypt 的实现。 */
    private static String legacy(String key, long userId, String text) throws Exception {
        byte[] nonce = new byte[12];
        new java.security.SecureRandom().nextBytes(nonce);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(key), "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(Long.toString(userId).getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
    }
}
