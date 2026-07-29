package com.creditcard.notification.web;

import com.creditcard.notification.domain.CardholderContact;
import com.creditcard.notification.service.CardholderContactService;
import com.creditcard.notification.web.dto.CardholderContactRequest;
import com.creditcard.notification.web.dto.CardholderContactResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cardholder-contacts")
public class CardholderContactController {

    private final CardholderContactService service;

    public CardholderContactController(CardholderContactService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CardholderContactResponse register(@Valid @RequestBody CardholderContactRequest request) {
        return toResponse(service.upsert(request));
    }

    @PutMapping("/{cardToken}")
    public CardholderContactResponse update(@PathVariable String cardToken,
                                             @Valid @RequestBody CardholderContactRequest request) {
        request.setCardToken(cardToken);
        return toResponse(service.upsert(request));
    }

    @GetMapping("/{cardToken}")
    public CardholderContactResponse get(@PathVariable String cardToken) {
        return toResponse(service.findByCardToken(cardToken));
    }

    private CardholderContactResponse toResponse(CardholderContact contact) {
        CardholderContactResponse r = new CardholderContactResponse();
        r.setCardToken(contact.getCardToken());
        r.setMaskedCardNumber(contact.getMaskedCardNumber());
        r.setEmail(contact.getEmail());
        r.setPhoneNumber(contact.getPhoneNumber());
        r.setTransactionNotificationsEnabled(contact.isTransactionNotificationsEnabled());
        r.setRegisteredAt(contact.getRegisteredAt());
        r.setUpdatedAt(contact.getUpdatedAt());
        return r;
    }
}
