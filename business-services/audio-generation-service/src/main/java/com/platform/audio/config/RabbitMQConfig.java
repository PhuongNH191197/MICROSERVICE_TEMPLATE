package com.platform.audio.config;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {
    public static final String AUDIO_EXCHANGE = "audio.exchange";
    public static final String AI_GENERATE_QUEUE = "audio.ai.generate.queue";
    public static final String DIY_MIX_QUEUE = "audio.diy.mix.queue";
    public static final String AUDIO_DLQ = "audio.dlq";
    public static final String ROUTING_AI = "audio.ai.generate";
    public static final String ROUTING_DIY = "audio.diy.mix";

    @Bean public Jackson2JsonMessageConverter messageConverter() { return new Jackson2JsonMessageConverter(); }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory cf, Jackson2JsonMessageConverter converter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(cf);
        factory.setMessageConverter(converter);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory cf, Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(cf);
        template.setMessageConverter(converter);
        return template;
    }

    @Bean public TopicExchange audioExchange() { return new TopicExchange(AUDIO_EXCHANGE, true, false); }

    @Bean public Queue aiGenerateQueue() {
        return QueueBuilder.durable(AI_GENERATE_QUEUE)
            .withArgument("x-dead-letter-exchange", AUDIO_DLQ).build();
    }
    @Bean public Queue diyMixQueue() {
        return QueueBuilder.durable(DIY_MIX_QUEUE)
            .withArgument("x-dead-letter-exchange", AUDIO_DLQ).build();
    }
    @Bean public Queue audioDlq() { return QueueBuilder.durable(AUDIO_DLQ).build(); }

    @Bean public Binding aiBinding() {
        return BindingBuilder.bind(aiGenerateQueue()).to(audioExchange()).with(ROUTING_AI);
    }
    @Bean public Binding diyBinding() {
        return BindingBuilder.bind(diyMixQueue()).to(audioExchange()).with(ROUTING_DIY);
    }
}
