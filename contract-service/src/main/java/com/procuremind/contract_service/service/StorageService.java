package com.procuremind.contract_service.service;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {
    private final MinioClient minioClient;
    private static final String BUCKET_NAME = "procuremind-contracts";

    public String uploadFile(MultipartFile file) throws Exception {
        boolean found = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(BUCKET_NAME).build()
        );
        if (!found) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET_NAME).build());
            log.info("Created new MINIO bucket: {}", BUCKET_NAME);
        }

        String objName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(BUCKET_NAME)
                        .object(objName)
                        .stream(file.getInputStream(), file.getSize(), -1)
                        .contentType(file.getContentType())
                        .build()
        );
        log.info("File uploaded to MinIO successfully as {}", objName);
        return objName;
    }
}
