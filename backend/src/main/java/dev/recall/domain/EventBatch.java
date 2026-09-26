package dev.recall.domain;

import java.util.List;
import java.util.UUID;

/** What travels over RabbitMQ: team and user come from the caller's JWT, never from the request body. */
public record EventBatch(UUID teamId, UUID userId, List<CapturedEvent> events) {}
