package com.procuremind.contract_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

/**
 * {@link StorageService#uploadFile} is the only code path that writes a PDF's bytes to MinIO,
 * so its bucket-provisioning and object-naming behaviour is locked down here with a mocked
 * {@link MinioClient} rather than a real MinIO instance.
 */
@ExtendWith(MockitoExtension.class)
class StorageServiceTest {

    @Mock
    MinioClient minioClient;

    @Test
    void doesNotCreateTheBucketWhenItAlreadyExists() throws Exception {
        StorageService service = new StorageService(minioClient);
        given(minioClient.bucketExists(any(BucketExistsArgs.class))).willReturn(true);
        MockMultipartFile file = new MockMultipartFile("file", "contract.pdf", "application/pdf", "hello".getBytes());

        service.uploadFile(file, "contract.pdf");

        verify(minioClient, never()).makeBucket(any(MakeBucketArgs.class));
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    void createsTheBucketBeforeUploadingWhenItDoesNotExist() throws Exception {
        StorageService service = new StorageService(minioClient);
        given(minioClient.bucketExists(any(BucketExistsArgs.class))).willReturn(false);
        MockMultipartFile file = new MockMultipartFile("file", "contract.pdf", "application/pdf", "hello".getBytes());

        service.uploadFile(file, "contract.pdf");

        InOrder order = inOrder(minioClient);
        order.verify(minioClient).makeBucket(any(MakeBucketArgs.class));
        order.verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    void returnedObjectNameIsUuidPrefixedSanitizedFilename() throws Exception {
        StorageService service = new StorageService(minioClient);
        given(minioClient.bucketExists(any(BucketExistsArgs.class))).willReturn(true);
        MockMultipartFile file = new MockMultipartFile("file", "contract.pdf", "application/pdf", "hello".getBytes());

        String objectName = service.uploadFile(file, "sanitized_name.pdf");

        assertThat(objectName).endsWith("_sanitized_name.pdf");
        String uuidPart = objectName.substring(0, objectName.length() - "_sanitized_name.pdf".length());
        assertThat(java.util.UUID.fromString(uuidPart)).isNotNull();
    }

    @Test
    void putObjectReceivesTheBucketObjectNameAndFileContentsPassedThrough() throws Exception {
        StorageService service = new StorageService(minioClient);
        given(minioClient.bucketExists(any(BucketExistsArgs.class))).willReturn(true);
        byte[] content = "%PDF-1.4 fake contract bytes".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "contract.pdf", "application/pdf", content);

        String objectName = service.uploadFile(file, "contract.pdf");

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(captor.capture());
        PutObjectArgs args = captor.getValue();
        assertThat(args.bucket()).isEqualTo("procuremind-contracts");
        assertThat(args.object()).isEqualTo(objectName);
        assertThat(args.contentType()).isEqualTo("application/pdf");
        assertThat(args.objectSize()).isEqualTo(content.length);
    }
}
