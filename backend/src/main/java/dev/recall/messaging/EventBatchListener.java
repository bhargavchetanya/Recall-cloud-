package dev.recall.messaging;

import dev.recall.domain.EventBatch;
import dev.recall.service.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class EventBatchListener {

    private static final Logger log = LoggerFactory.getLogger(EventBatchListener.class);

    private final EventStore store;

    public EventBatchListener(EventStore store) {
        this.store = store;
    }

    @RabbitListener(queues = "#{@eventRouting.queue()}")
    public void onBatch(EventBatch batch) {
        EventStore.StoreResult result = store.persist(batch);
        log.debug("Stored batch for team {}: {}", batch.teamId(), result);
    }
}
