package dev.recall.service;

import dev.recall.domain.EventBatch;

/** Hands a batch to the queue. Returns only once the broker has confirmed it. */
public interface EventPublisher {

    void publish(EventBatch batch);

    class QueueUnavailableException extends RuntimeException {
        public QueueUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
