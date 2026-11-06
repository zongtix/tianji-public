package com.tianji.aigc.knowledge.course;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Refresh;
import com.tianji.aigc.config.CourseIndexSyncConfig;
import com.tianji.aigc.config.KnowledgeStoreConfig;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CourseIndexInfoDTO;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.ResponseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 ES / RabbitMQ；合成课程与固定向量，不调用付费模型，也不发到业务交换机。 */
@EnabledIfEnvironmentVariable(named = "TJ_AIGC_TEST_COURSE_SYNC", matches = "true")
class CourseIndexSyncIntegrationTest {
    private final CourseClient courses = mock(CourseClient.class);
    private final EmbeddingModel embedding = mock(EmbeddingModel.class);

    @Test
    @SuppressWarnings("unchecked")
    void preservesLegacyDocumentsDisablesRestoresAndRejectsStaleWritesInRealElasticsearch() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        ElasticsearchRestClientAutoConfiguration.class, ElasticsearchClientAutoConfiguration.class))
                .withPropertyValues("spring.elasticsearch.uris=http://127.0.0.1:19201",
                        "spring.elasticsearch.username=tj_aigc_app",
                        "spring.elasticsearch.password=" + System.getenv("TJ_AIGC_ES_PASSWORD"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var client = context.getBean(ElasticsearchClient.class);
                    String index = "tj_aigc_sync_test_" + UUID.randomUUID().toString().replace("-", "");
                    boolean created = false;
                    try {
                        float[] vector = new float[1024]; vector[0] = 1;
                        when(embedding.embed(anyString())).thenReturn(vector);
                        var vectors = new KnowledgeStoreConfig().knowledgeVectorStore(context.getBean(RestClient.class),
                                client, embedding, index, 1024, context.getBeanProvider(io.micrometer.observation.ObservationRegistry.class));
                        created = true;
                        var repository = new CourseIndexRepository(client, index);
                        var sync = new CourseIndexSyncService(courses, repository, embedding, Clock.systemDefaultZone(), "text-embedding-v4", 1024);
                        long id = 910000000000099001L;
                        var course = new CourseIndexInfoDTO();
                        course.setId(id); course.setName("并发库存课程测试"); course.setStatus(2); course.setDeleted(0);
                        course.setIntroduce("合成资料"); course.setDetail("Java并发扣库存");
                        when(courses.getSemanticIndexInfo(id)).thenReturn(course);

                        // 模拟原手动导入方式，保证旧 hash 和文档 ID 可以直接接管。
                        var legacy = CourseDocumentService.toDocument(new CourseDocumentService.Course(id, course.getName(), 2,
                                null, null, course.getIntroduce(), null, course.getDetail()), "text-embedding-v4", 1024);
                        client.index(i -> i.index(index).id(legacy.getId()).refresh(Refresh.True).document(Map.of(
                                "id", legacy.getId(), "content", legacy.getText(), "metadata", legacy.getMetadata(), "embedding", vector)));
                        assertThat(sync.synchronize(id)).isEqualTo(CourseIndexSyncService.Result.UNCHANGED);
                        verifyNoInteractions(embedding);
                        course.setDetail("Java并发与库存更新的新正文");
                        assertThat(sync.synchronize(id)).isEqualTo(CourseIndexSyncService.Result.UPDATED);
                        var stale = repository.find(id);
                        assertThat(stale.id()).isEqualTo(legacy.getId());
                        assertThat(client.count(c -> c.index(index)).count()).isEqualTo(1);
                        course.setStatus(3);
                        assertThat(sync.synchronize(id)).isEqualTo(CourseIndexSyncService.Result.DISABLED);
                        assertThat(repository.find(id).source()).doesNotContainKey("embedding");
                        assertThat(vectors.similaritySearch(SearchRequest.builder().query("库存").topK(5)
                                .filterExpression("documentType == 'course'").build())).isEmpty();
                        assertConflict(() -> repository.write(stale, stale.source()));
                        course.setStatus(2);
                        assertThat(sync.synchronize(id)).isEqualTo(CourseIndexSyncService.Result.UPDATED);
                        assertThat(vectors.similaritySearch(SearchRequest.builder().query("库存").topK(5)
                                .filterExpression("documentType == 'course'").build())).hasSize(1);
                        course.setDeleted(1);
                        assertThat(sync.synchronize(id)).isEqualTo(CourseIndexSyncService.Result.DISABLED);

                        // 尚不存在时也采用 create 条件，禁用占位不能被并发旧创建覆盖。
                        long missingId = id + 1;
                        assertThat(sync.synchronize(missingId)).isEqualTo(CourseIndexSyncService.Result.DISABLED);
                        var disabled = repository.find(missingId);
                        assertConflict(() -> repository.write(null, disabled.source()));

                        var duplicate = new LinkedHashMap<String, Object>(disabled.source());
                        duplicate.put("id", "duplicate");
                        client.index(i -> i.index(index).id("duplicate").refresh(Refresh.True).document(duplicate));
                        assertThatThrownBy(() -> repository.find(missingId)).isInstanceOf(IllegalStateException.class);
                    } finally {
                        if (created) client.indices().delete(d -> d.index(index));
                    }
                });
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable write) {
        Throwable failure = catchThrowable(write);
        int status = failure instanceof ElasticsearchException elastic ? elastic.status()
                : failure instanceof ResponseException response ? response.getResponse().getStatusLine().getStatusCode() : -1;
        assertThat(status).as("旧版本写入必须收到 HTTP 409").isEqualTo(409);
    }

    @Test
    void failedDeliveryRetriesThreeTimesThenMovesToIsolatedDeadLetterQueue() throws Exception {
        String prefix = "aigc.course.sync.test." + UUID.randomUUID();
        String queue = prefix + ".queue", failedQueue = prefix + ".failed.queue";
        var connection = new CachingConnectionFactory("127.0.0.1", 5672);
        connection.setUsername(System.getenv("TJ_SYNC_TEST_MQ_USERNAME"));
        connection.setPassword(System.getenv("TJ_SYNC_TEST_MQ_PASSWORD"));
        connection.setVirtualHost(System.getenv("TJ_SYNC_TEST_MQ_VHOST"));
        var admin = new RabbitAdmin(connection);
        var converter = new Jackson2JsonMessageConverter();
        var template = new RabbitTemplate(connection);
        template.setMessageConverter(converter);
        var endpoint = new SimpleRabbitListenerEndpoint();
        endpoint.setId(prefix); endpoint.setQueueNames(queue);
        var repository = mock(CourseIndexRepository.class);
        var sync = new CourseIndexSyncService(courses, repository, embedding, Clock.systemDefaultZone(), "text-embedding-v4", 1024);
        when(courses.getSemanticIndexInfo(99L)).thenThrow(new IllegalStateException("synthetic course service offline"));
        var adapter = new MessageListenerAdapter(new CourseIndexEventListener(sync), converter);
        adapter.setDefaultListenerMethod("onCourseChanged");
        endpoint.setMessageListener(adapter);
        var factory = new CourseIndexSyncConfig().courseIndexListenerContainerFactory(connection, converter);
        var container = factory.createListenerContainer(endpoint);
        try {
            admin.declareExchange(new DirectExchange(prefix));
            admin.declareQueue(QueueBuilder.durable(queue).deadLetterExchange(prefix).deadLetterRoutingKey("failed").build());
            admin.declareQueue(QueueBuilder.durable(failedQueue).build());
            admin.declareBinding(new Binding(failedQueue, Binding.DestinationType.QUEUE, prefix, "failed", null));
            container.afterPropertiesSet(); container.start();
            template.convertAndSend("", queue, 99L);
            var failed = template.receive(failedQueue, 10000);
            assertThat(failed).isNotNull();
            verify(courses, times(3)).getSemanticIndexInfo(99L);
            verify(repository, never()).write(any(), anyMap());
            assertThat(template.receive(queue, 200)).isNull();
            assertThat(failed.getMessageProperties().getHeaders()).containsKey("x-death");
        } finally {
            container.stop();
            try {
                admin.deleteQueue(queue); admin.deleteQueue(failedQueue); admin.deleteExchange(prefix);
            } finally { connection.destroy(); }
        }
    }
}
