package com.procuremind.contract_service.service.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.procuremind.common.dto.PageIndexedEvent;
import com.procuremind.common.tracing.CorrelationIds;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

/**
 * The listener's own job is now just delegation and correlation-id bookkeeping — lifecycle
 * rules live in {@link ContractStatusUpdaterTest}.
 */
@ExtendWith(MockitoExtension.class)
class ContractEventListenerTest {

    private static final UUID CONTRACT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    ContractStatusUpdater statusUpdater;

    @Test
    void handleContractIndexedAdvancesToIndexed() {
        listener().handleContractIndexed(new PageIndexedEvent(CONTRACT_ID, "INDEXED"), null);

        verify(statusUpdater).advance(CONTRACT_ID, "INDEXED");
    }

    @Test
    void handleContractAnalyzedAdvancesToAnalyzed() {
        listener().handleContractAnalyzed(new PageIndexedEvent(CONTRACT_ID, "ANALYSIS_COMPLETED"), null);

        verify(statusUpdater).advance(CONTRACT_ID, "ANALYZED");
    }

    @Test
    void handleContractFailedAdvancesToFailed() {
        listener().handleContractFailed(new PageIndexedEvent(CONTRACT_ID, "FAILED"), null);

        verify(statusUpdater).advance(CONTRACT_ID, ContractStatusUpdater.FAILED);
    }

    @Test
    void theCorrelationHeaderIsRestoredIntoMdcForTheDurationOfProcessingThenCleared() {
        byte[] header = CONTRACT_ID.toString().getBytes(StandardCharsets.UTF_8);
        doAnswer(invocation -> {
            assertThat(MDC.get(CorrelationIds.MDC_KEY)).isEqualTo(CONTRACT_ID.toString());
            return null;
        }).when(statusUpdater).advance(CONTRACT_ID, "INDEXED");

        listener().handleContractIndexed(new PageIndexedEvent(CONTRACT_ID, "INDEXED"), header);

        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @Test
    void aMissingCorrelationHeaderFallsBackToTheEventsOwnContractId() {
        doAnswer(invocation -> {
            assertThat(MDC.get(CorrelationIds.MDC_KEY)).isEqualTo(CONTRACT_ID.toString());
            return null;
        }).when(statusUpdater).advance(CONTRACT_ID, "INDEXED");

        listener().handleContractIndexed(new PageIndexedEvent(CONTRACT_ID, "INDEXED"), null);

        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    private ContractEventListener listener() {
        return new ContractEventListener(statusUpdater);
    }
}
