package com.creditcard.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigDocsMatcherTest {

    @Test
    void enabledMatchesSwaggerUiAndSpecPaths() {
        ServerWebExchangeMatcher matcher = SecurityConfig.docsMatcher(true);

        assertThat(matches(matcher, "/swagger-ui.html")).isTrue();
        assertThat(matches(matcher, "/swagger-ui/index.html")).isTrue();
        assertThat(matches(matcher, "/webjars/swagger-ui/index.html")).isTrue();
        assertThat(matches(matcher, "/v3/api-docs")).isTrue();
        assertThat(matches(matcher, "/api/v1/bookings")).isFalse();
    }

    @Test
    void disabledMatchesNothing() {
        ServerWebExchangeMatcher matcher = SecurityConfig.docsMatcher(false);

        assertThat(matches(matcher, "/swagger-ui.html")).isFalse();
        assertThat(matches(matcher, "/v3/api-docs")).isFalse();
    }

    private static boolean matches(ServerWebExchangeMatcher matcher, String path) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
        return matcher.matches(exchange).map(ServerWebExchangeMatcher.MatchResult::isMatch).block();
    }
}
