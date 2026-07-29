package com.creditcard.transaction.config;

import org.junit.jupiter.api.Test;
import org.springframework.vault.core.VaultTemplate;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class VaultConfigTest {

    @Test
    void vaultTemplateBeanShouldBeCreated() throws Exception {
        VaultConfig config = new VaultConfig();
        setField(config, "vaultHost", "localhost");
        setField(config, "vaultPort", 8200);
        setField(config, "vaultScheme", "http");
        setField(config, "vaultToken", "dev-root-token");

        VaultTemplate template = config.vaultTemplate();

        assertThat(template).isNotNull();
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
