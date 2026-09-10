package com.creditcard.transaction.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.vault.VaultException;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.core.VaultTransitOperations;
import org.springframework.vault.support.VaultTransitContext;

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
    void shouldCallVaultConvergentEncryptWithRawPanAndFixedContext() {
        String rawPan = "4111111111111111";
        when(transitOperations.encrypt(eq("card-pan-key"), any(byte[].class), any(VaultTransitContext.class)))
                .thenReturn("vault:v1:ABCdef123");

        tokenizationService.tokenize(rawPan);

        ArgumentCaptor<byte[]> plaintextCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<VaultTransitContext> contextCaptor = ArgumentCaptor.forClass(VaultTransitContext.class);
        verify(transitOperations).encrypt(eq("card-pan-key"), plaintextCaptor.capture(), contextCaptor.capture());
        assertThat(new String(plaintextCaptor.getValue(), StandardCharsets.UTF_8)).isEqualTo(rawPan);
        assertThat(new String(contextCaptor.getValue().getContext(), StandardCharsets.UTF_8))
                .isEqualTo("credit-card-pan");
    }

    @Test
    void shouldUseTheSameContextAcrossCallsSoTokenizationIsDeterministic() {
        when(transitOperations.encrypt(eq("card-pan-key"), any(byte[].class), any(VaultTransitContext.class)))
                .thenReturn("vault:v1:x");

        tokenizationService.tokenize("4111111111111111");
        tokenizationService.tokenize("4111111111111111");

        ArgumentCaptor<VaultTransitContext> contextCaptor = ArgumentCaptor.forClass(VaultTransitContext.class);
        verify(transitOperations, org.mockito.Mockito.times(2))
                .encrypt(eq("card-pan-key"), any(byte[].class), contextCaptor.capture());
        assertThat(contextCaptor.getAllValues().get(0).getContext())
                .isEqualTo(contextCaptor.getAllValues().get(1).getContext());
    }

    @Test
    void shouldReturnVaultCiphertextAsToken() {
        when(transitOperations.encrypt(eq("card-pan-key"), any(byte[].class), any(VaultTransitContext.class)))
                .thenReturn("vault:v1:ABCdef123XYZ");

        TokenResult result = tokenizationService.tokenize("4111111111111111");

        assertThat(result.getToken()).isEqualTo("vault:v1:ABCdef123XYZ");
        assertThat(result.getToken()).startsWith("vault:");
    }

    @Test
    void shouldMaskAllButLastFourDigits() {
        when(transitOperations.encrypt(any(String.class), any(byte[].class), any(VaultTransitContext.class)))
                .thenReturn("vault:v1:xyz");

        TokenResult result = tokenizationService.tokenize("4111111111111234");

        assertThat(result.getMaskedCardNumber()).isEqualTo("****-****-****-1234");
        assertThat(result.getMaskedCardNumber()).matches("\\*{4}-\\*{4}-\\*{4}-\\d{4}");
    }

    @Test
    void tokenResultShouldNotContainRawPan() {
        String rawPan = "4111111111111111";
        when(transitOperations.encrypt(any(String.class), any(byte[].class), any(VaultTransitContext.class)))
                .thenReturn("vault:v1:xyz");

        TokenResult result = tokenizationService.tokenize(rawPan);

        assertThat(result.getToken()).doesNotContain(rawPan);
        assertThat(result.getMaskedCardNumber()).doesNotContain(rawPan);
    }

    @Test
    void shouldPropagateVaultExceptionWhenVaultUnavailable() {
        when(transitOperations.encrypt(any(String.class), any(byte[].class), any(VaultTransitContext.class)))
                .thenThrow(new VaultException("Vault unavailable"));

        assertThatThrownBy(() -> tokenizationService.tokenize("4111111111111111"))
                .isInstanceOf(VaultException.class);
    }
}
