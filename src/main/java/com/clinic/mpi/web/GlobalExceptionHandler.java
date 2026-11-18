package com.clinic.mpi.web;

import com.clinic.mpi.service.ApiException;
import com.clinic.mpi.service.BadRequestException;
import com.clinic.mpi.service.ConflictException;
import com.clinic.mpi.service.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> badRequest(BadRequestException e) {
        return build(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(ConflictException e) {
        Map<String, Object> body = base(HttpStatus.CONFLICT, e.getMessage());
        if (e.currentGraphVersion() != null) {
            body.put("currentGraphVersion", e.currentGraphVersion());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst().orElse("请求参数不合法");
        return ResponseEntity.badRequest().body(base(HttpStatus.BAD_REQUEST, message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegalArg(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(base(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, ApiException e) {
        return ResponseEntity.status(status).body(base(status, e.getMessage()));
    }

    private Map<String, Object> base(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return body;
    }
}
