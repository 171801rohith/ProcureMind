package com.procuremind.contract_service.service.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.procuremind.common.dto.ContractUploadedEvent;
import com.procuremind.common.tracing.CorrelationIds;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The event must describe a contract that actually exists. Publishing inside the upload
 * transaction meant a later rollback still emitted it, so ai-service would fetch an object
 * for a row that was never committed.
 */
@ExtendWith(MockitoExtension.class)
class ContractEventProducerTest {

    private static final UUID CONTRACT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesImmediatelyWhenThereIsNoTransactionToWaitFor() {
        givenSendSucceeds();

        producer().publishContractUploadEvent(CONTRACT_ID, "msa.pdf", "obj-1_msa.pdf");

        ProducerRecord<String, Object> sent = capturedRecord();
        assertThat(sent.topic()).isEqualTo("contract.uploaded");
        assertThat(sent.key()).isEqualTo(CONTRACT_ID.toString());
        assertThat(sent.value()).isEqualTo(new ContractUploadedEvent(CONTRACT_ID, "msa.pdf", "obj-1_msa.pdf"));
        assertThat(sent.headers().lastHeader(CorrelationIds.HEADER).value())
                .isEqualTo(CONTRACT_ID.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void defersTheSendUntilTheSurroundingTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();

        producer().publishContractUploadEvent(CONTRACT_ID, "msa.pdf", "obj-1_msa.pdf");

        // Still inside the transaction: nothing has been published yet.
        verifyNoInteractions(kafkaTemplate);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

        givenSendSucceeds();
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());

        assertThat(capturedRecord().topic()).isEqualTo("contract.uploaded");
    }

    @Test
    void aRolledBackTransactionPublishesNothing() {
        TransactionSynchronizationManager.initSynchronization();

        producer().publishContractUploadEvent(CONTRACT_ID, "msa.pdf", "obj-1_msa.pdf");
        // afterCompletion without afterCommit is what a rollback looks like.
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(kafkaTemplate);
    }

    private ContractEventProducer producer() {
        return new ContractEventProducer(kafkaTemplate);
    }

    private void givenSendSucceeds() {
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        future.complete(null);
        ProducerRecord<String, Object> anyRecord = any();
        given(kafkaTemplate.send(anyRecord)).willReturn(future);
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, Object> capturedRecord() {
        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }
}
