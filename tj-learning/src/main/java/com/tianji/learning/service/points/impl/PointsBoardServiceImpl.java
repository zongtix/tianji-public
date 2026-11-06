package com.tianji.learning.service.points.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.constants.Constant;
import com.tianji.common.autoconfigure.redisson.annotations.Lock;
import com.tianji.common.autoconfigure.redisson.enums.LockType;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardItemVO;
import com.tianji.learning.domain.vo.PointsBoardVO;
import com.tianji.learning.mapper.points.PointsBoardMapper;
import com.tianji.learning.service.points.IPointsBoardService;
import com.tianji.learning.utils.TableInfoContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.BoundZSetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 学霸天梯榜服务实现
 */
@Service
@RequiredArgsConstructor
public class PointsBoardServiceImpl extends ServiceImpl<PointsBoardMapper, PointsBoard>
        implements IPointsBoardService {

    private final StringRedisTemplate redisTemplate;

    private final UserClient userClient;
    @Override
    public PointsBoardVO queryPointsBoardBySeason(PointsBoardQuery query) {
        //1、判断是否是查询当前赛季
        Long season = query.getSeason();
        boolean isCurrentSeason = season == null || season == 0;
        if (!isCurrentSeason) {
            if (season < 0 || season > Integer.MAX_VALUE) {
                throw new BadRequestException("赛季参数不合法");
            }
            if (baseMapper.historyTableExists(Constant.SEASON_TABLE_NAME + season) == 0) {
                PointsBoardVO empty = new PointsBoardVO();
                empty.setPoints(0);
                empty.setRank(0);
                empty.setBoardList(Collections.emptyList());
                return empty;
            }
        }
        //1、获取Redis的key
        LocalDateTime now = LocalDateTime.now();
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX+now.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        //2、查询我的积分和排名
                                              //2-1、查询当前赛季，去redis里查找  //2-2、查询指定赛季，去mysql里找
        PointsBoard myBoard = isCurrentSeason ? queryMyCurrentBoard(key) : queryMySpecifiedBoard(season);

        //3、查询榜单列表
                                                  //3-1、查询当前赛季，去redis里查找
        List<PointsBoard> list = isCurrentSeason ? queryCurrentBoardList(key,query.getPageNo(),query.getPageSize())
                : querySpecifiedBoardList(query);//3-2、查询指定赛季，去mysql里找

        //4、封装VO并返回
        PointsBoardVO vo = new PointsBoardVO();
        //4.1、处理我的信息
        if (myBoard!=null){
            vo.setPoints(myBoard.getPoints());
            vo.setRank(myBoard.getRank());
        }
        if (CollUtils.isEmpty(list)){
            return vo;
        }
        //4.2、查询用户信息
        Set<Long> userIds = list.stream().map(PointsBoard::getUserId).collect(Collectors.toSet());
        List<UserDTO> userDTOS = userClient.queryUserByIds(userIds);
        Map<Long, String> userMap = new HashMap<>(userIds.size());
        if (CollUtils.isNotEmpty(userDTOS)){
            userMap = userDTOS.stream().collect(Collectors.toMap(UserDTO::getId, UserDTO::getName));
        }
        //4.3、处理榜单列表
        List<PointsBoardItemVO> items = new ArrayList<>(list.size());
        for (PointsBoard p : list) {
            PointsBoardItemVO v  = new PointsBoardItemVO();
            v.setPoints(p.getPoints());
            v.setRank(p.getRank());
            v.setName(userMap.get(p.getUserId()));
            items.add(v);
        }
        vo.setBoardList(items);
        return vo;
    }

    @Override
    public void createPointsBoardTable(Integer season) {
        baseMapper.createPointsBoardTable(Constant.SEASON_TABLE_NAME + season);
    }

    @Override
    @Lock(name = "lock:points:archive", lockType = LockType.WRITE_LOCK)
    public void updateBoardScore(Long userId, String month, int points) {
        redisTemplate.opsForZSet().add(RedisConstants.POINTS_BOARD_KEY_PREFIX + month, userId.toString(), points);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveHistoryBatch(List<PointsBoard> boards) {
        if (CollUtils.isNotEmpty(boards)) {
            baseMapper.upsertHistoryBatch(boards);
        }
    }

    @Override
    public void verifyHistoryBoard(Integer season, String key) {
        String table = Constant.SEASON_TABLE_NAME + season;
        if (baseMapper.historyTableExists(table) == 0) {
            throw new BizIllegalException("历史榜单尚未建表，禁止清理 Redis");
        }
        TableInfoContext.setInfo(table);
        try {
            Long size = redisTemplate.opsForZSet().zCard(key);
            if (size == null || count() != size) {
                throw new BizIllegalException("历史榜单记录数不完整，禁止清理 Redis");
            }
            int pageSize = 200;
            for (int pageNo = 1; (long) (pageNo - 1) * pageSize < size; pageNo++) {
                List<PointsBoard> source = queryCurrentBoardList(key, pageNo, pageSize);
                long from = (long) (pageNo - 1) * pageSize + 1;
                List<PointsBoard> archived = baseMapper.queryHistoryRange(from, from + pageSize - 1);
                int expected = (int) Math.min(pageSize, size - from + 1);
                if (source.size() != expected || archived.size() != expected) {
                    throw new BizIllegalException("历史榜单分页不完整，禁止清理 Redis");
                }
                for (int i = 0; i < source.size(); i++) {
                    PointsBoard actual = archived.get(i);
                    PointsBoard wanted = source.get(i);
                    if (!Objects.equals(actual.getId(), wanted.getRank().longValue())
                            || !Objects.equals(actual.getUserId(), wanted.getUserId())
                            || !Objects.equals(actual.getPoints(), wanted.getPoints())) {
                        throw new BizIllegalException("历史榜单数据不一致，禁止清理 Redis");
                    }
                }
            }
        } finally {
            TableInfoContext.removeInfo();
        }
    }


    //实现查询当前赛季我的积分和排名
    private PointsBoard queryMyCurrentBoard(String key) {
        //1、绑定key
        BoundZSetOperations<String, String> ops = redisTemplate.boundZSetOps(key);
        //2、获取当前用户信息
        Long userId = UserContext.getUser();
        //3、查询积分和排名
        Double score = ops.score(userId.toString());
        Long rank = ops.reverseRank(userId.toString());
        //4、封装返回
        PointsBoard pointsBoard = new PointsBoard();
        pointsBoard.setPoints(score == null? 0 :score.intValue());
        pointsBoard.setRank(rank == null? 0 : rank.intValue()+1);
        return pointsBoard;
    }

    // 实现查询指定赛季我的积分和排名
    private PointsBoard queryMySpecifiedBoard(Long season) {
        TableInfoContext.setInfo(Constant.SEASON_TABLE_NAME + season);
        try {
            // 历史表不存 rank、season，仅查询实际存在的三列。
            PointsBoard board = lambdaQuery()
                    .select(PointsBoard::getId, PointsBoard::getUserId, PointsBoard::getPoints)
                    .eq(PointsBoard::getUserId, UserContext.getUser())
                    .one();
            if (board == null) {
                return new PointsBoard().setPoints(0).setRank(0);
            }
            // 归档时将全局排名写入 id，查询时还原供接口展示。
            board.setRank(board.getId().intValue());
            return board;
        } finally {
            TableInfoContext.removeInfo();
        }
    }

    //实现查询当前赛季榜单
    @Override
    public List<PointsBoard> queryCurrentBoardList(String key, Integer pageNo, Integer pageSize) {
        // Redis 的范围下标从 0 开始，结束下标也包含在结果中。
        int from = (pageNo - 1) * pageSize;
        int to = from + pageSize - 1;
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet().reverseRangeWithScores(key, from, to);
        if (CollUtils.isEmpty(tuples)) {
            return CollUtils.emptyList();
        }

        List<PointsBoard> list = new ArrayList<>(tuples.size());
        // 名次从 1 开始，并接续前面页的名次；同分时沿用 Redis 返回的顺序。
        int rank = from + 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            PointsBoard board = new PointsBoard();
            board.setUserId(Long.valueOf(tuple.getValue()));
            board.setPoints(tuple.getScore().intValue());
            board.setRank(rank++);
            list.add(board);
        }
        return list;
    }
    //实现查询指定赛季榜单
    private List<PointsBoard> querySpecifiedBoardList(PointsBoardQuery query) {
        TableInfoContext.setInfo(Constant.SEASON_TABLE_NAME + query.getSeason());
        try {
            // 榜单始终按排名升序；返回列表不需要分页总数，关闭 COUNT 查询。
            List<PointsBoard> list = lambdaQuery()
                    .select(PointsBoard::getId, PointsBoard::getUserId, PointsBoard::getPoints)
                    .orderByAsc(PointsBoard::getId)
                    .page(new Page<>(query.getPageNo(), query.getPageSize(), false))
                    .getRecords();
            list.forEach(board -> board.setRank(board.getId().intValue()));
            return list;
        } finally {
            TableInfoContext.removeInfo();
        }
    }
}
