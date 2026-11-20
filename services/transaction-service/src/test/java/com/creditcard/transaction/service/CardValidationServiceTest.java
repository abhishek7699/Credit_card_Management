package com.creditcard.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.creditcard.mastercard.developers.AccountValidationClient;
import com.creditcard.mastercard.developers.AccountValidationUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

class CardValidationServiceTest {

    private static final String PAN = "5102589999999913";
    private static final String CVC = "6173";

    private final AccountValidationClient client = mock(AccountValidationClient.class);
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger("com.creditcard");
        logger.setLevel(Level.TRACE);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void stopCapture() {
        logger.detachAppender(logs);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AccountValidationClient> provider(AccountValidationClient client) {
        ObjectProvider<AccountValidationClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }

    private CardValidationService on(String prefix) {
        return new CardValidationService(provider(client), prefix);
    }

    private CardValidationService off() {
        return new CardValidationService(provider(null), "");
    }

    @Test
    void switchOff_wellFormedCardAlwaysPassesWithoutCallingMastercard() {
        CardValidationService.Outcome outcome = off().validate(PAN, "10/50", CVC, "Jane Doe");

        assertThat(outcome.status()).isEqualTo(CardValidationService.Status.MATCHED);
        assertThat(outcome.last4()).isEqualTo("9913");
        assertThat(outcome.referenceId()).startsWith("OFF_").hasSizeLessThanOrEqualTo(40);
        verify(client, never()).validate(any(), any());
    }

    @Test
    void switchOn_approvedByMastercard_isMatchedWithOurReferenceAndLast4() {
        when(client.validate(any(), any())).thenAnswer(inv -> {
            AccountValidationClient.Request r = inv.getArgument(0);
            return new AccountValidationClient.Validation(r.referenceId(), true, "00");
        });

        CardValidationService.Outcome outcome = on("AVM_").validate(PAN, "10/50", CVC, "Jane Doe");

        assertThat(outcome.status()).isEqualTo(CardValidationService.Status.MATCHED);
        assertThat(outcome.referenceId()).startsWith("AVM_").hasSize(20);
        assertThat(outcome.last4()).isEqualTo("9913");
    }

    @Test
    void switchOn_notApprovedByMastercard_isNotMatched() {
        when(client.validate(any(), any())).thenReturn(new AccountValidationClient.Validation("r", false, "05"));

        assertThat(on("").validate(PAN, "10/50", CVC, "Jane Doe").status())
                .isEqualTo(CardValidationService.Status.NOT_MATCHED);
    }

    @Test
    void switchOn_mastercardUnavailable_propagatesSoCallersFailClosed() {
        when(client.validate(any(), any())).thenThrow(new AccountValidationUnavailableException("HTTP 503"));

        assertThatThrownBy(() -> on("").validate(PAN, "10/50", CVC, "Jane Doe"))
                .isInstanceOf(AccountValidationUnavailableException.class);
    }

    @Test
    void malformedCardData_isRejectedInBothModes_withoutEchoingTheValue() {
        for (CardValidationService service : new CardValidationService[] {on(""), off()}) {
            assertThatThrownBy(() -> service.validate("4111-1111", "10/50", CVC, "Jane"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("card_number must be 13-19 digits");
            assertThatThrownBy(() -> service.validate(null, "10/50", CVC, "Jane"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.validate(PAN, "10/50", "12", "Jane"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("cvv must be 3 or 4 digits");
            assertThatThrownBy(() -> service.validate(PAN, "10/50", null, "Jane"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.validate(PAN, "2050-10", CVC, "Jane"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("expiry must be MM/YY");
        }
    }

    @Test
    void neverLogsTheCardNumberOrCvc_onAnyPath() {
        when(client.validate(any(), any()))
                .thenReturn(new AccountValidationClient.Validation("r", true, "00"))
                .thenReturn(new AccountValidationClient.Validation("r", false, "05"))
                .thenThrow(new AccountValidationUnavailableException("HTTP 500"));
        CardValidationService service = on("AVM_");
        service.validate(PAN, "10/50", CVC, "Jane Doe");
        service.validate(PAN, "10/50", CVC, "Jane Doe");
        assertThatThrownBy(() -> service.validate(PAN, "10/50", CVC, "Jane Doe"));
        assertThatThrownBy(() -> service.validate("123", "10/50", CVC, "Jane Doe"));
        off().validate(PAN, "10/50", CVC, "Jane Doe");

        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(PAN).doesNotContainPattern("\\b" + CVC + "\\b");
        }
    }
}
