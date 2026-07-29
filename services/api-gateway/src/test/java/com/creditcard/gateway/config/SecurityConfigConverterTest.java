package com.creditcard.gateway.config;

import com.creditcard.gateway.exception.GatewayAuthExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SecurityConfigConverterTest {

    private ReactiveJwtAuthenticationConverter converter;

    @BeforeEach
    void setUp() {
        SecurityConfig config = new SecurityConfig(
                new GatewayAuthExceptionHandler(new ObjectMapper()));
        converter = config.jwtAuthenticationConverter();
    }

    private Jwt buildJwt(Jwt.Builder builder) {
        return builder
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    @Test
    void jwtWithScopeClaim_extractsScopeAsRole() {
        Jwt jwt = buildJwt(Jwt.withTokenValue("tok").claim("scope", "read"));

        StepVerifier.create(converter.convert(jwt))
                .assertNext(auth -> assertThat(auth.getAuthorities())
                        .extracting("authority")
                        .containsExactly("ROLE_READ"))
                .verifyComplete();
    }

    @Test
    void jwtWithBlankScope_returnsEmptyAuthorities() {
        Jwt jwt = buildJwt(Jwt.withTokenValue("tok").claim("scope", "  "));

        StepVerifier.create(converter.convert(jwt))
                .assertNext(auth -> assertThat(auth.getAuthorities()).isEmpty())
                .verifyComplete();
    }

    @Test
    void jwtWithNoClaims_returnsEmptyAuthorities() {
        Jwt jwt = buildJwt(Jwt.withTokenValue("tok"));

        StepVerifier.create(converter.convert(jwt))
                .assertNext(auth -> assertThat(auth.getAuthorities()).isEmpty())
                .verifyComplete();
    }
}
