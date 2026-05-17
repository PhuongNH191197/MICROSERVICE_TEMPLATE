package com.platform.userprofile.config;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    @Bean public Jackson2JsonMessageConverter messageConverter() { return new Jackson2JsonMessageConverter(); }
    @Bean
    public Queue userProfileQueue() { return QueueBuilder.durable("user.profile.queue").build(); }
    @Bean
    public TopicExchange authExchange() { return new TopicExchange("auth.exchange", true, false); }
    @Bean
    public Binding userProfileBinding() {
        return BindingBuilder.bind(userProfileQueue()).to(authExchange()).with("user.registered");
    }
}
