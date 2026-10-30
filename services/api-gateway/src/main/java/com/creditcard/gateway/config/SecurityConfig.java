package com.creditcard.gateway.config;

import com.creditcard.gateway.exception.GatewayAuthExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Flux;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final GatewayAuthExceptionHandler authExceptionHandler;

    public SecurityConfig(GatewayAuthExceptionHandler authExceptionHandler) {
        this.authExceptionHandler = authExceptionHandler;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(auth -> auth
                        // Public endpoints
                        .pathMatchers("/actuator/**").permitAll()
                        .pathMatchers("/fallback/**").permitAll()
                        // Transaction routes
                        .pathMatchers(HttpMethod.POST, "/api/v1/transactions")
                                .hasAnyRole("MERCHANT", "ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/transactions/**")
                                .authenticated()
                        // Reporting — admin/support only for sensitive routes
                        .pathMatchers(HttpMethod.GET, "/api/v1/reporting/merchants/summary")
                                .hasAnyRole("ADMIN", "SUPPORT")
                        .pathMatchers(HttpMethod.GET, "/api/v1/reporting/settlements/**")
                                .hasAnyRole("ADMIN", "SUPPORT")
                        // Reporting — all authenticated roles for per-merchant and transactions
                        .pathMatchers(HttpMethod.GET, "/api/v1/reporting/**")
                                .authenticated()
                        // Room board (layer 2b) — front-desk OR housekeeping. Matched
                        // before the broader booking/room rule below (Spring evaluates
                        // authorizeExchange matchers in order; first match governs), so
                        // this one specific route gets HOUSEKEEPING without granting it
                        // to check-in/check-out/room-search/booking-get too.
                        .pathMatchers(HttpMethod.GET, "/api/v1/rooms/board")
                                .hasAnyRole("FRONT_DESK", "HOUSEKEEPING", "ADMIN")
                        // Room status update (layer 2b) — same front-desk/housekeeping
                        // split as the board, matched before the broad rule for the same
                        // reason.
                        .pathMatchers(HttpMethod.PATCH, "/api/v1/rooms/*/status")
                                .hasAnyRole("FRONT_DESK", "HOUSEKEEPING", "ADMIN")
                        // Booking — B2B, staff-only (FR-017): front-desk staff create and
                        // manage bookings on a guest's behalf; guests never call this API.
                        .pathMatchers("/api/v1/bookings/**", "/api/v1/rooms/**")
                                .hasAnyRole("FRONT_DESK", "ADMIN")
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authExceptionHandler)
                        .accessDeniedHandler(authExceptionHandler)
                )
                .build();
    }

    @Bean
    public ReactiveJwtAuthenticationConverter jwtAuthenticationConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<String> authorities = extractAuthorities(jwt);
            List<SimpleGrantedAuthority> grantedAuthorities = authorities.stream()
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());
            return Flux.fromIterable(grantedAuthorities);
        });
        return converter;
    }

    @SuppressWarnings("unchecked")
    private Collection<String> extractAuthorities(Jwt jwt) {
        Object authoritiesClaim = jwt.getClaim("authorities");
        if (authoritiesClaim instanceof Collection) {
            return (Collection<String>) authoritiesClaim;
        }
        // Fallback: check scope claim
        String scope = jwt.getClaimAsString("scope");
        if (scope != null && !scope.isBlank()) {
            return List.of("ROLE_" + scope.toUpperCase());
        }
        return Collections.emptyList();
    }
}
