package com.creditcard.transaction.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;

@Configuration
public class VaultConfig {

    @Value("${spring.vault.host:localhost}")
    private String vaultHost;

    @Value("${spring.vault.port:8200}")
    private int vaultPort;

    @Value("${spring.vault.scheme:http}")
    private String vaultScheme;

    @Value("${spring.vault.token:dev-root-token}")
    private String vaultToken;

    @Bean
    public VaultTemplate vaultTemplate() {
        VaultEndpoint endpoint = VaultEndpoint.create(vaultHost, vaultPort);
        endpoint.setScheme(vaultScheme);
        return new VaultTemplate(endpoint, new TokenAuthentication(vaultToken));
    }
}
