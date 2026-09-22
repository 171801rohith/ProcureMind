package com.procuremind.contract_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.procuremind.contract_service.entity.Contract;
import com.procuremind.contract_service.repository.ContractRepository;
import com.procuremind.contract_service.service.kafka.ContractEventProducer;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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

    @Mock
    FileValidationService fileValidationService;

    @Test
    void sanitizesPathTraversalFilenameBeforeStorageAndPersistence() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "../../etc/passwd", "application/pdf", "x".getBytes());
        given(storageService.uploadFile(any(), any())).willReturn("obj-1_.._.._etc_passwd");
        given(contractRepository.save(any())).willAnswer(inv -> assignIdIfMissing(inv.getArgument(0)));

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
        given(contractRepository.save(any())).willAnswer(inv -> assignIdIfMissing(inv.getArgument(0)));

        service().processNewContract(file, "Acme");

        ArgumentCaptor<String> sanitized = ArgumentCaptor.forClass(String.class);
        Mockito.verify(storageService).uploadFile(any(), sanitized.capture());
        assertThat(sanitized.getValue()).matches("[a-zA-Z0-9._-]+").isEqualTo("weird_name___.pdf");
    }

    @Test
    void nullOriginalFilenameFallsBackToUnnamed() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", null, "application/pdf", "x".getBytes());
        given(storageService.uploadFile(any(), any())).willReturn("obj-3_unnamed");
        given(contractRepository.save(any())).willAnswer(inv -> assignIdIfMissing(inv.getArgument(0)));

        service().processNewContract(file, "Acme");

        ArgumentCaptor<String> sanitized = ArgumentCaptor.forClass(String.class);
        Mockito.verify(storageService).uploadFile(any(), sanitized.capture());
        assertThat(sanitized.getValue()).isEqualTo("unnamed");
    }

    /**
     * {@code processNewContract} is {@code @Transactional} on the JPA side only — MinIO has no
     * part in that transaction. If the DB row were written before the object exists in
     * storage, a reader could observe a contract row pointing at an object that isn't there
     * yet (or never arrives). Uploading first, and only then persisting, rules that out.
     */
    @Test
    void uploadsToMinioBeforeSavingTheContractRow() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "contract.pdf", "application/pdf", "x".getBytes());
        given(storageService.uploadFile(any(), any())).willReturn("obj-1_contract.pdf");
        given(contractRepository.save(any())).willAnswer(inv -> assignIdIfMissing(inv.getArgument(0)));

        service().processNewContract(file, "Acme");

        InOrder order = Mockito.inOrder(storageService, contractRepository);
        order.verify(storageService).uploadFile(any(), any());
        order.verify(contractRepository).save(any());
    }

    /**
     * If the MinIO upload itself fails, nothing about this contract should reach the database
     * or the event bus — a file that never made it to storage must not produce a DB row or a
     * downstream Kafka event for other services to chase.
     */
    @Test
    void aFailedMinioUploadNeverTouchesTheDatabaseOrPublishesAnEvent() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "contract.pdf", "application/pdf", "x".getBytes());
        willThrow(new IOException("MinIO unreachable")).given(storageService).uploadFile(any(), any());

        assertThatThrownBy(() -> service().processNewContract(file, "Acme"))
                .isInstanceOf(IOException.class);

        verify(storageService).uploadFile(any(), any());
        verifyNoInteractions(contractRepository);
        verifyNoInteractions(contractEventProducer);
    }

    /**
     * Known, currently-unfixed gap (Architecture Review finding #8): the MinIO upload is not
     * part of the JPA transaction, and {@code StorageService} exposes no compensating delete.
     * If the DB save fails AFTER a successful upload, the uploaded object is orphaned in
     * MinIO with no DB row ever referencing it, and nothing today cleans it up. This test
     * documents/locks down that current (imperfect) behavior rather than silently fixing it,
     * per the review's decision to only verify — not remediate — this gap in this pass.
     */
    @Test
    void aFailedDbSaveOrphansTheAlreadyUploadedMinioObject() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "contract.pdf", "application/pdf", "x".getBytes());
        given(storageService.uploadFile(any(), any())).willReturn("obj-2_contract.pdf");
        willThrow(new RuntimeException("DB unavailable")).given(contractRepository).save(any());

        assertThatThrownBy(() -> service().processNewContract(file, "Acme"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB unavailable");

        // The upload already happened and is irreversible from this method's perspective...
        verify(storageService).uploadFile(any(), any());
        // ...and StorageService has no delete/cleanup method for ContractService to call even
        // if it wanted to compensate, so nothing more than the upload+failed-save happens.
        verifyNoMoreInteractions(storageService);
        verifyNoInteractions(contractEventProducer);
    }

    private ContractService service() {
        return new ContractService(storageService, contractRepository, contractEventProducer, fileValidationService);
    }

    /** Mirrors real JPA behaviour for {@code @GeneratedValue(strategy = GenerationType.UUID)}. */
    private static Contract assignIdIfMissing(Contract contract) {
        if (contract.getId() == null) {
            contract.setId(UUID.randomUUID());
        }
        return contract;
    }
}
