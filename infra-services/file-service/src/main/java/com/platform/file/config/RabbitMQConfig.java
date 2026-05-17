package com.platform.file.config;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    public static final String FILE_EXCHANGE = "file.exchange";
    public static final String ROUTING_KEY_FILE_UPLOADED = "file.uploaded";

    @Bean public TopicExchange fileExchange() { return new TopicExchange(FILE_EXCHANGE, true, false); }
    @Bean public Jackson2JsonMessageConverter messageConverter() { return new Jackson2JsonMessageConverter(); }
    @Bean public RabbitTemplate rabbitTemplate(ConnectionFactory cf, Jackson2JsonMessageConverter conv) {
        RabbitTemplate t = new RabbitTemplate(cf); t.setMessageConverter(conv); return t;
    }
}
