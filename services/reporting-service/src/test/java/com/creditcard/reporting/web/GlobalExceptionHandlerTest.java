package com.creditcard.reporting.web;

import com.creditcard.reporting.exception.InvalidDateRangeException;
import com.creditcard.reporting.exception.ResourceNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private BeanPropertyBindingResult bindingResult;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        bindingResult = new BeanPropertyBindingResult(new Object(), "target");
    }

    @Test
    void resourceNotFoundException_returns404WithErrorMessage() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Settlement batch not found: xyz");
        ResponseEntity<Map<String, String>> response = handler.handleNotFound(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "Settlement batch not found: xyz");
    }

    @Test
    void invalidDateRangeException_returns400WithErrorMessage() {
        InvalidDateRangeException ex = new InvalidDateRangeException("Date range exceeds maximum of 90 days");
        ResponseEntity<Map<String, String>> response = handler.handleInvalidDateRange(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Date range exceeds maximum of 90 days");
    }

    @Test
    void methodArgumentNotValidException_returns400WithFieldErrors() throws NoSuchMethodException {
        bindingResult.addError(new FieldError("target", "size", "must be between 1 and 100"));
        bindingResult.addError(new FieldError("target", "status", "must be one of: AUTHORIZED, SETTLED"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                new org.springframework.core.MethodParameter(Object.class.getMethod("toString"), -1),
                bindingResult);

        ResponseEntity<Map<String, List<Map<String, String>>>> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        List<Map<String, String>> errors = response.getBody().get("errors");
        assertThat(errors).hasSize(2);
        assertThat(errors).anySatisfy(e -> {
            assertThat(e.get("field")).isEqualTo("size");
            assertThat(e.get("message")).isEqualTo("must be between 1 and 100");
        });
        assertThat(errors).anySatisfy(e -> {
            assertThat(e.get("field")).isEqualTo("status");
            assertThat(e.get("message")).isEqualTo("must be one of: AUTHORIZED, SETTLED");
        });
    }

    @Test
    void methodArgumentNotValidException_nullDefaultMessage_fallsBackToInvalid() throws NoSuchMethodException {
        bindingResult.addError(new FieldError("target", "amount", null, false, null, null, null));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                new org.springframework.core.MethodParameter(Object.class.getMethod("toString"), -1),
                bindingResult);

        ResponseEntity<Map<String, List<Map<String, String>>>> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        List<Map<String, String>> errors = response.getBody().get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("message")).isEqualTo("invalid");
    }

    @Test
    void constraintViolationException_returns400WithErrorsList() {
        ConstraintViolationException ex = new ConstraintViolationException("validation failed", Set.of());
        ResponseEntity<Map<String, List<Map<String, String>>>> response = handler.handleConstraintViolation(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsKey("errors");
        assertThat(response.getBody().get("errors")).isEmpty();
    }
}
