package com.commercecore.backend.shared.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class OutboxPoller {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxRepository outboxRepository;
    // private final MessageBrokerPublisher publisher; // Kafka/RabbitMQ client

    public OutboxPoller(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    /**
     * Runs every 2 seconds.
     * The @Transactional boundary is CRITICAL here.
     * We must lock the rows, publish them, and mark them as published
     * in a single atomic database transaction.
     */
    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void pollAndPublish() {
        // 1. Fetch and LOCK a batch of events (SKIP LOCKED prevents duplicates)
        List<OutboxEvent> batch = outboxRepository.findUnpublishedBatch(BATCH_SIZE);

        if (batch.isEmpty()) {
            return;
        }

        LOG.info("Polled {} outbox events for publishing", batch.size());

        List<UUID> processedIds = new java.util.ArrayList<>();

        for (OutboxEvent event : batch) {
            try {
                // 2. Publish to Message Broker (Kafka/RabbitMQ/SQS)
                publishToBroker(event);

                // 3. Track successful IDs
                processedIds.add(event.getId());

            } catch (Exception e) {
                // CRITICAL FAILURE HANDLING:
                // If the broker is down, we MUST NOT mark the event as published.
                // By throwing an exception (or just breaking the loop and not
                // adding to processedIds), the transaction will roll back (or
                // the specific rows won't be updated), and the events will be
                // retried on the next poll cycle.
                LOG.error("Failed to publish event {}. Halting batch to preserve order.",
                        event.getId(), e);
                break;
            }
        }

        // 4. Mark only the successfully published events as PUBLISHED
        if (!processedIds.isEmpty()) {
            outboxRepository.markAsPublished(processedIds, Instant.now());
        }
    }

    private void publishToBroker(OutboxEvent event) {
        // In production, this calls your Kafka/RabbitMQ producer.
        // e.g., kafkaTemplate.send(event.getEventType(), event.getAggregateId().toString(), event.getPayload());
        LOG.debug("Publishing event: {} for aggregate: {}",
                event.getEventType(), event.getAggregateId());
    }
}