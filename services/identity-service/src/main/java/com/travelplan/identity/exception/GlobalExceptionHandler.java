package com.travelplan.identity.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Centralised exception-to-HTTP mapping.
 *
 * All error bodies follow the same structure:
 * <pre>
 * { "error": "...", "status": N }
 * </pre>
 *
 * No business logic lives here — only HTTP status assignment and body shaping.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUserNotFound(UserNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(EmailAlreadyActiveException.class)
    public ResponseEntity<Map<String, Object>> handleEmailAlreadyActive(EmailAlreadyActiveException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCredentials(InvalidCredentialsException ex) {
        return buildResponse(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidToken(InvalidTokenException ex) {
        return buildResponse(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(InsufficientRoleException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientRole(InsufficientRoleException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(ReportNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleReportNotFound(ReportNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(SelfReportException.class)
    public ResponseEntity<Map<String, Object>> handleSelfReport(SelfReportException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(InvalidReportStatusValueException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidReportStatusValue(InvalidReportStatusValueException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(ReportAlreadyTerminalException.class)
    public ResponseEntity<Map<String, Object>> handleReportAlreadyTerminal(ReportAlreadyTerminalException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return buildResponse(HttpStatus.BAD_REQUEST, "Validation failed — " + detail);
    }

    private static ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("status", status.value());
        return ResponseEntity.status(status).body(body);
    }
}