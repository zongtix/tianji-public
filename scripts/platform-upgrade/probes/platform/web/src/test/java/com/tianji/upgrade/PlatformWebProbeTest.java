package com.tianji.upgrade;

import com.xxl.job.core.biz.client.ExecutorBizClient;
import com.xxl.job.core.biz.model.ReturnT;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.apache.seata.tm.api.GlobalTransaction;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.test.StepVerifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@DirtiesContext
class PlatformWebProbeTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProbeMapper mapper;
    @Autowired TransactionTemplate transactions;
    @Autowired StringRedisTemplate redis;
    @Autowired RedissonClient redisson;
    @Autowired RabbitTemplate rabbit;
    @Autowired ProbeApplication.IdentityClient identity;
    @Autowired Environment environment;
    @Autowired ApplicationContext context;
    @Autowired ChatModel model;
    @Autowired RestHighLevelClient elasticsearch;

    @BeforeEach void prepare() {
        assertTrue(environment.getRequiredProperty("spring.datasource.url").contains("/tj_upgrade_learning?"));
        assertEquals("tj_upgrade", environment.getRequiredProperty("spring.datasource.username"));
        jdbc.execute("CREATE TABLE IF NOT EXISTS platform_upgrade_probe (id BIGINT PRIMARY KEY, amount INT NOT NULL)");
        jdbc.update("DELETE FROM platform_upgrade_probe WHERE id=900099001");
        jdbc.update("INSERT INTO platform_upgrade_probe (id,amount) VALUES (900099001,10)");
    }

    @Test void nacosConfigAndFeignDiscoveryPreserveIdentity() {
        assertEquals("isolated-framework-probe", environment.getProperty("upgrade.probe.marker"));
        assertEquals("900000001", identity.identity("900000001"));
    }

    @Test void mybatisAndLocalTransactionRollback() {
        assertEquals(10, mapper.amount(900099001));
        assertThrows(IllegalStateException.class, () -> transactions.execute(status -> {
            jdbc.update("UPDATE platform_upgrade_probe SET amount=20 WHERE id=900099001");
            throw new IllegalStateException("intentional probe rollback");
        }));
        assertEquals(10, mapper.amount(900099001));
    }

    @Test void seataAtRollsBackCommittedLocalBranch() throws Exception {
        GlobalTransaction global = GlobalTransactionContext.getCurrentOrCreate();
        global.begin(30000, "isolated-platform-probe");
        try {
            transactions.execute(status -> {
                jdbc.update("UPDATE platform_upgrade_probe SET amount=30 WHERE id=900099001");
                return null;
            });
            assertEquals(30, mapper.amount(900099001));
        } finally { global.rollback(); }
        for (int attempt=0; attempt<20 && mapper.amount(900099001)!=10; attempt++) Thread.sleep(250);
        assertEquals(10, mapper.amount(900099001), "AT coordinator must undo the committed branch");
    }

    @Test void redisAndRedissonUseOnlyApplicationTestInstance() throws Exception {
        String key = "platform-upgrade:probe:" + UUID.randomUUID();
        try {
            redis.opsForValue().set(key, "isolated");
            assertEquals("isolated", redis.opsForValue().get(key));
            var lock = redisson.getLock(key + ":lock");
            assertTrue(lock.tryLock(1, 5, TimeUnit.SECONDS));
            try { assertTrue(lock.isHeldByCurrentThread()); } finally { lock.unlock(); }
        } finally { redis.delete(key); }
    }

    @Test void rabbitRoundTripStaysInsideTestVhost() {
        assertEquals("tianji-platform-upgrade-test", environment.getProperty("spring.rabbitmq.virtual-host"));
        RabbitAdmin admin = new RabbitAdmin(rabbit.getConnectionFactory());
        String queue = "platform-upgrade.probe." + UUID.randomUUID();
        admin.declareQueue(new Queue(queue, false, false, true));
        try {
            rabbit.convertAndSend("", queue, "isolated-message");
            assertEquals("isolated-message", rabbit.receiveAndConvert(queue, 5000));
        } finally { admin.deleteQueue(queue); }
    }

    @Test void xxlExecutorProtocolWorksWithoutLiveAdmin() {
        assertEquals(ReturnT.SUCCESS_CODE, new ExecutorBizClient("http://127.0.0.1:19998", "upgrade-probe-token").beat().getCode());
    }

    @Test void aiProviderBindsAndSubstituteStreamsOverHttp() throws Exception {
        assertTrue(context.getBeansOfType(ChatModel.class).values().stream()
            .anyMatch(bean -> bean.getClass().getName().contains("DashScopeChatModel")));
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18098/probe/chat")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        assertTrue(response.body().contains("data:first"));
        assertTrue(response.body().contains("data:second"));
    }

    @Test void cancellingChatCancelsSubstituteUpstream() {
        ProbeApplication.CANCELLED.set(false);
        StepVerifier.create(ChatClient.builder(model).build().prompt().user("test").stream().content(), 1)
            .expectNext("first").thenCancel().verify();
        assertTrue(ProbeApplication.CANCELLED.get());
    }

    @Test void elasticsearchSevenClientKeepsIndexAndSearchCompatible() throws Exception {
        String index = "tj_upgrade_probe_" + UUID.randomUUID().toString().replace("-", "");
        elasticsearch.indices().create(new CreateIndexRequest(index).settings(Settings.builder()
            .put("index.number_of_shards", 1).put("index.number_of_replicas", 0)), RequestOptions.DEFAULT);
        try {
            elasticsearch.index(new IndexRequest(index).id("fixture").source(Map.of("name", "upgrade-only")), RequestOptions.DEFAULT);
            elasticsearch.indices().refresh(new RefreshRequest(index), RequestOptions.DEFAULT);
            var result = elasticsearch.search(new SearchRequest(index).source(new SearchSourceBuilder()
                .query(QueryBuilders.termQuery("name.keyword", "upgrade-only"))), RequestOptions.DEFAULT);
            assertEquals(1, result.getHits().getTotalHits().value);
        } finally { elasticsearch.indices().delete(new DeleteIndexRequest(index), RequestOptions.DEFAULT); }
    }

    @Test void openApiAndKnife4jServeUnderSpringSix() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        var json = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18098/v3/api-docs")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, json.statusCode());
        assertTrue(json.body().contains("/probe/identity"));
        var ui = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18098/doc.html")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ui.statusCode());
    }
}
