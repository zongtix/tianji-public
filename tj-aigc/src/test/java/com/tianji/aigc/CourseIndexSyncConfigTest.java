package com.tianji.aigc;

import com.tianji.aigc.config.CourseIndexSyncConfig;
import com.tianji.aigc.knowledge.course.CourseIndexSyncService;
import com.tianji.aigc.knowledge.course.CourseIndexSyncTask;
import com.tianji.aigc.knowledge.course.CourseSemanticSearch;
import com.tianji.api.client.course.CourseClient;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.LifecycleProcessor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CourseIndexSyncConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CourseIndexSyncConfig.class);

    @Test
    void syncIsOptInAndDisabledModesRequireNoExternalServices() {
        runner.withPropertyValues("tj.ai.knowledge.enabled=true", "tj.ai.course-retrieval.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CourseIndexSyncService.class));
        for (String disabled : new String[]{"knowledge.enabled=false", "course-retrieval.enabled=false", "knowledge.store-type=redis"}) {
            runner.withPropertyValues("tj.ai.knowledge.enabled=true", "tj.ai.course-retrieval.enabled=true",
                            "tj.ai.course-retrieval.sync.enabled=true", "tj.ai." + disabled)
                    .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CourseIndexSyncService.class));
        }
    }

    @Test
    void enabledConfigurationWiresSyncWithoutReplacingExistingRetrieval() {
        runner.withInitializer(context -> context.getBeanFactory()
                        .registerSingleton("lifecycleProcessor", mock(LifecycleProcessor.class)))
                .withBean(CourseClient.class, () -> mock(CourseClient.class))
                .withBean(ElasticsearchClient.class, () -> mock(ElasticsearchClient.class))
                .withBean(EmbeddingModel.class, () -> mock(EmbeddingModel.class))
                .withBean(CourseSemanticSearch.class, () -> mock(CourseSemanticSearch.class))
                .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
                .withBean("messageConverter", Jackson2JsonMessageConverter.class, Jackson2JsonMessageConverter::new)
                .withPropertyValues("tj.ai.knowledge.enabled=true", "tj.ai.course-retrieval.enabled=true",
                        "tj.ai.course-retrieval.sync.enabled=true", "tj.ai.knowledge.embedding.model=text-embedding-v4",
                        "tj.ai.knowledge.embedding.dimensions=1024", "tj.ai.course-retrieval.sync.initial-delay-ms=600000")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(CourseIndexSyncService.class)
                        .hasSingleBean(CourseIndexSyncTask.class).hasSingleBean(CourseSemanticSearch.class));
    }

    @Test
    void eventsUseAnIndependentDurableQueueWithABoundedFailureDestination() {
        var declarations = new CourseIndexSyncConfig().courseIndexQueues();
        var queues = declarations.getDeclarablesByType(Queue.class);
        assertThat(queues).extracting(Queue::getName)
                .containsExactly(CourseIndexSyncConfig.QUEUE, CourseIndexSyncConfig.FAILED_QUEUE);
        assertThat(queues).allMatch(Queue::isDurable);
        assertThat(queues.get(0).getArguments()).containsEntry("x-dead-letter-exchange", CourseIndexSyncConfig.FAILED_EXCHANGE);
        assertThat(declarations.getDeclarablesByType(Binding.class)).extracting(Binding::getRoutingKey)
                .containsExactlyInAnyOrder("failed", "course.up", "course.down", "course.expire", "course.delete");
    }
}
