package com.tianji.remark.exception;

/**
 * Broker 收到但没有匹配队列时抛出的内部异常，帮助区分网络故障与路由/消费者声明缺失。
 * 写后任务保留未确认日志，按退避重试并输出路由诊断；异常本身不创建下游队列。
 */
public class LikePublishUnroutableException extends RuntimeException {
    public LikePublishUnroutableException(String message) {
        super(message);
    }
}
