package com.procuremind.contract_service.service.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import java.util.UUID;

import com.procuremind.common.dto.PageIndexedEvent;
import com.procuremind.contract_service.entity.Contract;
import com.procuremind.contract_service.repository.ContractRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The contract lifecycle has to survive Kafka's at-least-once delivery: a redelivered
 * {@code contract.indexed} must not drag an already-ANALYZED contract backwards, and a
 * FAILED contract must not be quietly revived by a late success.
 */
@ExtendWith(MockitoExtension.class)
class ContractEventListenerTest {

    private static final UUID CONTRACT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    ContractRepository contractRepository;

    @ParameterizedTest(name = "{0} -> {1} advances")
    @CsvSource({
            "UPLOADED,INDEXED",
            "UPLOADED,ANALYZED",
            "INDEXED,ANALYZED",
            "UPLOADED,FAILED",
            "INDEXED,FAILED",
            "ANALYZED,FAILED",
            // A failure is usually environmental, so reprocessing must be able to clear it.
            "FAILED,INDEXED",
            "FAILED,ANALYZED"
    })
    void theLifecycleMovesForwardAndIntoFailure(String current, String candidate) {
        assertThat(ContractEventListener.shouldAdvance(current, candidate)).isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1} is ignored")
    @CsvSource({
            "INDEXED,INDEXED",
            "ANALYZED,INDEXED",
            "ANALYZED,ANALYZED",
            "INDEXED,UPLOADED",
            "FAILED,FAILED"
    })
    void theLifecycleNeverMovesBackwardsAndFailureIsTerminal(String current, String candidate) {
        assertThat(ContractEventListener.shouldAdvance(current, candidate)).isFalse();
    }

    @Test
    void aRedeliveredIndexedEventLeavesAnAnalyzedContractAlone() {
        Contract contract = contractAt("ANALYZED");
        given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract));

        listener().handleContractIndexed(new PageIndexedEvent(CONTRACT_ID, "INDEXED"));

        assertThat(contract.getStatus()).isEqualTo("ANALYZED");
        verify(contractRepository, never()).save(contract);
    }

    @Test
    void aTerminalFailureIsRecorded() {
        Contract contract = contractAt("INDEXED");
        given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract));

        listener().handleContractFailed(new PageIndexedEvent(CONTRACT_ID, "FAILED"));

        assertThat(contract.getStatus()).isEqualTo("FAILED");
        verify(contractRepository).save(contract);
    }

    @Test
    void anEventForAnUnknownContractIsIgnoredRatherThanThrown() {
        given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.empty());

        // Throwing here would send the record around the retry/DLT path forever for a
        // contract that simply does not exist.
        listener().handleContractAnalyzed(new PageIndexedEvent(CONTRACT_ID, "ANALYSIS_COMPLETED"));

        verify(contractRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private ContractEventListener listener() {
        return new ContractEventListener(contractRepository);
    }

    private static Contract contractAt(String status) {
        Contract contract = new Contract();
        contract.setId(CONTRACT_ID);
        contract.setStatus(status);
        return contract;
    }
}
