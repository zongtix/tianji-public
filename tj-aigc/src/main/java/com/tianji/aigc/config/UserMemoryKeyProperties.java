package com.tianji.aigc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** 只用于旧钥解密过渡，不在绑定校验或 toString 中输出密钥值。 */
@ConfigurationProperties(prefix = "tj.ai.long-term-memory")
public class UserMemoryKeyProperties {
    private List<String> decryptionKeys = List.of();

    public List<String> getDecryptionKeys() { return decryptionKeys; }

    public void setDecryptionKeys(List<String> decryptionKeys) {
        this.decryptionKeys = List.copyOf(decryptionKeys);
    }

    @Override
    public String toString() { return "UserMemoryKeyProperties[decryptionKeys=REDACTED]"; }
}
