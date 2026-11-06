package com.tianji.learning.service.points.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.autoconfigure.redisson.annotations.Lock;
import com.tianji.common.autoconfigure.redisson.enums.LockStrategy;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.po.PointsRecord;
import com.tianji.learning.domain.vo.PointsStatisticsVO;
import com.tianji.learning.enums.PointsRecordType;
import com.tianji.learning.mapper.points.PointsRecordMapper;
import com.tianji.learning.service.points.IPointsRecordService;
import com.tianji.learning.service.points.IPointsMessageService;
import com.tianji.learning.service.points.IPointsBoardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 学习积分记录服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsRecordServiceImpl extends ServiceImpl<PointsRecordMapper, PointsRecord>
        implements IPointsRecordService {

    private final IPointsMessageService pointsMessageService;
    private final IPointsBoardService pointsBoardService;

    @Override
    @Lock(name = "lock:points:#{userId}", lockStrategy = LockStrategy.FAIL_AFTER_RETRY_TIMEOUT)
    public void addPointsRecord(Long userId, int points, PointsRecordType type) {
        doAddPointsRecord(userId, points, type, null);
    }

    /**
     * 积分入账（带幂等键）。
     * <p>
     * 加锁原因：下面「查今日已得积分、判断是否超上限、插入记录」是三步非原子操作。
     * 多实例部署时，同一用户同一类型的并发请求会各自读到相同的已得积分值、
     * 双双通过上限判断，把当日上限刷超（例如 20 分刷到 25）。
     * 同一用户的入账与月榜重算共用锁，避免不同类型的同步覆盖较新的积分总额。
     * <p>
     * 等 1 秒拿不到锁就抛出异常，让 MQ 按失败流程重试，不能正常返回后把未入账消息确认掉。
     * <p>
     * 事务放在 pointsMessageService.savePointsRecord()：明细与同步任务原子提交，
     * 该代理方法提交后才返回，因此释放本方法的锁时数据已经可见。
     */
    @Override
    @Lock(name = "lock:points:#{userId}", lockStrategy = LockStrategy.FAIL_AFTER_RETRY_TIMEOUT)
    public void addPointsRecord(Long userId, int points, PointsRecordType type, String bizId) {
        doAddPointsRecord(userId, points, type, bizId);
    }

    /**
     * 积分入账的共同逻辑。两个 public 重载各自加锁后调它 ——
     * 不能让一个 public 直接调另一个，那是自调用、不走 AOP 代理，锁会静默失效。
     */
    private void doAddPointsRecord(Long userId, int points, PointsRecordType type, String bizId) {
        if (userId == null || type == null || points <= 0) {
            throw new BadRequestException("积分入账参数不合法");
        }
        LocalDateTime now = LocalDateTime.now();
        //1、判断当前积分类型是否有上限
        int realPoints = points;
        int maxPoints = type.getMaxPoints();
        if (maxPoints>0){
            //2.1、(有上限)判断当前用户当前积分类型是否已经达到了上限
            LocalDateTime begin= DateUtils.getDayStartTime(now);
            LocalDateTime end= DateUtils.getDayEndTime(now);
            //2.2、查询今日积分
            int currentPoints = queryUserPointsByTypeAndDate(userId,type,begin,end);
            //2.3、判断积分是否超上限
            if (currentPoints>=maxPoints){
                return;
            }
            //2.4、没超过，保存积分记录
            if (currentPoints+realPoints>maxPoints){
                realPoints = maxPoints-currentPoints;
            }

        }
        //3、（无上限），直接保存积分记录
        PointsRecord pointsRecord = new PointsRecord();
        pointsRecord.setPoints(realPoints);
        pointsRecord.setUserId(userId);
        pointsRecord.setType(type);
        pointsRecord.setBizId(bizId);
        // 明细时间与每日额度判断、月榜归属使用同一个入账时间。
        pointsRecord.setCreateTime(now);
        try {
            pointsMessageService.savePointsRecord(pointsRecord);
        } catch (DuplicateKeyException e) {
            // 原始入账的同步任务仍由发布任务重试，重复投递不创建新的明细或任务。
            log.info("积分已存在，忽略重复投递的消息：userId={}, type={}, bizId={}", userId, type, bizId);
            return;
        }
        // 月榜由已提交的同步任务更新；Redis 不可用不会丢失补偿机会。
    }

    @Override
    @Lock(name = "lock:points:#{userId}", lockStrategy = LockStrategy.FAIL_AFTER_RETRY_TIMEOUT)
    public void syncPointsBoard(Long userId, String month) {
        YearMonth yearMonth = YearMonth.parse(month, DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
        wrapper.lambda().eq(PointsRecord::getUserId, userId)
                .ge(PointsRecord::getCreateTime, yearMonth.atDay(1).atStartOfDay())
                .lt(PointsRecord::getCreateTime, yearMonth.plusMonths(1).atDay(1).atStartOfDay());
        Integer total = baseMapper.queryUserPointsByTypeAndDate(wrapper);
        pointsBoardService.updateBoardScore(userId, month, total == null ? 0 : total);
    }

    /**
     * 查询今日积分情况。
     * <p>
     * type 用枚举的中文描述（「课程学习」等）而不是枚举值 —— 前端 myIntegral.vue 是拿它
     * 跟页面上写死的中文名做 == 匹配来填 points 的，给数字或枚举名都会匹配不上、页面恒显示 0。
     * <p>
     * 5 种类型各查一次，都走 idx_user_id(user_id, type) 索引，单用户单日数据量极小；
     * 若以后想合并成一次查询，给 mapper 加一个按 type 分组的聚合 SQL 即可。
     */
    @Override
    public List<PointsStatisticsVO> queryMyTodayPoints() {
        Long userId = UserContext.getUser();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime begin = DateUtils.getDayStartTime(now);
        LocalDateTime end = DateUtils.getDayEndTime(now);

        PointsRecordType[] types = PointsRecordType.values();
        List<PointsStatisticsVO> result = new ArrayList<>(types.length);
        for (PointsRecordType type : types) {
            PointsStatisticsVO vo = new PointsStatisticsVO();
            vo.setType(type.getDesc());
            vo.setPoints(queryUserPointsByTypeAndDate(userId, type, begin, end));
            vo.setMaxPoints(type.getMaxPoints());
            result.add(vo);
        }
        return result;
    }

    //根据类型查询当前用户今日的积分
    private int queryUserPointsByTypeAndDate(Long userId, PointsRecordType type, LocalDateTime begin, LocalDateTime end) {
        //1、查询条件
        QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .eq(PointsRecord::getUserId, userId)
                .eq(type!=null,PointsRecord::getType, type)
                .between(begin!=null&&end!=null,PointsRecord::getCreateTime,begin,end);
        //2、调用mapper，查询结果
         Integer points = getBaseMapper().queryUserPointsByTypeAndDate(wrapper);
         //3、判断并返回
         return points == null ? 0 : points;

    }
}
