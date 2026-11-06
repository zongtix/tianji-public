package com.tianji.learning.config;

import com.tianji.common.constants.MqConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
public class PointsMqConfig {
    @Bean
    public ApplicationRunner removeLegacyPointsBindings(AmqpAdmin admin) {
        return args -> {
            // 新消费者已使用独立队列；只解绑旧队列，保留历史积压供核对和分流，不再积累新消息。
            try {
                if (admin.getQueueProperties("learning.points.queue") == null) {
                    return;
                }
                for (String key : new String[]{MqConstants.Key.LEARN_SECTION, MqConstants.Key.WRITE_NOTE, MqConstants.Key.WRITE_COMMENT}) {
                    admin.removeBinding(new Binding("learning.points.queue", Binding.DestinationType.QUEUE,
                            MqConstants.Exchange.LEARNING_EXCHANGE, key, null));
                }
            } catch (AmqpException e) {
                // Broker 暂不可用时不能阻止启动；新队列仍由监听容器声明，待发送消息由任务保留。
                log.warn("旧积分队列解绑失败；恢复 RabbitMQ 后重启 learning-service 完成解绑，历史消息仍保留", e);
            }
        };
    }
}
