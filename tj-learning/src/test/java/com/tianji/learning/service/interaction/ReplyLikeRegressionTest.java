package com.tianji.learning.service.interaction;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tianji.api.dto.LikedTimesDTO;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.mapper.interaction.InteractionReplyMapper;
import com.tianji.learning.mq.LikeTimesChangeListener;
import com.tianji.learning.service.interaction.impl.InteractionReplyServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReplyLikeRegressionTest {
    @Test
    void existenceQueryReturnsOnlyDatabaseIdsAndSelectsOnlyPrimaryKey() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                InteractionReply.class);
        InteractionReplyMapper mapper = mock(InteractionReplyMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(new InteractionReply().setId(2L)));
        // 构造参数按 final 字段声明顺序：questionMapper, userClient, likedRecordClient, pointsMessageService
        InteractionReplyServiceImpl service = new InteractionReplyServiceImpl(null, null, null, null, null);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        // MyBatis-Plus 3.5 derives this from the real mapper proxy; these tests inject a mock.
        ReflectionTestUtils.setField(service, "entityClass", InteractionReply.class);

        assertEquals(Set.of(2L), service.queryExistingReplyIds(List.of(2L, 999L)));

        ArgumentCaptor<Wrapper<InteractionReply>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(query.capture());
        assertEquals("id", query.getValue().getSqlSelect());
    }

    @Test
    void emptyExistenceQueryDoesNotScanDatabase() {
        InteractionReplyMapper mapper = mock(InteractionReplyMapper.class);
        InteractionReplyServiceImpl service = new InteractionReplyServiceImpl(null, null, null, null, null);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        assertEquals(Set.of(), service.queryExistingReplyIds(List.of()));
        verifyNoInteractions(mapper);
    }

    @Test
    void duplicateAndOlderSnapshotsNeverIncrementTheCount() {
        InteractionReplyMapper mapper = mock(InteractionReplyMapper.class);
        AtomicInteger count = new AtomicInteger();
        AtomicLong version = new AtomicLong();
        when(mapper.updateLikedTimesIfNewer(eq(2L), anyInt(), anyLong())).thenAnswer(invocation -> {
            long incoming = invocation.getArgument(2);
            if (incoming <= version.get()) {
                return 0;
            }
            count.set(invocation.getArgument(1));
            version.set(incoming);
            return 1;
        });
        LikeTimesChangeListener listener = new LikeTimesChangeListener(mapper);

        listener.listenQaLikedTimesChange(LikedTimesDTO.of("QA", 2L, 1, 100L));
        listener.listenQaLikedTimesChange(LikedTimesDTO.of("QA", 2L, 1, 100L));
        assertEquals(1, count.get());
        listener.listenQaLikedTimesChange(LikedTimesDTO.of("QA", 2L, 0, 101L));
        listener.listenQaLikedTimesChange(LikedTimesDTO.of("QA", 2L, 1, 100L));
        assertEquals(0, count.get());
        assertEquals(101L, version.get());
    }
}
