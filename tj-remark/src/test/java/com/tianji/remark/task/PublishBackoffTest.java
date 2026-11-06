package com.tianji.remark.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishBackoffTest {
    @Test
    void doublesDelayAfterEachFailure() {
        PublishBackoff backoff = new PublishBackoff(1000, 60000);

        backoff.onFailure(10000);
        assertEquals(11000, backoff.nextAttemptAtMs());
        assertTrue(backoff.shouldSkip(10999));
        assertFalse(backoff.shouldSkip(11000));

        backoff.onFailure(11000);
        assertEquals(13000, backoff.nextAttemptAtMs());

        backoff.onFailure(13000);
        assertEquals(17000, backoff.nextAttemptAtMs());
    }

    @Test
    void capsDelayAfterTwentyFailures() {
        PublishBackoff backoff = new PublishBackoff(1000, 60000);

        for (int i = 0; i < 20; i++) {
            backoff.onFailure(10000);
        }

        assertEquals(20, backoff.failureCount());
        assertEquals(70000, backoff.nextAttemptAtMs());
    }

    @Test
    void successResetsFailureCountAndDelay() {
        PublishBackoff backoff = new PublishBackoff(1000, 60000);
        backoff.onFailure(10000);
        backoff.onFailure(11000);

        backoff.onSuccess();

        assertEquals(0, backoff.failureCount());
        assertFalse(backoff.shouldSkip(11000));
        backoff.onFailure(20000);
        assertEquals(21000, backoff.nextAttemptAtMs());
    }
}
