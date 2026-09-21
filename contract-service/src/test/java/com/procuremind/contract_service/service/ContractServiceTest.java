package com.procuremind.contract_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.procuremind.contract_service.entity.Contract;
import com.procuremind.contract_service.repository.ContractRepository;
import com.procuremind.contract_service.service.kafka.ContractEventProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Client-supplied filenames must never reach the MinIO object key or the DB unsanitized —
 * a raw "../../etc/passwd"-style name would otherwise let a caller control the storage path.
 */
@ExtendWith(MockitoExtension.class)
class ContractServiceTest {

    @Mock
    StorageService storageService;

    @Mock
    ContractRepository contractRepository;

    @Mock
    ContractEventProducer contractEventProducer;

    @Test
    void sanitizesPathTraversalFilenameBeforeStorageAndPersistence() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "../../etc/passwd", "application/pdf", "x".getBytes());
        given(storageService.uploadFile(any(), any())).willReturn("obj-1_.._.._etc_passwd");
        given(contractRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service().processNewContract(file, "Acme");

        ArgumentCaptor<String> sanitized = ArgumentCaptor.forClass(String.class);
        Mockito.verify(storageService).uploadFile(any(), sanitized.capture());
        // Dots survive (needed for extensions), but "/" is stripped so the value can no
        // longer act as a path — it is just a flat, literal object-key segment.
        assertThat(sanitized.getValue()).doesNotContain("/").isEqualTo(".._.._etc_passwd");

        ArgumentCaptor<Contract> saved = ArgumentCaptor.forClass(Contract.class);
        Mockito.verify(contractRepository).save(saved.capture());
        assertThat(saved.getValue().getFilename()).isEqualTo(".._.._etc_passwd");
    }

    @Test
    void sanitizesSpecialCharactersInFilename() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "weird name!@#.pdf", "application/pdf", "x".getBytes());
        given(storageService.uploadFile(any(), any())).willReturn("obj-2_weird_name___.pdf");
        given(contractRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service().processNewContract(file, "Acme");

        ArgumentCaptor<String> sanitized = ArgumentCaptor.forClass(String.class);
        Mockito.verify(storageService).uploadFile(any(), sanitized.capture());
        assertThat(sanitized.getValue()).matches("[a-zA-Z0-9._-]+").isEqualTo("weird_name___.pdf");
    }

    @Test
    void nullOriginalFilenameFallsBackToUnnamed() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", null, "application/pdf", "x".getBytes());
        given(storageService.uploadFile(any(), any())).willReturn("obj-3_unnamed");
        given(contractRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service().processNewContract(file, "Acme");

        ArgumentCaptor<String> sanitized = ArgumentCaptor.forClass(String.class);
        Mockito.verify(storageService).uploadFile(any(), sanitized.capture());
        assertThat(sanitized.getValue()).isEqualTo("unnamed");
    }

    private ContractService service() {
        return new ContractService(storageService, contractRepository, contractEventProducer);
    }
}
