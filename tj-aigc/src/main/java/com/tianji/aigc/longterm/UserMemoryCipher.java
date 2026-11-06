package com.tianji.aigc.longterm;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 用用户 ID 绑定密文，密钥由运行环境提供，不能从日志或数据库获取。 */
public final class UserMemoryCipher {
    private final SecretKeySpec key;
    private final String keyId;
    private final Map<String, SecretKeySpec> decryptionKeys;
    private final SecureRandom random = new SecureRandom();

    public UserMemoryCipher(String encodedKey) {
        this(encodedKey, List.of());
    }

    /** 旧钥只参与解密；标识由密钥摘要生成，避免轮换时另行维护 ID 映射。 */
    public UserMemoryCipher(String encodedKey, List<String> previousKeys) {
        this.key = decodeKey(encodedKey);
        this.keyId = keyId(key);
        Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
        keys.put(keyId, key);
        for (String previous : previousKeys) {
            SecretKeySpec decoded = decodeKey(previous);
            keys.putIfAbsent(keyId(decoded), decoded);
        }
        this.decryptionKeys = java.util.Collections.unmodifiableMap(keys);
    }

    private static SecretKeySpec decodeKey(String encodedKey) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encodedKey);
        } catch (RuntimeException error) {
            // 不保留解析器异常：配置错误也不能回显密钥。
            throw new IllegalArgumentException("长期记忆密钥必须是 Base64 编码的 32 字节");
        }
        if (bytes.length != 32) throw new IllegalArgumentException("长期记忆密钥必须是 Base64 编码的 32 字节");
        return new SecretKeySpec(bytes, "AES");
    }

    private static String keyId(SecretKeySpec key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("无法标识长期记忆密钥");
        }
    }

    public String encrypt(long userId, String plaintext) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(Long.toString(userId).getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:" + keyId + ":" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (Exception error) {
            throw new IllegalStateException("无法加密用户记忆");
        }
    }

    public String decrypt(long userId, String encoded) {
        try {
            String payload = encoded;
            List<SecretKeySpec> candidates;
            if (encoded.startsWith("v1:")) {
                String[] parts = encoded.split(":", -1);
                if (parts.length != 3) throw new IllegalArgumentException();
                SecretKeySpec selected = decryptionKeys.get(parts[1]);
                if (selected == null) throw new IllegalArgumentException();
                candidates = List.of(selected);
                payload = parts[2];
            } else {
                // 历史 Base64(nonce+ciphertext) 没有 ID，只在已显式提供的钥匙中尝试。
                candidates = new ArrayList<>(decryptionKeys.values());
            }
            byte[] bytes = Base64.getDecoder().decode(payload);
            if (bytes.length < 28) throw new IllegalArgumentException();
            ByteBuffer data = ByteBuffer.wrap(bytes);
            byte[] nonce = new byte[12];
            data.get(nonce);
            byte[] encrypted = new byte[data.remaining()];
            data.get(encrypted);
            for (SecretKeySpec candidate : candidates) {
                try {
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, candidate, new GCMParameterSpec(128, nonce));
                    cipher.updateAAD(Long.toString(userId).getBytes(StandardCharsets.UTF_8));
                    return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
                } catch (javax.crypto.AEADBadTagException ignored) {
                    // 兼容无 ID 的历史密文；用户 AAD 校验始终保留。
                }
            }
            throw new IllegalArgumentException();
        } catch (Exception error) {
            throw new IllegalStateException("无法解密用户记忆");
        }
    }
}
