package dev.recall.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Bean
    DirectExchange eventsExchange(EventRouting routing) {
        return new DirectExchange(routing.exchange(), true, false);
    }

    /** Batches the consumer failed on after retries go to the dead-letter queue instead of being lost or looping. */
    @Bean
    Queue eventsQueue(EventRouting routing) {
        return QueueBuilder.durable(routing.queue())
                .deadLetterExchange("")
                .deadLetterRoutingKey(routing.deadLetterQueue())
                .build();
    }

    @Bean
    Queue eventsDeadLetterQueue(EventRouting routing) {
        return QueueBuilder.durable(routing.deadLetterQueue()).build();
    }

    @Bean
    Binding eventsBinding(Queue eventsQueue, DirectExchange eventsExchange, EventRouting routing) {
        return BindingBuilder.bind(eventsQueue).to(eventsExchange).with(routing.routingKey());
    }

    /** JSON on the wire; the listener's parameter type decides the target class, not a header from the sender. */
    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);
        typeMapper.setTrustedPackages("dev.recall.domain");
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }
}
