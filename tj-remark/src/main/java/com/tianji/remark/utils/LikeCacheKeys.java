package com.tianji.remark.utils;

/**
 * 点赞 Redis 数据字典，读写脚本、后台落库和恢复逻辑共用，防止 Key 拼写或维度不一致。
 * user:* 为 Set（成员 bizId）；count:* 为 ZSet（成员 bizId，score 总数）；version:* 为 Hash（bizId→版本）。
 * changes 为 Stream（状态变更日志），publish:retry 为 Hash（类型:对象→随机补发标记），cache:ready 为 String。
 * 上述业务 Key 没有在当前代码中设置 TTL；靠取消、落库后的 XDEL、目标删除及重建清理维持生命周期。
 * lock:* 是 Redisson 的协调锁，与业务缓存命名空间不同；消费组/消费者名字也不是独立数据 Key。
 */
public final class LikeCacheKeys {
    public static final String PREFIX = "remark:like:";
    // READY/BUILDING 是共享恢复状态；请求与消费者据此决定是否继续，写请求在 Lua 内再次检查。
    public static final String READY_KEY = PREFIX + "cache:ready";
    public static final String CHANGE_STREAM = PREFIX + "changes";
    // 这里只保存“对象需要补发”的标记；真正补发时重新读取数量/版本，不保存过期消息内容。
    public static final String RETRY_SNAPSHOTS = PREFIX + "publish:retry";
    public static final String CHANGE_GROUP = "remark-like-db-writer";
    public static final String INITIALIZE_LOCK = "lock:remark:like:initialize";
    public static final String WORKER_LOCK = "lock:remark:like:worker";
    // 所有实例共用消费者名称并受 WORKER_LOCK 串行协调，便于下一实例继续读取已有 pending。
    public static final String WORKER_NAME = "remark-like-writer";

    private LikeCacheKeys() {
    }

    /** 用户维度的 Set 支持一次批量检查该用户的一组业务对象是否已点赞。 */
    public static String userBizSet(String bizType, Long userId) {
        return PREFIX + "user:" + bizType + ":" + userId;
    }

    /** 每种业务各有一个点赞排行榜，member 是业务 id，score 是当前点赞数。 */
    public static String countSortedSet(String bizType) {
        return PREFIX + "count:" + bizType;
    }

    /** 保存每个业务对象的单调递增版本，供业务方拒绝乱序到达的旧消息。 */
    public static String versionHash(String bizType) {
        return PREFIX + "version:" + bizType;
    }
}
