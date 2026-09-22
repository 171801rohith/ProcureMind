package com.procuremind.contract_service.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.procuremind.contract_service.exception.UnsupportedFileTypeException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * {@link org.springframework.web.multipart.MultipartFile#getContentType()} is a header the
 * client controls, so it proves nothing about the bytes that follow it. These tests exercise
 * real Apache Tika magic-byte detection (no mocking) to prove the validator looks at content,
 * not the declared content-type or the filename extension.
 */
class FileValidationServiceTest {

    private final FileValidationService validator = new FileValidationService();

    @Test
    void aRealPdfIsAccepted() {
        byte[] pdfBytes = "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        MockMultipartFile file = new MockMultipartFile("file", "contract.pdf", "application/pdf", pdfBytes);

        assertThatCode(() -> validator.validatePdf(file)).doesNotThrowAnyException();
    }

    @Test
    void aPngRenamedWithAPdfExtensionIsRejectedByItsMagicBytes() {
        byte[] pngMagic = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0 };
        MockMultipartFile file = new MockMultipartFile("file", "totally-a-contract.pdf", "application/pdf", pngMagic);

        assertThatThrownBy(() -> validator.validatePdf(file))
                .isInstanceOf(UnsupportedFileTypeException.class)
                .hasMessageContaining("Unsupported file type");
    }

    @Test
    void aSpoofedContentTypeHeaderDoesNotBypassContentSniffing() {
        // Content-Type says PDF; the bytes say PNG. Only the bytes may be trusted.
        byte[] pngMagic = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0 };
        MockMultipartFile file = new MockMultipartFile("file", "image.dat", "application/pdf", pngMagic);

        assertThatThrownBy(() -> validator.validatePdf(file))
                .isInstanceOf(UnsupportedFileTypeException.class);
    }

    @Test
    void anEmptyFileIsRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> validator.validatePdf(file))
                .isInstanceOf(UnsupportedFileTypeException.class);
    }
}
