package com.creditcard.booking.kafka;

import com.creditcard.booking.kafka.event.TransactionStatusEvent;
import com.creditcard.booking.service.BookingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TransactionStatusConsumerTest {

    @Mock
    private BookingService bookingService;

    @Mock
    private Acknowledgment acknowledgment;

    private TransactionStatusConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TransactionStatusConsumer(bookingService);
    }

    @Test
    void nullPayload_discardedAndAcknowledged_bookingServiceNeverCalled() {
        ConsumerRecord<String, TransactionStatusEvent> record =
                new ConsumerRecord<>("transaction.status.updated", 0, 0, "key", null);

        consumer.onMessage(record, acknowledgment);

        verifyNoInteractions(bookingService);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void validPayload_delegatesToBookingServiceThenAcknowledges() {
        TransactionStatusEvent event = new TransactionStatusEvent();
        ConsumerRecord<String, TransactionStatusEvent> record =
                new ConsumerRecord<>("transaction.status.updated", 0, 0, "key", event);

        consumer.onMessage(record, acknowledgment);

        verify(bookingService).handlePaymentOutcome(event);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void bookingServiceThrows_propagatesAndNeverAcknowledges() {
        TransactionStatusEvent event = new TransactionStatusEvent();
        ConsumerRecord<String, TransactionStatusEvent> record =
                new ConsumerRecord<>("transaction.status.updated", 0, 0, "key", event);
        doThrow(new BookingNotFoundForTransactionException("txn-1"))
                .when(bookingService).handlePaymentOutcome(any());

        assertThatThrownBy(() -> consumer.onMessage(record, acknowledgment))
                .isInstanceOf(BookingNotFoundForTransactionException.class);

        verify(acknowledgment, never()).acknowledge();
    }
}
