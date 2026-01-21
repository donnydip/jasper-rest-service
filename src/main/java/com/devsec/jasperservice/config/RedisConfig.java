package com.devsec.jasperservice.config;

import com.devsec.jasperservice.jobs.ReportJobListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

@Configuration
public class RedisConfig {

    public static final String REPORT_CHANNEL = "report-jobs";

    @Bean
    MessageListenerAdapter messageListener(ReportJobListener reportJobListener) {
        return new MessageListenerAdapter(reportJobListener);
    }

    @Bean
    RedisMessageListenerContainer redisContainer(RedisConnectionFactory connectionFactory, MessageListenerAdapter listenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listenerAdapter, new ChannelTopic(REPORT_CHANNEL));
        return container;
    }
}
