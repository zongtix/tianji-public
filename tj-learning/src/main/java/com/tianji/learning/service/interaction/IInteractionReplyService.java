package com.tianji.learning.service.interaction;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;
import com.tianji.learning.domain.vo.ReplySubmissionVO;

import java.util.List;
import java.util.Set;

/**
 * <p>
 * 互动问题的回答或评论 服务类
 * </p>
 */
public interface IInteractionReplyService extends IService<InteractionReply> {
    Set<Long> queryExistingReplyIds(List<Long> ids);

    ReplySubmissionVO saveReply(ReplyDTO replyDTO);

    PageDTO<ReplyVO> queryReplyPage(ReplyPageQuery query, boolean forAdmin);

    void hiddenReply(Long id, Boolean hidden);

    ReplyVO queryReplyById(Long id);
}
