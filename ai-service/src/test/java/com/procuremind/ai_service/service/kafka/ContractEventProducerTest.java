package com.procuremind.ai_service.service.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.procuremind.common.dto.PageIndexedEvent;
import com.procuremind.common.tracing.CorrelationIds;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Every published event must carry the contract id as both the Kafka key (for partitioning
 * and ordering) and the {@link CorrelationIds#HEADER} header (so a consumer can restore it
 * into MDC on receipt without deserializing the payload first).
 */
@ExtendWith(MockitoExtension.class)
class ContractEventProducerTest {

    private static final UUID CONTRACT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void publishPageIndexedCarriesTheContractIdAsKeyAndHeader() {
        givenSendSucceeds();

        producer().publishPageIndexed(CONTRACT_ID);

        ProducerRecord<String, Object> sent = capturedRecord();
        assertThat(sent.topic()).isEqualTo("contract.indexed");
        assertThat(sent.key()).isEqualTo(CONTRACT_ID.toString());
        assertThat(sent.value()).isEqualTo(new PageIndexedEvent(CONTRACT_ID, "INDEXED"));
        assertCorrelationHeader(sent);
    }

    @Test
    void publishAnalysisCompletedCarriesTheContractIdAsKeyAndHeader() {
        givenSendSucceeds();

        producer().publishAnalysisCompleted(CONTRACT_ID);

        ProducerRecord<String, Object> sent = capturedRecord();
        assertThat(sent.topic()).isEqualTo("contract.analyzed");
        assertCorrelationHeader(sent);
    }

    @Test
    void publishProcessingFailedCarriesTheContractIdAsKeyAndHeader() {
        givenSendSucceeds();

        producer().publishProcessingFailed(CONTRACT_ID);

        ProducerRecord<String, Object> sent = capturedRecord();
        assertThat(sent.topic()).isEqualTo("contract.failed");
        assertCorrelationHeader(sent);
    }

    private void assertCorrelationHeader(ProducerRecord<String, Object> record) {
        assertThat(record.headers().lastHeader(CorrelationIds.HEADER).value())
                .isEqualTo(CONTRACT_ID.toString().getBytes(StandardCharsets.UTF_8));
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
