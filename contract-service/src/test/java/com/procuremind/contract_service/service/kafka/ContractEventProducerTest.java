package com.procuremind.contract_service.service.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.procuremind.common.dto.ContractUploadedEvent;

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

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("contract.uploaded"), eq(CONTRACT_ID.toString()), payload.capture());
        assertThat(payload.getValue())
                .isEqualTo(new ContractUploadedEvent(CONTRACT_ID, "msa.pdf", "obj-1_msa.pdf"));
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

        verify(kafkaTemplate).send(eq("contract.uploaded"), eq(CONTRACT_ID.toString()), any());
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
        given(kafkaTemplate.send(any(String.class), any(String.class), any())).willReturn(future);
    }
}
