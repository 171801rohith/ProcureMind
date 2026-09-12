package com.procuremind.contract_service.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Retry and dead-letter policy for the contract lifecycle listeners.
 *
 * <p>These listeners only advance {@code contracts.status}, so a failure is almost always
 * transient (a database blip). Three quick attempts recover that; anything that survives
 * them is a genuine poison record and is routed to {@code <topic>.DLT} rather than being
 * silently dropped, which is what the previous catch-and-log did.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    private static final long RETRY_INTERVAL_MS = 1_000L;
    private static final long MAX_RETRIES = 2L; // 3 attempts total

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));

        return new DefaultErrorHandler(deadLetter, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
    }

    @Bean
    NewTopic contractIndexedDltTopic() {
        return TopicBuilder.name("contract.indexed.DLT").partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic contractAnalyzedDltTopic() {
        return TopicBuilder.name("contract.analyzed.DLT").partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic contractFailedDltTopic() {
        return TopicBuilder.name("contract.failed.DLT").partitions(1).replicas(1).build();
    }
}
