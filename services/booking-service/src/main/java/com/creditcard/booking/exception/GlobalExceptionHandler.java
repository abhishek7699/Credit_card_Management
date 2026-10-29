package com.creditcard.booking.exception;

import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> errors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.put(fe.getField(), fe.getDefaultMessage());
        }
        return errors;
    }

    @ExceptionHandler(InvalidDateRangeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleInvalidDateRange(InvalidDateRangeException ex) {
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(RoomNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleRoomNotFound(RoomNotFoundException ex) {
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(BookingNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleBookingNotFound(BookingNotFoundException ex) {
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(RoomUnavailableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleRoomUnavailable(RoomUnavailableException ex) {
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(InvalidBookingStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleInvalidBookingState(InvalidBookingStateException ex) {
        return Map.of("error", ex.getMessage());
    }

    @ExceptionHandler(InvalidRoomStatusTransitionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleInvalidRoomStatusTransition(InvalidRoomStatusTransitionException ex) {
        return Map.of("error", ex.getMessage());
    }
}
