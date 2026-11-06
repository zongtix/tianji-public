package com.tianji.aigc.longterm;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UserMemoryWorkerObservabilityTest {
    @Test
    void extractionCandidatesAreOptInAndSensitiveDetailsAreHidden() {
        var observations = ObservationRegistry.create();
        var completed = new CopyOnWriteArrayList<Observation.Context>();
        observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            public boolean supportsContext(Observation.Context context) { return true; }
            public void onStop(Observation.Context context) { completed.add(context); }
        });
        var store = mock(UserMemoryStore.class);
        var extractor = mock(UserMemoryExtractor.class);
        var batch = new UserMemoryStore.Batch(7, "session-a", 0, "token", List.of(), List.of(), 1);
        when(store.claimDue(eq(3), any(Duration.class))).thenReturn(batch);
        when(store.apply(eq(batch), any())).thenReturn(true);
        when(extractor.extract(batch)).thenReturn(List.of(
                new UserMemoryStore.Candidate("background", "学过 Java", "a", "学过 Java"),
                new UserMemoryStore.Candidate("preference", "电话 138 0000 0000", "b", "电话")));
        try (var worker = new UserMemoryWorker(store, extractor, observations)) {
            worker.poll();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(completed).hasSize(1));
            assertThat(completed.get(0).getHighCardinalityKeyValue("langfuse.session.id").getValue()).isEqualTo("7_session-a");
            assertThat(completed.get(0).getHighCardinalityKeyValue("user_memory.candidates")).isNull();
            ReflectionTestUtils.setField(worker, "captureMemoryDetails", true);
            worker.poll();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(completed).hasSize(2));
            String value = completed.get(1).getHighCardinalityKeyValue("user_memory.candidates").getValue();
            assertThat(value).contains("学过 Java", "已隐藏").doesNotContain("138", "0000");
        }
    }
}
