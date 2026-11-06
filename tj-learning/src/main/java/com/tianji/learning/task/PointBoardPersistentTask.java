package com.tianji.learning.task;
import com.tianji.common.constants.Constant;
import com.tianji.common.autoconfigure.redisson.annotations.Lock;
import com.tianji.common.autoconfigure.redisson.enums.LockType;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.service.points.IPointsBoardSeasonService;
import com.tianji.learning.service.points.IPointsBoardService;
import com.tianji.learning.utils.TableInfoContext;
import com.tianji.learning.mapper.points.PointsMessageMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PointBoardPersistentTask {

    private final IPointsBoardService pointsBoardService;

    private final IPointsBoardSeasonService seasonService;

    private final StringRedisTemplate redisTemplate;
    private final PointsMessageMapper messageMapper;


    @XxlJob("createTableJob")
    public void persistPointsBoard() {
        //1、获取上个月的时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        //2、查询赛季id
        Integer season = seasonService.querySeasonByTime(time);
        if (season == null) {
            return;
        }
        //3、创建表
        pointsBoardService.createPointsBoardTable(season);
    }

    @XxlJob("savePointsBoardToDBJob")
    @Lock(name = "lock:points:archive", lockType = LockType.READ_LOCK)
    public void savePointsBoardToDBJob() {
        //1、获取上个月的时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        //2、计算动态表名
        //2.1、查询赛季id
        Integer season = seasonService.querySeasonByTime(time);
        if (season == null) {
            return;
        }
        //2.2、存入ThreadLocal
        TableInfoContext.setInfo(Constant.SEASON_TABLE_NAME +season);
        try {
            //3、查询榜单数据
            //3.1、拼接key
            String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + time.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
            //3.2、查询数据
            int index = XxlJobHelper.getShardIndex();
            int total = XxlJobHelper.getShardTotal();
            int pageNo = index + 1;
            int pageSize = 5;
            while (true) {
                List<PointsBoard> pointsBoardList = pointsBoardService.queryCurrentBoardList(key, pageNo, pageSize);
                if (CollUtils.isEmpty(pointsBoardList)) {
                    break;
                }
                //4、持久化数据到数据库
                //4.1把排名信息写入id
                pointsBoardList.forEach(b->{
                    b.setId(b.getRank().longValue());
                    b.setRank(null);
                });
                //4.2、持久化
                pointsBoardService.saveHistoryBatch(pointsBoardList);
                //5、翻页
                pageNo+=total;
            }
        } finally {
            // 任务线程会复用，正常结束或发生异常都必须清理表名。
            TableInfoContext.removeInfo();
        }
    }

    @XxlJob("clearPointsBoardFromRedisJob")
    @Lock(name = "lock:points:archive", lockType = LockType.WRITE_LOCK)
    public void clearPointsBoardFromRedis() {
        //获取上个月的时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        //拼接key
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + time.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        Integer season = seasonService.querySeasonByTime(time);
        if (season == null) {
            throw new BizIllegalException("未找到上月赛季，禁止清理 Redis");
        }
        String month = time.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        if (messageMapper.countPendingBoardSync(month) != 0) {
            throw new BizIllegalException("上月仍有榜单同步任务，禁止清理 Redis");
        }
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            return; // 已经清理的榜单可安全重复执行。
        }
        pointsBoardService.verifyHistoryBoard(season, key);
        redisTemplate.unlink(key);
    }
}
