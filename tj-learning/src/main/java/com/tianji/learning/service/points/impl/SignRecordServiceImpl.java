package com.tianji.learning.service.points.impl;

import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BooleanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.vo.SignRecordVO;
import com.tianji.learning.domain.vo.SignResultVO;
import com.tianji.learning.mq.message.SignInMessage;
import com.tianji.learning.service.points.IPointsMessageService;
import com.tianji.learning.service.points.ISignRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class SignRecordServiceImpl implements ISignRecordService {

    private final StringRedisTemplate stringRedisTemplate;

    private final IPointsMessageService pointsMessageService;
    @Override
    public SignResultVO addSignRecords() {
        //1、签到
        //1.1、获取用户信息
        Long userId = UserContext.getUser();
        //1.2、获取当前日期
        LocalDateTime now = LocalDateTime.now();
        //1.3、拼接key
        String key = RedisConstants.SIGN_RECORD_KEY_PREFIX
                + userId
                + now.format(DateUtils.SIGN_DATE_SUFFIX_FORMATTER);
        //1.4、计算offset
        int offset = now.getDayOfMonth()-1;
        // 上一次签到已落库但 Bitmap 写入失败时，先补回本月记录，再计算连续天数和奖励。
        for (LocalDate date : pointsMessageService.querySignDates(userId, now.toLocalDate())) {
            stringRedisTemplate.opsForValue().setBit(key, date.getDayOfMonth() - 1, true);
        }
        //1.5、检查本月 Bitmap；新签到先保存数据库消息记录，再更新签到位。
        Boolean signedBefore = stringRedisTemplate.opsForValue().getBit(key, offset);
        if (BooleanUtils.isTrue(signedBefore)) {
            throw new BizIllegalException("今日已签到");
        }
        //2、计算连续签到天数
        int signDays = offset == 0 ? 1 : countSignDays(key, offset) + 1;
        //3、计算签到得分（连续天数只统计本月）
        int rewardPoints = 0;
        switch (signDays){
            case 7:
                rewardPoints = 10;
                break;
            case 14:
                rewardPoints = 20;
                break;
            case 28:
                rewardPoints = 40;
                break;
        }

        //4、先提交待发送消息。唯一业务键保证并发签到也只成功一次，MQ 失败由定时任务重试。
        String signDate = String.valueOf(now.toLocalDate());
        try {
            pointsMessageService.saveMessage("sign:" + userId + ":" + signDate, MqConstants.Key.SIGN_IN,
                    SignInMessage.of(userId, rewardPoints + 1, signDate));
        } catch (DuplicateKeyException e) {
            throw new BizIllegalException("今日已签到");
        }
        try {
            stringRedisTemplate.opsForValue().setBit(key, offset, true);
        } catch (RuntimeException e) {
            // 数据库已提交，签到已经受理；发布任务会根据签到日期补回 Bitmap，不能撤销业务键。
            log.warn("签到已保存，等待任务恢复签到位：userId={}, date={}", userId, signDate, e);
        }


        //5、封装返回
        SignResultVO signResultVO = new SignResultVO();
        signResultVO.setSignDays(signDays);
        signResultVO.setRewardPoints(rewardPoints);
        return signResultVO;
    }

    /**
     * 查询本月签到记录
     * 利用 Redis Bitmap 存储签到数据，每个 bit 位代表一天：offset 0 = 第1天，offset 1 = 第2天 ...
     * 签到时通过 setBit 将对应 offset 设为 1
     *
     * @return SignRecordVO 包含：
     *   - signRecords: 每天的签到状态列表 [1,0,1,1,0,...]，角标 0 代表第1天
     *   - signDays: 截止到今天的连续签到天数
     */
    @Override
    public SignRecordVO querySignRecords() {
        // 1、获取用户信息
        Long userId = UserContext.getUser();
        // 2、获取当前日期
        LocalDateTime now = LocalDateTime.now();
        // 3、拼接 Redis key，格式示例：sign:uid:1001:202610（用户1001在2026年10月的签到记录）
        String key = RedisConstants.SIGN_RECORD_KEY_PREFIX
                + userId
                + now.format(DateUtils.SIGN_DATE_SUFFIX_FORMATTER);
        // dayOfMonth 代表今天是本月第几天，也就是 Bitmap 中一共可能有这么多位
        int dayOfMonth = now.getDayOfMonth();
        // 已发送的签到也可能在 Redis 丢失后需要恢复，查询和签到使用相同的数据库事实。
        for (LocalDate date : pointsMessageService.querySignDates(userId, now.toLocalDate())) {
            stringRedisTemplate.opsForValue().setBit(key, date.getDayOfMonth() - 1, true);
        }

        /*
         * 4、一次性读取本月所有签到 bit 位
         *
         * 对应 Redis 命令：BITFIELD key GET u{dayOfMonth} 0
         * 含义：从 offset 0 开始，读取 dayOfMonth 个 bit，当作一个无符号整数返回
         *
         * 举例：今天是5号，用户签到了第1、3、4、5天，Bitmap 如下：
         *   offset:  0  1  2  3  4
         *   bit:     1  0  1  1  1
         * Redis 按 big-endian 拼装：offset 0 是最高位，offset 4 是最低位
         * 得到二进制 10111 = 十进制 23
         */
        List<Long> result = stringRedisTemplate.opsForValue()
                .bitField(key, BitFieldSubCommands.create().get(
                        BitFieldSubCommands.BitFieldType.unsigned(now.getDayOfMonth())).valueAt(0));

        SignRecordVO vo = new SignRecordVO();
        // 如果 key 不存在（本月从未签到），或者取出的整数值为 0（所有位都是0，一天都没签）
        if (CollUtils.isEmpty(result) || result.get(0) == 0L) {
            vo.setSignDays(0);
            vo.setSignRecords(buildEmptyRecords(dayOfMonth));
            return vo;
        }
        int num = result.get(0).intValue();

        /*
         * 5、将整数 num 拆解为每天的签到记录
         *
         * 因为 offset 0（第1天）在最高位，offset 最大（今天）在最低位，
         * 所以 i 从 dayOfMonth-1 递减到 0，依次提取第1天 → 今天 的 bit 值
         *
         * (num >> i) & 1 的原理：
         *   num >> i  → 把第 i 位右移到最低位
         *   & 1       → 用掩码只保留最低位，其余位全部清零
         *
         * 以 num = 23（10111）为例，i 从 4 到 0：
         *   i=4: 23 >> 4 = 00001, & 1 = 1  → 第1天签到了
         *   i=3: 23 >> 3 = 00010, & 1 = 0  → 第2天没签到
         *   i=2: 23 >> 2 = 00101, & 1 = 1  → 第3天签到了
         *   i=1: 23 >> 1 = 01011, & 1 = 1  → 第4天签到了
         *   i=0: 23 >> 0 = 10111, & 1 = 1  → 第5天签到了
         * 最终 records = [1, 0, 1, 1, 1]
         */
        List<Byte> records = new ArrayList<>(dayOfMonth);
        for (int i = dayOfMonth - 1; i >= 0; i--) {
            records.add((byte) ((num >> i) & 1));
        }
        vo.setSignRecords(records);

        // 6、计算截止到今天的连续签到天数（从今天的 bit 往高位方向数连续的 1）
        vo.setSignDays(countSignDays(key, dayOfMonth));
        return vo;
    }

    /**
     * 计算截止到今天的连续签到天数
     *
     * 原理：
     *   今天的签到记录在 num 的最低位（LSB），昨天在倒数第二位，以此类推
     *   从 LSB 开始往高位数连续的 1，就是连续签到天数
     *   遇到 0（某天没签到）就停止
     *
     * 举例：num = 10111（二进制），从 LSB 开始：
     *   第1轮: 10111 & 1 = 1 ✅ count=1, >>>=1 → 01011
     *   第2轮: 01011 & 1 = 1 ✅ count=2, >>>=1 → 00101
     *   第3轮: 00101 & 1 = 1 ✅ count=3, >>>=1 → 00010
     *   第4轮: 00010 & 1 = 0 ❌ 循环结束，返回 count=3
     */
    private int countSignDays(String key, int len) {
        // 1. 用 BITFIELD 一次性读取本月所有签到 bit 位，得到整数 num
        List<Long> result = stringRedisTemplate.opsForValue()
                .bitField(key, BitFieldSubCommands.create().get(
                        BitFieldSubCommands.BitFieldType.unsigned(len)).valueAt(0));
        if (CollUtils.isEmpty(result)) {
            return 0;
        }
        int num = result.get(0).intValue();
        // 2. 定义一个计数器，记录连续签到天数
        int count = 0;
        // 3. (num & 1) == 1 表示最低位是 1，即当天签到了
        while ((num & 1) == 1) {
            count++;
            // 4. >>> 是无符号右移：高位补 0，最低位丢弃
            //    效果：把"前一天"的 bit 移到最低位，下一轮继续检查
            //    用 >>> 而非 >> 是为了避免符号位干扰（虽然本例 num 一定是正数）
            num >>>= 1;
        }
        return count;
    }

    /**
     * 构建空签到记录（本月一天都没签到的情况）
     * 返回 [0, 0, 0, ..., 0]，长度等于天数
     */
    private List<Byte> buildEmptyRecords(int days) {
        List<Byte> records = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            records.add((byte) 0);
        }
        return records;
    }
}
