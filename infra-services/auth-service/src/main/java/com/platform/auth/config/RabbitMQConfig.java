package com.platform.auth.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    public static final String AUTH_EXCHANGE = "auth.exchange";
    public static final String ROUTING_KEY_USER_REGISTERED = "user.registered";
    public static final String ROUTING_KEY_PASSWORD_RESET = "user.password.reset";

    @Bean
    public TopicExchange authExchange() {
        return new TopicExchange(AUTH_EXCHANGE, true, false);
    }
}
