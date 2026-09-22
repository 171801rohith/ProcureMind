package com.procuremind.contract_service.service;

import com.procuremind.contract_service.exception.UnsupportedFileTypeException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * Sniffs the real content of an uploaded file before it ever reaches MinIO or the database.
 *
 * <p>{@link org.springframework.web.multipart.MultipartFile#getContentType()} is a header the
 * client sends and controls, so it is trivial to spoof — it says nothing about the bytes that
 * follow. Apache Tika instead reads the file's magic bytes (falling back to its extension only
 * when the content is ambiguous) to determine what the file actually is, the same way
 * ai-service's {@code PdfParsingService} already does for content extraction.
 */
@Slf4j
@Service
public class FileValidationService {

    private static final String ACCEPTED_MEDIA_TYPE = "application/pdf";

    private final Tika tika = new Tika();

    public void validatePdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new UnsupportedFileTypeException("No file was uploaded.");
        }

        String detectedType;
        try (InputStream stream = file.getInputStream()) {
            detectedType = tika.detect(stream, file.getOriginalFilename());
        } catch (IOException e) {
            log.warn("Failed to read uploaded file to verify its type: {}", e.toString());
            throw new UnsupportedFileTypeException("The uploaded file could not be read to verify its type.");
        }

        if (!ACCEPTED_MEDIA_TYPE.equals(detectedType)) {
            log.warn("Rejected upload of detected type '{}' (declared content-type '{}')",
                    detectedType, file.getContentType());
            throw new UnsupportedFileTypeException(
                    "Unsupported file type '" + detectedType + "'; only PDF documents are accepted.");
        }
    }
}
