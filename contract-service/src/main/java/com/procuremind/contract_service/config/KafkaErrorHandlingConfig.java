package com.procuremind.contract_service.config;

import com.procuremind.contract_service.service.kafka.ContractStatusUpdater;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Retry and dead-letter policy for the contract lifecycle listeners.
 *
 * <p>These listeners only advance {@code contracts.status}, so a failure is almost always
 * transient (a database blip). Three quick attempts recover that; anything that survives
 * them is a genuine poison record. Before it is routed to {@code <topic>.DLT}, the contract
 * it names is marked FAILED — previously the recoverer only dead-lettered the record and left
 * the contract silently stuck at whatever status it had before the poison message, with no
 * signal that it would never advance further.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    private static final long RETRY_INTERVAL_MS = 1_000L;
    private static final long MAX_RETRIES = 2L; // 3 attempts total

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate,
            ContractStatusUpdater statusUpdater) {
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));

        return new DefaultErrorHandler(new MarkFailedThenDeadLetterRecoverer(statusUpdater, deadLetter),
                new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
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

    /**
     * Marks the record's contract FAILED, then dead-letters the record. Package-visible and
     * constructed with plain arguments (rather than inlined as a lambda in the {@code @Bean}
     * method) so the recovery behaviour can be unit tested without driving
     * {@link DefaultErrorHandler}'s internal retry state machine.
     */
    @Slf4j
    @RequiredArgsConstructor
    static class MarkFailedThenDeadLetterRecoverer implements ConsumerRecordRecoverer {

        private final ContractStatusUpdater statusUpdater;
        private final DeadLetterPublishingRecoverer deadLetter;

        @Override
        public void accept(ConsumerRecord<?, ?> record, Exception exception) {
            log.error("Exhausted retries for {} [{}], marking contract FAILED before dead-lettering",
                    record.topic(), record.key(), exception);
            statusUpdater.markFailedByKey((String) record.key());
            deadLetter.accept(record, exception);
        }
    }
}
