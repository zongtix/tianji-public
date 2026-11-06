package com.tianji.learning.service.points;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.po.PointsRecord;
import com.tianji.learning.domain.vo.PointsStatisticsVO;
import com.tianji.learning.enums.PointsRecordType;

import java.util.List;

/**
 * 学习积分记录服务
 */
public interface IPointsRecordService extends IService<PointsRecord> {
    /**
     * 无业务键的内部积分入账，不做幂等。MQ 消费者必须使用带稳定业务键的重载。
     */
    void addPointsRecord(Long userId, int points, PointsRecordType type);

    /**
     * 带业务键的积分入账。
     * <p>
     * bizId 是幂等键（学习=小节id，问答=回答id，签到=签到日期）：
     * 唯一索引 (user_id, type, biz_id) 保证同一次业务动作只会加一次分，
     * MQ 消息重复投递时插入会撞唯一键，服务实现内部按幂等处理（忽略，不报错）。
     */
    void addPointsRecord(Long userId, int points, PointsRecordType type, String bizId);

    /** 按数据库明细重算用户月积分；同一任务重跑不会重复累加。 */
    void syncPointsBoard(Long userId, String month);

    /**
     * 查询当前用户今日各积分类型的获取情况，用于个人中心「我的积分」页。
     * <p>
     * 5 种积分类型每种都返回一条（今天没得分的返回 points = 0），
     * 且 type 返回的是中文描述而非枚举值 —— 前端是拿它与页面上的中文名做匹配的。
     */
    List<PointsStatisticsVO> queryMyTodayPoints();
}
