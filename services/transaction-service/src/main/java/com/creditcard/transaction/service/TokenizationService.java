package com.creditcard.transaction.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.Plaintext;

@Service
public class TokenizationService {

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
                .encrypt(keyName, Plaintext.of(rawPan))
                .getCiphertext();

        String maskedCardNumber = "****-****-****-" + rawPan.substring(rawPan.length() - 4);

        return new TokenResult(token, maskedCardNumber);
    }
}
