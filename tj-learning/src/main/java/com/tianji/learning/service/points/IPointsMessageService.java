package com.tianji.learning.service.points;

import com.tianji.learning.domain.po.PointsRecord;
import java.time.LocalDate;
import java.util.List;

public interface IPointsMessageService {
    /** 保存待发送消息；调用方有事务时与业务数据一起提交，数据库失败则一起回滚。 */
    void saveMessage(String messageKey, String routingKey, Object payload);

    /** 积分明细和榜单同步任务一起提交；任一写入失败则一起回滚。 */
    void savePointsRecord(PointsRecord record);

    /** 本月已受理的签到日期，含尚未发送的消息，用于补回失败的 Bitmap 写入。 */
    List<LocalDate> querySignDates(Long userId, LocalDate date);
}
