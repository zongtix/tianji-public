package com.tianji.api.client.learning;

import com.tianji.api.client.learning.fallback.LearningClientFallback;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LearningClientFallbackTest {
    @Test
    void failedExistenceLookupCannotBeReportedAsAnEmptyResult() {
        RuntimeException cause = new RuntimeException("learning unavailable");
        LearningClient client = new LearningClientFallback().create(cause);

        assertSame(cause, assertThrows(IllegalStateException.class,
                () -> client.queryExistingReplyIds(List.of(2L))).getCause());
    }
}
