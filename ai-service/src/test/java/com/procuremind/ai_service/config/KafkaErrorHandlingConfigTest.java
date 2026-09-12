package com.procuremind.ai_service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.procuremind.ai_service.service.kafka.ContractEventProducer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.support.SendResult;

/**
 * A poisoned record has to end up somewhere an operator can see it, and the contract it
 * belongs to has to stop looking like one that is merely slow. Both halves of that are the
 * recoverer's job, so they are asserted together.
 */
@ExtendWith(MockitoExtension.class)
class KafkaErrorHandlingConfigTest {

    private static final UUID CONTRACT_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    ContractEventProducer eventProducer;

    ConsumerRecordRecoverer recoverer;

    @BeforeEach
    void setUp() {
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        future.complete(null);
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(future);

        recoverer = new KafkaErrorHandlingConfig().deadLetterRecoverer(kafkaTemplate, eventProducer);
    }

    @Test
    void anExhaustedRecordIsDeadLetteredAndItsContractMarkedFailed() {
        recoverer.accept(record("contract.uploaded", CONTRACT_ID.toString()), new IllegalStateException("boom"));

        verify(eventProducer).publishProcessingFailed(CONTRACT_ID);
        // The DLT name is derived from the source topic, so a new topic needs no new config.
        verify(kafkaTemplate).send(targeting("contract.uploaded.DLT"));
    }

    @Test
    void aRecordWithoutAUsableKeyIsStillDeadLetteredRatherThanLost() {
        assertThatCode(() -> recoverer.accept(record("contract.indexed", "not-a-uuid"),
                new IllegalStateException("boom")))
                .doesNotThrowAnyException();

        verifyNoInteractions(eventProducer);
        verify(kafkaTemplate).send(targeting("contract.indexed.DLT"));
    }

    @Test
    void theBrokerChoosesTheDeadLetterPartition() {
        recoverer.accept(record("contract.uploaded", CONTRACT_ID.toString()), new IllegalStateException("boom"));

        // A pinned partition number would break the moment the source topic is repartitioned.
        ArgumentCaptor<ProducerRecord<String, Object>> published = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(published.capture());
        assertThat(published.getValue().partition()).isNull();
    }

    private static ConsumerRecord<String, Object> record(String topic, String key) {
        return new ConsumerRecord<>(topic, 0, 0L, key, "payload");
    }

    private static ProducerRecord<String, Object> targeting(String topic) {
        return argThat(published -> topic.equals(published.topic()));
    }
}
