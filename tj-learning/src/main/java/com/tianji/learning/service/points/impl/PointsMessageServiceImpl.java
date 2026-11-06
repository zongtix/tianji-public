package com.tianji.learning.service.points.impl;

import com.tianji.common.exceptions.DbException;
import com.tianji.common.utils.JsonUtils;
import com.tianji.learning.domain.po.PointsMessage;
import com.tianji.learning.domain.po.PointsRecord;
import com.tianji.learning.mapper.points.PointsMessageMapper;
import com.tianji.learning.mapper.points.PointsRecordMapper;
import com.tianji.learning.mq.message.PointsBoardSyncMessage;
import com.tianji.common.utils.DateUtils;
import com.tianji.learning.service.points.IPointsMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PointsMessageServiceImpl implements IPointsMessageService {
    private final PointsMessageMapper mapper;
    private final PointsRecordMapper recordMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void savePointsRecord(PointsRecord record) {
        if (recordMapper.insert(record) != 1) {
            throw new DbException("保存积分明细失败");
        }
        String month = record.getCreateTime().format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        saveMessage("board:" + month + ":" + record.getId(), PointsBoardSyncMessage.ROUTING_KEY,
                new PointsBoardSyncMessage(record.getUserId(), month));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveMessage(String messageKey, String routingKey, Object payload) {
        PointsMessage message = new PointsMessage();
        message.setMessageKey(messageKey);
        message.setRoutingKey(routingKey);
        message.setPayload(JsonUtils.toJsonStr(payload));
        message.setSent(false);
        if (mapper.insert(message) != 1) {
            throw new DbException("保存积分消息失败");
        }
    }

    @Override
    public List<LocalDate> querySignDates(Long userId, LocalDate date) {
        return mapper.querySignKeys(userId, YearMonth.from(date).toString()).stream()
                .map(key -> LocalDate.parse(key.substring(key.lastIndexOf(':') + 1)))
                .collect(Collectors.toList());
    }
}
