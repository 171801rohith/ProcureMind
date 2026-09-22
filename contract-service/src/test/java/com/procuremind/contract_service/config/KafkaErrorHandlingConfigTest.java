package com.procuremind.contract_service.config;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import com.procuremind.contract_service.config.KafkaErrorHandlingConfig.MarkFailedThenDeadLetterRecoverer;
import com.procuremind.contract_service.service.kafka.ContractStatusUpdater;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

/**
 * A message that exhausts its retries must leave the contract it names visibly FAILED, not
 * silently dead-lettered with no signal — the gap the architecture review flagged as #4.
 */
@ExtendWith(MockitoExtension.class)
class KafkaErrorHandlingConfigTest {

    private static final UUID CONTRACT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock
    ContractStatusUpdater statusUpdater;

    @Mock
    DeadLetterPublishingRecoverer deadLetter;

    @Test
    void marksTheContractFailedThenDeadLettersTheRecord() {
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("contract.indexed", 0, 0L,
                CONTRACT_ID.toString(), "poison-payload");
        RuntimeException cause = new RuntimeException("boom");

        recoverer().accept(record, cause);

        verify(statusUpdater).markFailedByKey(CONTRACT_ID.toString());
        verify(deadLetter).accept(record, cause);
    }

    @Test
    void aRecordWithNoKeyIsStillDeadLetteredWithoutThrowing() {
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("contract.indexed", 0, 0L,
                null, "poison-payload");
        RuntimeException cause = new RuntimeException("boom");

        recoverer().accept(record, cause);

        verify(statusUpdater).markFailedByKey(null);
        verify(deadLetter, times(1)).accept(record, cause);
    }

    private MarkFailedThenDeadLetterRecoverer recoverer() {
        return new MarkFailedThenDeadLetterRecoverer(statusUpdater, deadLetter);
    }
}
