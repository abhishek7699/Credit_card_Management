package com.creditcard.gateway.fallback;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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

    private final MeterRegistry meterRegistry;

    public FallbackController(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        // Register both known fallback counters at startup, at count 0, so
        // Prometheus has a baseline sample before the first real
        // circuit-breaker trip. A counter series that doesn't exist yet
        // jumps straight from "no series" to 1 on its first real increment
        // -- rate()/increase() can never show that (there's no preceding
        // sample to subtract from), so without this warm-up a single
        // circuit-breaker trip is invisible on the error-rate dashboard
        // panel, no matter the rate window or scrape interval.
        counterFor("transaction");
        counterFor("reporting");
    }

    @RequestMapping(value = "/{service}", method = {RequestMethod.GET, RequestMethod.POST,
            RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<Map<String, String>> fallback(@PathVariable String service) {
        counterFor(service).increment();
        String message = switch (service) {
            case "transaction" -> "Transaction service temporarily unavailable. Please retry shortly.";
            case "reporting" -> "Reporting service temporarily unavailable. Please retry shortly.";
            default -> "Service temporarily unavailable. Please retry shortly.";
        };
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(ERROR_KEY, ERROR_VALUE, MESSAGE_KEY, message));
    }

    private Counter counterFor(String service) {
        return Counter.builder("gateway.fallback.requests")
                .description("Circuit-breaker fallback responses served by api-gateway, per downstream service")
                .tag("service", service)
                .register(meterRegistry);
    }
}
