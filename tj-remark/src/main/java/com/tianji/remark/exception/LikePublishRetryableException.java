package com.tianji.remark.exception;

/**
 * 发布暂时失败的内部异常（超时、NACK 等），传给写后任务保留 Stream 并退避重试。
 * 它不代表数据库回滚：发布发生在关系事务提交之后。
 */
public class LikePublishRetryableException extends RuntimeException {
    public LikePublishRetryableException(String message) {
        super(message);
    }

    public LikePublishRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
