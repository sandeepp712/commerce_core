package com.commercecore.backend.flashsale.config;


import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class RabbitMQConfig {

    @Value("${flash-sale.queue-name}")
    private String queueName;

    @Value("${flash-sale.exchange-name}")
    private String exchangeName;

    @Value("${flash-sale.routing-key}")
    private String routingKey;

    // ─── Topology: Exchange → Queue → Binding ────────────────────────────────
    @Bean
    public DirectExchange flashsaleExchange() {
        return new DirectExchange(exchangeName, true, false);
    }

    @Bean
    public Queue flashsaleQueue() {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange",exchangeName + ".dlx")
                .withArgument("x-dead-letter-routing-key", "order.failed")
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(queueName + ".dlq").build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(exchangeName + ".dlx", true, false);
    }

    @Bean
    public Binding flashsaleBinding() {
        return BindingBuilder.bind(flashsaleQueue())
                .to(flashsaleExchange())
                .with(routingKey);
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue())
                .to(deadLetterExchange())
                .with("order.failed");
    }


    // ─── Serialization ───────────────────────────────────────────────────────

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter(JsonMapper.builder().build());
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(10);  // Batch size: consume 10 messages at a time
        return factory;
    }
}