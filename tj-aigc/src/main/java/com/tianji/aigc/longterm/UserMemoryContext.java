package com.tianji.aigc.longterm;

import java.util.List;

/** 每轮独立的服务端记忆快照；不会保存在会话消息或工具上下文中。 */
public record UserMemoryContext(long userId, String requestId, boolean staged, List<UserMemoryStore.Memory> memories) {
    public UserMemoryContext {
        memories = List.copyOf(memories);
    }
}
