package dev.recall.messaging;

import dev.recall.config.RecallProperties;
import dev.recall.domain.EventBatch;
import dev.recall.service.EventPublisher;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** Publishes with broker confirms, so a 202 from /events means RabbitMQ has the batch on disk. */
@Component
public class RabbitEventPublisher implements EventPublisher {

    private final RabbitTemplate rabbit;
    private final EventRouting routing;
    private final Duration confirmTimeout;

    public RabbitEventPublisher(RabbitTemplate rabbit, EventRouting routing, RecallProperties properties) {
        this.rabbit = rabbit;
        this.routing = routing;
        this.confirmTimeout = properties.events().publishConfirmTimeout();
    }

    @Override
    public void publish(EventBatch batch) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbit.convertAndSend(routing.exchange(), routing.routingKey(), batch, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new QueueUnavailableException("Broker rejected the batch: " + confirm.getReason(), null);
            }
        } catch (AmqpException | ExecutionException | TimeoutException e) {
            throw new QueueUnavailableException("Could not hand the batch to RabbitMQ", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QueueUnavailableException("Interrupted while waiting for RabbitMQ", e);
        }
    }
}
