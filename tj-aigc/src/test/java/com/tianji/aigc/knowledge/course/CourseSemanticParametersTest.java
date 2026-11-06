package com.tianji.aigc.knowledge.course;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.document.Document;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CourseSemanticParametersTest {
    @Test
    void configuredParametersReachTheCourseIndexAndLimitResults() {
        var vectors = mock(VectorStore.class);
        when(vectors.similaritySearch(any(SearchRequest.class))).thenAnswer(call -> {
            SearchRequest request = call.getArgument(0);
            assertThat(request.getTopK()).isEqualTo(2);
            assertThat(request.getSimilarityThreshold()).isEqualTo(0.4);
            assertThat(request.getFilterExpression()).isNotNull();
            return List.of(new Document("a", Map.of("courseId", "1")),
                    new Document("b", Map.of("courseId", "2")), new Document("c", Map.of("courseId", "3")));
        });
        assertThat(new CourseSemanticSearch(vectors, 2, 0.4).search("Java")).containsExactly(1L, 2L);
    }

    @Test
    void invalidParametersFailBeforeAnyEmbeddingRequest() {
        var vectors = mock(VectorStore.class);
        assertThatIllegalArgumentException().isThrownBy(() -> new CourseSemanticSearch(vectors, 0, 0.6));
        for (double threshold : new double[]{-0.1, 1.1, Double.NaN}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new CourseSemanticSearch(vectors, 5, threshold));
        }
        verifyNoInteractions(vectors);
    }
}
