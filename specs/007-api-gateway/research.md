# Research: API Gateway

**Feature**: `specs/007-api-gateway`
**Date**: 2026-06-17

---

## Decision 1: Reactive Framework — Spring Cloud Gateway over Spring WebFlux standalone

**Decision**: Use `spring-cloud-starter-gateway` (Spring Boot 3.x, reactive Netty stack). The gateway application is a Spring Boot 3.2.5 app using Spring Cloud 2023.0.1 BOM.

**Rationale**: The constitution explicitly mandates Spring Cloud Gateway for the external API gateway tier. Spring Cloud Gateway ships with first-class support for rate limiting (`RequestRateLimiter`), circuit breaking (`CircuitBreaker`), header manipulation, and route predicates — all required by this feature. Building these from scratch in plain WebFlux would be unnecessary.

**Alternatives considered**:
- Spring Boot WebFlux without SCG: loses built-in `RedisRateLimiter`, `CircuitBreaker` filters, and route DSL.
- Spring MVC (blocking): incompatible with SCG's reactive Netty runtime; cannot coexist in the same app.

---

## Decision 2: JWT Validation — Spring Security OAuth2 Resource Server (Reactive)

**Decision**: Use `spring-boot-starter-oauth2-resource-server` (reactive). Configure a `SecurityWebFilterChain` with `.oauth2ResourceServer(oauth2 -> oauth2.jwt(...))`. The JWKS endpoint URI of the auth-service is provided via `spring.security.oauth2.resourceserver.jwt.jwks-uri` configuration.

**Rationale**: Spring Security's reactive OAuth2 Resource Server handles JWT signature verification, expiry checking, and claim extraction without manual implementation. It integrates directly with Spring Cloud Gateway's reactive security model (`ServerHttpSecurity`).

**JWT claims structure** (Spring Authorization Server defaults):
- `sub`: unique client/user identifier — used as the rate limit key
- `authorities`: array of granted authority strings, e.g., `["ROLE_MERCHANT", "ROLE_ADMIN"]`
- `merchantId`: custom claim, present only for MERCHANT-role tokens — extracted for data scoping

**Custom converter**: A `ReactiveJwtAuthenticationConverter` maps the `authorities` claim to `SimpleGrantedAuthority` objects and extracts `merchantId` into a custom principal.

**Alternatives considered**:
- Manual JWT parsing with `nimbus-jose-jwt`: duplicates what Spring Security already provides; increases attack surface.
- Opaque token introspection: requires a network call to auth-service on every request; higher latency than local JWKS-based verification.

---

## Decision 3: gRPC Client for Transaction Service

**Decision**: Use `net.devh:grpc-client-spring-boot-starter:3.1.0.RELEASE` (client-only, same version as transaction-service server-side). Use `TransactionServiceGrpc.TransactionServiceFutureStub` (async, returns `ListenableFuture`) bridged to Project Reactor via `Mono.fromFuture(stub.rpc(request))`.

**Rationale**: Matching the version used by transaction-service avoids compatibility issues. The future stub is non-blocking and compatible with Netty's event loop (unlike the blocking stub). `Mono.fromFuture()` integrates cleanly into the reactive filter chain.

**gRPC stub choice**:
- `FutureStub` → `Mono.fromFuture()`: minimal ceremony, compatible with Resilience4j `ReactiveCircuitBreaker`.
- `AsyncStub` with `StreamObserver`: more complex; only needed for streaming RPCs. Transaction RPCs are unary.
- `BlockingStub`: MUST NOT be used on the Netty event loop thread — would deadlock.

**Alternatives considered**:
- Plain `ManagedChannel` with manual stub management: `grpc-client-spring-boot-starter` handles lifecycle, TLS config, and retry policy automatically.
- Reactive gRPC (`com.salesforce.servicelibs:reactor-grpc`): additional dependency with no benefit over `Mono.fromFuture()` for unary calls.

---

## Decision 4: Rate Limiting — Spring Cloud Gateway RedisRateLimiter

**Decision**: Use Spring Cloud Gateway's built-in `RequestRateLimiter` `GatewayFilter` with the provided `RedisRateLimiter` implementation and `spring-data-redis-reactive`. Key resolver extracts the JWT `sub` claim from the `Principal` set by Spring Security.

**Rate limit values** (configurable via application.yml):
- Transaction submission: 10 requests/second, burst capacity 20
- Transaction lookup: 50 requests/second, burst capacity 100
- Reporting routes: 30 requests/second, burst capacity 60

**Rationale**: `RedisRateLimiter` uses a Redis Lua script for atomic token-bucket operations distributed across all gateway instances. No custom Redis scripting needed. Constitution mandates Redis-backed rate limiting.

**Key resolver**: Custom `KeyResolver` bean that extracts `sub` from the `JwtAuthenticationToken`. Returns `Mono.empty()` for unauthenticated requests (they are rejected by security layer first).

**Alternatives considered**:
- Bucket4j with Redis: more flexible but requires custom integration with SCG filter chain.
- In-memory rate limiting: not distributed; fails when multiple gateway replicas run.
- Guava `RateLimiter`: in-memory only, same problem.

---

## Decision 5: Circuit Breaker — Spring Cloud CircuitBreaker (Resilience4j)

**Decision**: Use `spring-cloud-starter-circuitbreaker-resilience4j` (reactive variant). For reporting routes: use SCG's `CircuitBreaker` `GatewayFilter` with `fallbackUri: forward:/fallback/reporting`. For gRPC routes: use `ReactiveCircuitBreaker` from `ReactiveCircuitBreakerFactory` programmatically inside `GrpcTransactionGatewayFilterFactory`.

