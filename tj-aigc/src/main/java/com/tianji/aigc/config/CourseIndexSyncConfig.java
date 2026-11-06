package com.tianji.aigc.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.tianji.aigc.knowledge.course.*;
import com.tianji.api.client.course.CourseClient;
import com.tianji.common.constants.MqConstants;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableRabbit
@EnableScheduling
@ConditionalOnProperty(prefix = "tj.ai", name = {"knowledge.enabled", "course-retrieval.enabled",
        "course-retrieval.sync.enabled"}, havingValue = "true")
@ConditionalOnProperty(prefix = "tj.ai.knowledge", name = "store-type", havingValue = "elasticsearch", matchIfMissing = true)
public class CourseIndexSyncConfig {
    public static final String QUEUE = "aigc.course.index.sync.queue";
    public static final String FAILED_EXCHANGE = "aigc.course.index.sync.failed";
    public static final String FAILED_QUEUE = "aigc.course.index.sync.failed.queue";

    @Bean
    public CourseIndexRepository courseIndexRepository(ElasticsearchClient client, CourseSemanticSearch initializedIndex) {
        return new CourseIndexRepository(client, CourseImportCommand.COURSE_INDEX);
    }

    @Bean
    public CourseIndexSyncService courseIndexSyncService(CourseClient courses, CourseIndexRepository repository,
            EmbeddingModel embedding,
            @Value("${tj.ai.knowledge.embedding.model}") String model,
            @Value("${tj.ai.knowledge.embedding.dimensions}") int dimensions) {
        return new CourseIndexSyncService(courses, repository, embedding, Clock.systemDefaultZone(), model, dimensions);
    }

    @Bean
    public CourseIndexSyncTask courseIndexSyncTask(CourseClient courses, CourseIndexSyncService sync,
            @Value("${tj.ai.course-retrieval.sync.page-size:50}") int pageSize) {
        return new CourseIndexSyncTask(courses, sync, pageSize);
    }

    @Bean
    public CourseIndexEventListener courseIndexEventListener(CourseIndexSyncService sync) {
        return new CourseIndexEventListener(sync);
    }

    @Bean
    public Declarables courseIndexQueues() {
        var exchange = new TopicExchange(MqConstants.Exchange.COURSE_EXCHANGE, true, false);
        var failedExchange = new DirectExchange(FAILED_EXCHANGE, true, false);
        var queue = QueueBuilder.durable(QUEUE).deadLetterExchange(FAILED_EXCHANGE).deadLetterRoutingKey("failed").build();
        var failed = QueueBuilder.durable(FAILED_QUEUE).build();
        List<Declarable> declarations = new ArrayList<>(List.of(exchange, failedExchange, queue, failed,
                BindingBuilder.bind(failed).to(failedExchange).with("failed")));
        for (String key : List.of(MqConstants.Key.COURSE_UP_KEY, MqConstants.Key.COURSE_DOWN_KEY,
                MqConstants.Key.COURSE_EXPIRE_KEY, MqConstants.Key.COURSE_DELETE_KEY)) {
            declarations.add(BindingBuilder.bind(queue).to(exchange).with(key));
        }
        return new Declarables(declarations);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory courseIndexListenerContainerFactory(
            ConnectionFactory connections, MessageConverter converter) {
        // 不套用公共监听器重试：同步服务内部重试三次，耗尽后由 broker 投入独立失败队列。
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connections);
        factory.setMessageConverter(converter);
        factory.setConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
