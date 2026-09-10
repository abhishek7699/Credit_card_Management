package com.creditcard.transaction.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultTransitContext;

import java.nio.charset.StandardCharsets;

@Service
public class TokenizationService {

    /**
     * Fixed derivation context. The {@code card-pan-key} transit key is created with
     * {@code convergent_encryption=true derived=true}, so encrypting the same PAN with this
     * context always yields the same ciphertext — which lets downstream services (and test
     * fixtures) use the card token as a stable lookup key.
     */
    private static final byte[] TRANSIT_CONTEXT = "credit-card-pan".getBytes(StandardCharsets.UTF_8);

    private final VaultTemplate vaultTemplate;
    private final String keyName;

    public TokenizationService(
            VaultTemplate vaultTemplate,
            @Value("${spring.vault.transit.key-name:card-pan-key}") String keyName) {
        this.vaultTemplate = vaultTemplate;
        this.keyName = keyName;
    }

    public TokenResult tokenize(String rawPan) {
        String token = vaultTemplate.opsForTransit()
                .encrypt(keyName, rawPan.getBytes(StandardCharsets.UTF_8),
                        VaultTransitContext.fromContext(TRANSIT_CONTEXT));

        String maskedCardNumber = "****-****-****-" + rawPan.substring(rawPan.length() - 4);

        return new TokenResult(token, maskedCardNumber);
    }
}
