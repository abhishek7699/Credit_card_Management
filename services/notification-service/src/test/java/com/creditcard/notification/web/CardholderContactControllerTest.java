package com.creditcard.notification.web;

import com.creditcard.notification.domain.CardholderContact;
import com.creditcard.notification.service.CardholderContactNotFoundException;
import com.creditcard.notification.service.CardholderContactService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CardholderContactController.class)
class CardholderContactControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CardholderContactService service;

    private CardholderContact stubContact(String cardToken, String email, String phone) {
        CardholderContact c = new CardholderContact();
        c.setCardToken(cardToken);
        c.setMaskedCardNumber("****-****-****-1234");
        c.setEmail(email);
        c.setPhoneNumber(phone);
        c.setTransactionNotificationsEnabled(true);
        return c;
    }

    @Test
    void postValidContact_returns201() throws Exception {
        CardholderContact saved = stubContact("vault:v1:tok_abc", "user@example.com", "+14155551234");
        when(service.upsert(any())).thenReturn(saved);

        mockMvc.perform(post("/api/v1/cardholder-contacts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "cardToken", "vault:v1:tok_abc",
                    "maskedCardNumber", "****-****-****-1234",
                    "email", "user@example.com",
                    "phoneNumber", "+14155551234"
                ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cardToken").value("vault:v1:tok_abc"));
    }

    @Test
    void postMissingCardToken_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/cardholder-contacts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "maskedCardNumber", "****-****-****-1234",
                    "email", "user@example.com"
                ))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postNoEmailOrPhone_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/cardholder-contacts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "cardToken", "vault:v1:tok_abc",
                    "maskedCardNumber", "****-****-****-1234"
                ))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postRawPanAsMaskedCardNumber_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/cardholder-contacts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "cardToken", "vault:v1:tok_abc",
                    "maskedCardNumber", "4111111111111111",
                    "email", "user@example.com"
                ))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putValidContact_returns200() throws Exception {
        CardholderContact saved = stubContact("vault:v1:tok_abc", "new@example.com", "+14155554321");
        when(service.upsert(any())).thenReturn(saved);

        mockMvc.perform(put("/api/v1/cardholder-contacts/vault:v1:tok_abc")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "cardToken", "vault:v1:tok_abc",
                    "maskedCardNumber", "****-****-****-1234",
                    "email", "new@example.com",
                    "phoneNumber", "+14155554321"
                ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("new@example.com"));
    }

    @Test
    void getExistingContact_returns200() throws Exception {
        CardholderContact contact = stubContact("vault:v1:tok_abc", "user@example.com", "+14155551234");
        when(service.findByCardToken("vault:v1:tok_abc")).thenReturn(contact);

        mockMvc.perform(get("/api/v1/cardholder-contacts/vault:v1:tok_abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardToken").value("vault:v1:tok_abc"))
                .andExpect(jsonPath("$.email").value("user@example.com"));
    }

    @Test
    void getMissingContact_returns404() throws Exception {
        when(service.findByCardToken(anyString()))
                .thenThrow(new CardholderContactNotFoundException("vault:v1:tok_missing"));

        mockMvc.perform(get("/api/v1/cardholder-contacts/vault:v1:tok_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No contact preferences found for card token"));
    }
}
