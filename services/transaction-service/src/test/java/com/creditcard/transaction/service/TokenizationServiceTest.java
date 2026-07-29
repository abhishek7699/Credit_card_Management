package com.creditcard.transaction.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.core.VaultTransitOperations;
import org.springframework.vault.support.Ciphertext;
import org.springframework.vault.support.Plaintext;
import org.springframework.vault.VaultException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenizationServiceTest {

    @Mock
    private VaultTemplate vaultTemplate;

    @Mock
    private VaultTransitOperations transitOperations;

    private TokenizationService tokenizationService;

    @BeforeEach
    void setUp() {
        when(vaultTemplate.opsForTransit()).thenReturn(transitOperations);
        tokenizationService = new TokenizationService(vaultTemplate, "card-pan-key");
    }

    @Test
    void shouldCallVaultEncryptWithRawPan() {
        String rawPan = "4111111111111111";
        when(transitOperations.encrypt(eq("card-pan-key"), any(Plaintext.class)))
                .thenReturn(Ciphertext.of("vault:v1:ABCdef123"));

        tokenizationService.tokenize(rawPan);

        ArgumentCaptor<Plaintext> captor = ArgumentCaptor.forClass(Plaintext.class);
        verify(transitOperations).encrypt(eq("card-pan-key"), captor.capture());
        assertThat(new String(captor.getValue().getPlaintext(), StandardCharsets.UTF_8)).isEqualTo(rawPan);
    }

    @Test
    void shouldReturnVaultCiphertextAsToken() {
        when(transitOperations.encrypt(eq("card-pan-key"), any(Plaintext.class)))
                .thenReturn(Ciphertext.of("vault:v1:ABCdef123XYZ"));

        TokenResult result = tokenizationService.tokenize("4111111111111111");

        assertThat(result.getToken()).isEqualTo("vault:v1:ABCdef123XYZ");
        assertThat(result.getToken()).startsWith("vault:");
    }

    @Test
    void shouldMaskAllButLastFourDigits() {
        when(transitOperations.encrypt(any(String.class), any(Plaintext.class))).thenReturn(Ciphertext.of("vault:v1:xyz"));

        TokenResult result = tokenizationService.tokenize("4111111111111234");

        assertThat(result.getMaskedCardNumber()).isEqualTo("****-****-****-1234");
        assertThat(result.getMaskedCardNumber()).matches("\\*{4}-\\*{4}-\\*{4}-\\d{4}");
    }

    @Test
    void tokenResultShouldNotContainRawPan() {
        String rawPan = "4111111111111111";
        when(transitOperations.encrypt(any(String.class), any(Plaintext.class))).thenReturn(Ciphertext.of("vault:v1:xyz"));

        TokenResult result = tokenizationService.tokenize(rawPan);

        assertThat(result.getToken()).doesNotContain(rawPan);
        assertThat(result.getMaskedCardNumber()).doesNotContain(rawPan);
    }

    @Test
    void shouldPropagateVaultExceptionWhenVaultUnavailable() {
        when(transitOperations.encrypt(any(String.class), any(Plaintext.class))).thenThrow(new VaultException("Vault unavailable"));

        assertThatThrownBy(() -> tokenizationService.tokenize("4111111111111111"))
                .isInstanceOf(VaultException.class);
    }
}
