package com.tianji.remark.controller;

import com.tianji.common.exceptions.BadRequestException;
import com.tianji.remark.service.ILikedRecordService;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LikedRecordControllerTest {
    @Test
    void invalidBizTypeIsRejectedBeforeEmptyIdsReturn() {
        ILikedRecordService service = mock(ILikedRecordService.class);
        LikedRecordController controller = new LikedRecordController(service);

        BadRequestException statusError = assertThrows(BadRequestException.class,
                () -> controller.queryMyLikedBizIds("BAD", Collections.emptyList()));
        BadRequestException timesError = assertThrows(BadRequestException.class,
                () -> controller.queryLikedTimes("BAD", Collections.emptyList()));

        assertEquals(400, statusError.getStatus());
        assertEquals(400, timesError.getStatus());
        verifyNoInteractions(service);
    }
}
