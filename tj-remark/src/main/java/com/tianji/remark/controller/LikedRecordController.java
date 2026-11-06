package com.tianji.remark.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.tianji.remark.domain.dto.LikeRecordFormDTO;
import com.tianji.remark.service.ILikedRecordService;
import com.tianji.common.exceptions.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 点赞 HTTP 入口：接收目标状态，或批量返回当前用户的点赞关系、对象总点赞数。
 * 请求交给 ILikedRecordService；本层负责表单/列表长度校验，不直接操作数据库或 Redis。
 * 服务内路径为 /likes，经过 Gateway 的 /rs/** 路由及 StripPrefix=1 后，外部路径为 /rs/likes。
 * 评论发布、回复、隐藏由 learning 的 InteractionReplyController/AdminController 管理，本模块只认 bizType + bizId。
 * 这里没有独立 VO：写接口无返回数据，两个读接口分别直接返回 Set<Long>、Map<Long, Integer>。
 */
@Tag(name = "点赞记录相关接口")
@Validated
@RestController
@RequestMapping("/likes")
@RequiredArgsConstructor
public class LikedRecordController {
    private final ILikedRecordService likedRecordService;

    /**
     * 设置目标点赞状态：请求体携带 bizType、bizId、liked，用户 ID 由登录上下文提供。
     * 成功表示 Redis 已接收状态（或已是该状态）；不表示 MySQL 与下游业务表已经同步完成。
     */
    @PostMapping
    @Operation(summary = "点赞或取消点赞")
    public void addLikeRecord(@Validated @RequestBody LikeRecordFormDTO recordDTO) {
        likedRecordService.saveLikeRecord(recordDTO);
    }

    /**
     * 为一页回答/评论批量查询“我点过哪些赞”。返回输入 ID 的已点赞子集，便于调用方用 contains 组装 liked。
     * 一次最多 500 个 ID，限制单次脚本工作量；这里的批量读取不是数据库分页。
     */
    @GetMapping("/status")
    @Operation(summary = "批量查询当前用户已点赞的业务id")
    public Set<Long> queryMyLikedBizIds(
            @RequestParam("bizType") String bizType,
            @RequestParam(value = "bizIds", required = false) @Size(max = 500) List<Long> bizIds) {
        validateBizType(bizType);
        return likedRecordService.queryMyLikedBizIds(bizType, bizIds);
    }

    /**
     * 批量查询对象总数，返回 Map<业务ID, 点赞数>，与当前用户是否点赞是两种独立信息。
     * 调用方可按 ID 将即时计数组装进列表 VO，无需逐条远程调用。
     */
    @GetMapping("/times")
    @Operation(summary = "批量查询业务对象的点赞总数")
    public Map<Long, Integer> queryLikedTimes(
            @RequestParam("bizType") String bizType,
            @RequestParam(value = "bizIds", required = false) @Size(max = 500) List<Long> bizIds) {
        validateBizType(bizType);
        return likedRecordService.queryLikedTimes(bizType, bizIds);
    }

    private void validateBizType(String bizType) {
        if (!"QA".equals(bizType) && !"NOTE".equals(bizType)) {
            throw new BadRequestException("不支持的点赞业务类型");
        }
    }
}
