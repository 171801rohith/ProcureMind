package com.procuremind.contract_service.exception;

/** The uploaded file's real (magic-byte/MIME sniffed) content type is not an accepted one. */
public class UnsupportedFileTypeException extends RuntimeException {
    public UnsupportedFileTypeException(String message) {
        super(message);
    }
}
