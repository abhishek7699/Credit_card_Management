package com.creditcard.notification.service;

import com.creditcard.notification.domain.CardholderContact;
import com.creditcard.notification.repository.CardholderContactRepository;
import com.creditcard.notification.web.dto.CardholderContactRequest;
import org.springframework.stereotype.Service;

@Service
public class CardholderContactService {

    private final CardholderContactRepository repo;

    public CardholderContactService(CardholderContactRepository repo) {
        this.repo = repo;
    }

    public CardholderContact upsert(CardholderContactRequest request) {
        CardholderContact contact = repo.findById(request.getCardToken())
                .orElse(new CardholderContact());

        contact.setCardToken(request.getCardToken());
        contact.setMaskedCardNumber(request.getMaskedCardNumber());
        contact.setEmail(request.getEmail());
        contact.setPhoneNumber(request.getPhoneNumber());
        contact.setTransactionNotificationsEnabled(
                request.getTransactionNotificationsEnabled() == null
                        ? true
                        : request.getTransactionNotificationsEnabled());

        return repo.save(contact);
    }

    public CardholderContact findByCardToken(String cardToken) {
        return repo.findById(cardToken)
                .orElseThrow(() -> new CardholderContactNotFoundException(cardToken));
    }
}
