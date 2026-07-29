package com.creditcard.gateway.fallback;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/fallback")
public class FallbackController {

    private static final String ERROR_KEY = "error";
    private static final String MESSAGE_KEY = "message";
    private static final String ERROR_VALUE = "Service Unavailable";

    @RequestMapping(value = "/{service}", method = {RequestMethod.GET, RequestMethod.POST,
            RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<Map<String, String>> fallback(@PathVariable String service) {
        String message = switch (service) {
            case "transaction" -> "Transaction service temporarily unavailable. Please retry shortly.";
            case "reporting" -> "Reporting service temporarily unavailable. Please retry shortly.";
            default -> "Service temporarily unavailable. Please retry shortly.";
        };
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(ERROR_KEY, ERROR_VALUE, MESSAGE_KEY, message));
    }
}
