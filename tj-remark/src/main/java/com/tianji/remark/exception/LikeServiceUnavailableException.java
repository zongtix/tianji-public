package com.tianji.remark.exception;

import com.tianji.common.exceptions.CommonException;

/**
 * 点赞依赖不可用或缓存未就绪时的业务异常，状态码为 503，由公共 MVC 异常处理输出。
 * 请求阶段拒绝读写；后台目标校验失败也用它中断处理，避免把依赖故障当成对象不存在。
 */
public class LikeServiceUnavailableException extends CommonException {

    private static final int STATUS = 503;

    public LikeServiceUnavailableException(String message) {
        super(STATUS, message);
    }

    public LikeServiceUnavailableException(String message, Throwable cause) {
        super(STATUS, message, cause);
    }

    /** 显式覆写父类硬编码的 500，保证对外错误契约稳定；不再依赖字段名与 getter 名的巧合。 */
    @Override
    public int getStatus() {
        return STATUS;
    }
}
