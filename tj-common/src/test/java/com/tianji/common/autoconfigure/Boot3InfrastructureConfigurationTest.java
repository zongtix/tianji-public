package com.tianji.common.autoconfigure;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.tianji.common.autoconfigure.mq.MqConfig;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.autoconfigure.mvc.ParamCheckerConfig;
import com.tianji.common.autoconfigure.mvc.aspects.CheckerAspect;
import com.tianji.common.autoconfigure.mybatis.MybatisConfig;
import com.tianji.common.autoconfigure.redisson.RedissonConfig;
import com.tianji.common.autoconfigure.redisson.aspect.LockAspect;
import com.tianji.common.autoconfigure.xxljob.XxlJobConfig;
import com.xxl.job.core.biz.client.ExecutorBizClient;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class Boot3InfrastructureConfigurationTest {
    @TempDir Path logs;

    @Test
    void createsInfrastructureBeansAndBindsRenamedRedisProperties() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, RabbitAutoConfiguration.class))
                .withUserConfiguration(MqConfig.class, MybatisConfig.class, ParamCheckerConfig.class, RedissonConfig.class)
                .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
                .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .withPropertyValues("spring.application.name=upgrade-contract", "spring.rabbitmq.dynamic=false",
                        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=26379")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(CheckerAspect.class)
                            .hasSingleBean(MybatisPlusInterceptor.class).hasSingleBean(LockAspect.class)
                            .hasSingleBean(MessageConverter.class).hasSingleBean(MessageRecoverer.class)
                            .hasSingleBean(RabbitMqHelper.class).hasSingleBean(SimpleRabbitListenerContainerFactory.class);
                    assertThat(context.getBean(RedisProperties.class).getPort()).isEqualTo(26379);
                    assertThat(context.getBean(MybatisPlusInterceptor.class).getInterceptors()).hasSize(2);
                });
    }

    @Test
    void commonXxlConfigurationStartsExecutorWithoutLiveAdmin() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        new ApplicationContextRunner().withUserConfiguration(XxlJobConfig.class)
                .withPropertyValues("tj.xxl-job.executor.app-name=upgrade-contract",
                        "tj.xxl-job.executor.ip=127.0.0.1", "tj.xxl-job.executor.port=" + port,
                        "tj.xxl-job.executor.log-path=" + logs, "tj.xxl-job.access-token=contract-token")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(XxlJobSpringExecutor.class);
                    ExecutorBizClient client = new ExecutorBizClient("http://127.0.0.1:" + port, "contract-token");
                    ReturnT<String> result = null;
                    for (int attempt = 0; attempt < 30; attempt++) {
                        result = client.beat();
                        if (result.getCode() == ReturnT.SUCCESS_CODE) break;
                        Thread.sleep(100);
                    }
                    assertThat(result.getCode()).isEqualTo(ReturnT.SUCCESS_CODE);
                });
    }
}
