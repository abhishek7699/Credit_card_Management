package com.creditcard.notification.service;

import com.creditcard.notification.domain.CardholderContact;
import com.creditcard.notification.repository.CardholderContactRepository;
import com.creditcard.notification.web.dto.CardholderContactRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardholderContactServiceTest {

    @Mock
    private CardholderContactRepository repo;

    @InjectMocks
    private CardholderContactService service;

    @Test
    void upsert_nullTransactionNotificationsEnabled_defaultsToTrue() {
        CardholderContactRequest req = new CardholderContactRequest();
        req.setCardToken("vault:v1:tok");
        req.setMaskedCardNumber("****-****-****-1234");
        req.setEmail("user@example.com");
        req.setTransactionNotificationsEnabled(null);

        CardholderContact saved = new CardholderContact();
        saved.setCardToken("vault:v1:tok");
        saved.setTransactionNotificationsEnabled(true);
        when(repo.findById("vault:v1:tok")).thenReturn(Optional.empty());
        when(repo.save(any())).thenReturn(saved);

        CardholderContact result = service.upsert(req);

        ArgumentCaptor<CardholderContact> captor = ArgumentCaptor.forClass(CardholderContact.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().isTransactionNotificationsEnabled()).isTrue();
    }

    @Test
    void upsert_existingContact_updatesFields() {
        CardholderContactRequest req = new CardholderContactRequest();
        req.setCardToken("vault:v1:tok");
        req.setMaskedCardNumber("****-****-****-5678");
        req.setEmail("new@example.com");
        req.setTransactionNotificationsEnabled(false);

        CardholderContact existing = new CardholderContact();
        existing.setCardToken("vault:v1:tok");
        when(repo.findById("vault:v1:tok")).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upsert(req);

        ArgumentCaptor<CardholderContact> captor = ArgumentCaptor.forClass(CardholderContact.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("new@example.com");
        assertThat(captor.getValue().isTransactionNotificationsEnabled()).isFalse();
    }

    @Test
    void findByCardToken_notFound_throwsCardholderContactNotFoundException() {
        when(repo.findById("vault:v1:missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByCardToken("vault:v1:missing"))
                .isInstanceOf(CardholderContactNotFoundException.class);
    }

    @Test
    void findByCardToken_found_returnsContact() {
        CardholderContact contact = new CardholderContact();
        contact.setCardToken("vault:v1:tok");
        when(repo.findById("vault:v1:tok")).thenReturn(Optional.of(contact));

        CardholderContact result = service.findByCardToken("vault:v1:tok");
        assertThat(result.getCardToken()).isEqualTo("vault:v1:tok");
    }
}
