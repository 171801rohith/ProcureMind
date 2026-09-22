package com.procuremind.auth.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the two things that legitimately go wrong while creating a user into responses a
 * UI can act on, instead of letting them surface as a generic 500.
 *
 * <p>Scoped to {@link UserAdminController} so it cannot change how any other endpoint,
 * including the OAuth2 endpoints, reports errors. Messages are safe to display: they never
 * include a password, a hash, or any other credential material.
 */
@RestControllerAdvice(assignableTypes = UserAdminController.class)
public class UserAdminExceptionHandler {

    /** A duplicate username is a conflict, and an unknown role is a bad request. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleRejectedInput(IllegalArgumentException ex) {
        String message = ex.getMessage() == null ? "Invalid request" : ex.getMessage();
        HttpStatus status = message.startsWith("Username already exists")
                ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(body(status, message));
    }

    /** Bean-validation failures, reported per field so the form can highlight them. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> payload = body(HttpStatus.BAD_REQUEST, "Validation failed");
        payload.put("fieldErrors", ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        error -> error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage(),
                        (first, second) -> first)));
        return ResponseEntity.badRequest().body(payload);
    }

    private static Map<String, Object> body(HttpStatus status, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status.value());
        payload.put("error", status.getReasonPhrase());
        payload.put("message", message);
        return payload;
    }
}
