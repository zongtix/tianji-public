package com.tianji.learning.domain.vo;

/** 普通评论与 @请求共用提交接口；机器人结果通过状态接口查询。 */
public record ReplySubmissionVO(Long id, String botStatus) { }