**Configuration**:
```yaml
resilience4j:
  circuitbreaker:
    instances:
      transaction-service-cb:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
      reporting-service-cb:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
```

**Rationale**: Resilience4j is the SCG-recommended circuit breaker; it integrates with Spring Cloud Gateway's `CircuitBreaker` filter natively. The reactive variant (`io.github.resilience4j:resilience4j-reactor`) works with `Mono`/`Flux`.

**Alternatives considered**:
- Hystrix: deprecated, removed from Spring Cloud 2021+.
- Custom retry + timeout logic: reimplements what Resilience4j provides; error-prone.

---

## Decision 6: REST-to-gRPC Translation for Transaction Routes

**Decision**: Implement a custom `AbstractGatewayFilterFactory<Config>` named `GrpcTransactionGatewayFilterFactory`. Each route that targets transaction-service uses this filter. The filter reads the HTTP request body/path, constructs the appropriate proto request, calls the gRPC future stub via `Mono.fromFuture()`, maps the proto response to a JSON response DTO, and writes it directly to `ServerWebExchange.getResponse()` via `setComplete()`. Spring Cloud Gateway's routing step is bypassed (`chain.filter()` is never called).

**Route URI**: Set to `no-op://localhost` — Spring Cloud Gateway requires a URI but it is never reached because the filter completes the exchange before the routing step.

**Filter execution order within a route** (pre-filters left to right):
1. `RequestRateLimiter` — enforces quota
2. `CircuitBreaker` — wraps remainder of chain; if open, redirects to fallback URI
3. `GrpcTransaction` — translates REST→gRPC→REST; short-circuits after writing response

**Rationale**: This composition allows rate limiting and circuit breaking to apply naturally to gRPC-backed routes without special-casing the filter chain.

**Alternatives considered**:
- `GlobalFilter` intercepting by path: cannot easily apply per-route rate limits and circuit breakers using SCG's built-in filters.
- Envoy/gRPC-Gateway sidecar: adds infrastructure complexity outside scope.
- Adding REST controllers alongside SCG routes: mixing controller and gateway routing requires careful ordering and is harder to test.

---

## Decision 7: Reporting Service Routing — Internal REST (Constitution Exception)

**Decision**: Route reporting requests via WebClient HTTP proxy (standard Spring Cloud Gateway routing). Target URI: `http://reporting-service:8086`. Path rewrite: `/api/v1/reporting/{segment}` → `/internal/reporting/{segment}`.

**Constitution Exception**: Principle V states that internal synchronous service-to-service calls MUST use gRPC. This route uses REST internally, which is technically prohibited.

**Justification**: The reporting-service was designed and implemented as a read-only REST service (Phase 6) without a gRPC interface. Adding gRPC to a completed service would violate Principle IV (iterative one-step development — no reopening completed services). The reporting data is read-only and non-financial (no state changes), making the risk of REST-vs-gRPC consistency semantics negligible.

**Mitigation**: Future constitution amendment or reporting-service v2 should add gRPC. The exception is isolated to reporting routes only.

---

## Decision 8: Merchant Data Scoping

**Decision**: The `IdentityPropagationFilter` (`GlobalFilter`, runs before routing) injects request headers from JWT claims:
- `X-Merchant-Id`: JWT `merchantId` claim (MERCHANT role only; absent for ADMIN/SUPPORT)
- `X-User-Role`: JWT role (e.g., `MERCHANT`, `ADMIN`, `SUPPORT`)
- `X-User-Sub`: JWT `sub` claim (propagated for all roles)

For transaction submission (`POST /api/v1/transactions`): The gRPC filter reads `X-Merchant-Id` from the exchange attributes and passes it as gRPC metadata. The transaction-service already handles merchant scoping via the token it stores.

For reporting routes: The gateway enforces merchant scoping by adding `merchantId={X-Merchant-Id}` as a required query parameter override when `X-User-Role=MERCHANT`. This prevents a MERCHANT client from querying another merchant's data by providing a different query parameter.

For `GET /api/v1/transactions/{id}`: The gRPC filter calls `GetTransaction`, receives the response, and checks that the returned `merchant_id` matches `X-Merchant-Id`. Returns 403 if mismatched (for MERCHANT role only).

**Rationale**: Enforcing scoping at the gateway is a defence-in-depth measure. Downstream services enforce it independently as a second layer (Principle I — least privilege).

---

## Decision 9: Testing Strategy for 100% Coverage

**Decision**:

| Test Type | Scope | Tools |
|-----------|-------|-------|
| Unit | SecurityConfig, RateLimitConfig, CircuitBreakerConfig, GrpcClientConfig, Filters, FallbackController | JUnit 5 + Mockito |
| Slice | WebFlux controller tests for fallback | `@WebFluxTest` + `SecurityMockServerConfigurers.mockJwt()` |
| Contract (gRPC) | GrpcTransactionGatewayFilterFactory against real proto stubs | `io.grpc:grpc-inprocess` + `InProcessServerBuilder` |
| Integration | Full gateway: JWT validation, rate limiting, gRPC routing, circuit breaker, reporting proxy | `@SpringBootTest` + Testcontainers Redis + gRPC `InProcessServer` + WireMock |

**100% branch coverage gaps to watch**:
- `IdentityPropagationFilter`: MERCHANT vs ADMIN/SUPPORT branches
- `GrpcTransactionGatewayFilterFactory`: INITIATE vs GET operation branches; error path (gRPC exception)
- Merchant ID mismatch check in GetTransaction path
- Circuit breaker OPEN state fallback

**Alternatives considered**:
- Mocking all gRPC calls with Mockito: valid for unit tests; but integration tests must use `InProcessServer` to verify actual proto serialization/deserialization.
