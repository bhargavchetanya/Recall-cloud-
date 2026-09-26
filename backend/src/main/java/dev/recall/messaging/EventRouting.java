package dev.recall.messaging;

import dev.recall.config.RecallProperties;
import org.springframework.stereotype.Component;

/**
 * Names of the events exchange, routing key and queues. With {@code recall.events.instance} set (tests do this per
 * Spring context), the routing key and queues get that suffix so parallel contexts never steal each other's batches.
 */
@Component
public class EventRouting {

    public static final String EXCHANGE = "recall.events";
    static final String DEFAULT_ROUTING_KEY = "events";
    static final String QUEUE_BASE = "recall.events.ingest";

    private final String routingKey;
    private final String queue;
    private final String deadLetterQueue;

    public EventRouting(RecallProperties properties) {
        String instance = properties.events().instance();
        boolean shared = instance == null || instance.isBlank();
        this.routingKey = shared ? DEFAULT_ROUTING_KEY : instance;
        this.queue = shared ? QUEUE_BASE : QUEUE_BASE + "." + instance;
        this.deadLetterQueue = queue + ".dlq";
    }

    public String exchange() {
        return EXCHANGE;
    }

    public String routingKey() {
        return routingKey;
    }

    public String queue() {
        return queue;
    }

    public String deadLetterQueue() {
        return deadLetterQueue;
    }
}
