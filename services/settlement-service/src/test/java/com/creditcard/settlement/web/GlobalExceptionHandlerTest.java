package com.creditcard.settlement.web;

import com.creditcard.settlement.exception.BatchAlreadyRunningException;
import com.creditcard.settlement.exception.MerchantSettlementNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleMerchantNotFound_returns404() {
        MerchantSettlementNotFoundException ex =
                new MerchantSettlementNotFoundException("M1", "batch-001");

        ResponseEntity<Map<String, String>> response = handler.handleMerchantNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsKey("error");
        assertThat(response.getBody().get("error")).contains("M1").contains("batch-001");
    }

    @Test
    void handleNotFound_returns404() {
        NoSuchElementException ex = new NoSuchElementException("Batch not found: xyz");

        ResponseEntity<Map<String, String>> response = handler.handleNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("error")).contains("xyz");
    }

    @Test
    void handleBatchAlreadyRunning_returns409() {
        BatchAlreadyRunningException ex = new BatchAlreadyRunningException("running-123");

        ResponseEntity<Map<String, String>> response = handler.handleBatchAlreadyRunning(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("error")).isEqualTo("Settlement batch is already running");
        assertThat(response.getBody().get("runningBatchId")).isEqualTo("running-123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleValidation_returns400WithFieldErrors() throws NoSuchMethodException {
        Object target = new Object();
        BindingResult bindingResult = new BeanPropertyBindingResult(target, "target");
        bindingResult.addError(new FieldError("target", "amount", "must be positive"));
        bindingResult.addError(new FieldError("target", "merchantId", "must not be blank"));

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                new org.springframework.core.MethodParameter(
                        Object.class.getMethod("toString"), -1),
                bindingResult);

        ResponseEntity<Map<String, List<Map<String, String>>>> response =
                handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        List<Map<String, String>> errors = response.getBody().get("errors");
        assertThat(errors).hasSize(2);
        assertThat(errors).anyMatch(e -> "amount".equals(e.get("field"))
                && "must be positive".equals(e.get("message")));
        assertThat(errors).anyMatch(e -> "merchantId".equals(e.get("field"))
                && "must not be blank".equals(e.get("message")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleValidation_nullDefaultMessage_fallsBackToInvalid() throws NoSuchMethodException {
        Object target = new Object();
        BindingResult bindingResult = new BeanPropertyBindingResult(target, "target");
        // FieldError with null defaultMessage
        bindingResult.addError(new FieldError("target", "amount", null, false, null, null, null));

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                new org.springframework.core.MethodParameter(
                        Object.class.getMethod("toString"), -1),
                bindingResult);

        ResponseEntity<Map<String, List<Map<String, String>>>> response =
                handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        List<Map<String, String>> errors = response.getBody().get("errors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("message")).isEqualTo("invalid");
    }
}
