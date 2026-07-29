package com.creditcard.notification.web.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CardholderContactRequestTest {

    @Test
    void atLeastOneContactPresent_bothNull_returnsFalse() {
        CardholderContactRequest r = new CardholderContactRequest();
        assertThat(r.isAtLeastOneContactPresent()).isFalse();
    }

    @Test
    void atLeastOneContactPresent_emailOnly_returnsTrue() {
        CardholderContactRequest r = new CardholderContactRequest();
        r.setEmail("user@example.com");
        assertThat(r.isAtLeastOneContactPresent()).isTrue();
    }

    @Test
    void atLeastOneContactPresent_phoneOnly_returnsTrue() {
        CardholderContactRequest r = new CardholderContactRequest();
        r.setPhoneNumber("+14155551234");
        assertThat(r.isAtLeastOneContactPresent()).isTrue();
    }

    @Test
    void maskedCardNumberSafe_nullMasked_returnsTrue() {
        CardholderContactRequest r = new CardholderContactRequest();
        assertThat(r.isMaskedCardNumberSafe()).isTrue();
    }

    @Test
    void maskedCardNumberSafe_maskedFormat_returnsTrue() {
        CardholderContactRequest r = new CardholderContactRequest();
        r.setMaskedCardNumber("****-****-****-1234");
        assertThat(r.isMaskedCardNumberSafe()).isTrue();
    }

    @Test
    void maskedCardNumberSafe_rawPan_returnsFalse() {
        CardholderContactRequest r = new CardholderContactRequest();
        r.setMaskedCardNumber("4111111111111111");
        assertThat(r.isMaskedCardNumberSafe()).isFalse();
    }

    @Test
    void atLeastOneContactPresent_bothBlank_returnsFalse() {
        CardholderContactRequest r = new CardholderContactRequest();
        r.setEmail("");
        r.setPhoneNumber("");
        assertThat(r.isAtLeastOneContactPresent()).isFalse();
    }

    @Test
    void gettersAndSetters() {
        CardholderContactRequest r = new CardholderContactRequest();
        r.setCardToken("vault:v1:tok");
        r.setMaskedCardNumber("****-****-****-5678");
        r.setEmail("a@b.com");
        r.setPhoneNumber("+1234567890");
        r.setTransactionNotificationsEnabled(false);

        assertThat(r.getCardToken()).isEqualTo("vault:v1:tok");
        assertThat(r.getMaskedCardNumber()).isEqualTo("****-****-****-5678");
        assertThat(r.getEmail()).isEqualTo("a@b.com");
        assertThat(r.getPhoneNumber()).isEqualTo("+1234567890");
        assertThat(r.getTransactionNotificationsEnabled()).isFalse();
    }
}
