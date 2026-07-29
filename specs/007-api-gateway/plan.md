# Implementation Plan: API Gateway

**Branch**: `007-api-gateway` | **Date**: 2026-06-17 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/007-api-gateway/spec.md`

## Summary

Build the api-gateway: the sole external REST entry point for the credit card payment gateway. The service validates JWT tokens issued by a Spring Authorization Server, enforces MERCHANT/ADMIN/SUPPORT role-based authorization, applies Redis-backed per-client rate limiting, wraps downstream calls in Resilience4j circuit breakers, routes transaction requests to the transaction-service via gRPC, and routes reporting requests to the reporting-service via internal HTTP proxy. Implemented as a Spring Boot 3.2.5 + Spring Cloud Gateway (reactive Netty) application with 100% JaCoCo instruction/branch coverage.

## Technical Context

**Language/Version**: Java 17 (LTS)

**Primary Dependencies**:
- `spring-cloud-starter-gateway` (Spring Cloud 2023.0.1 BOM)
- `spring-boot-starter-oauth2-resource-server` (reactive JWT validation)
- `spring-cloud-starter-circuitbreaker-resilience4j` (reactive circuit breaker)
- `spring-boot-starter-data-redis-reactive` (RedisRateLimiter)
- `net.devh:grpc-client-spring-boot-starter:3.1.0.RELEASE` (gRPC client for transaction-service)
- `io.grpc:grpc-protobuf:1.63.0`, `io.grpc:grpc-stub:1.63.0`
- `com.google.protobuf:protobuf-java:3.25.3` (proto-generated stubs from `proto/v1/transaction_service.proto`)
- `com.google.protobuf:protobuf-gradle-plugin:0.9.4`
- `net.logstash.logback:logstash-logback-encoder:7.4`
- `io.micrometer:micrometer-tracing-bridge-brave` + `zipkin-reporter-brave`

**Test Dependencies**:
- `spring-boot-starter-test`
- `io.grpc:grpc-testing:1.63.0` (InProcessServerBuilder for gRPC unit tests)
- `org.testcontainers:testcontainers:1.19.7`, `org.testcontainers:junit-jupiter:1.19.7`
- `com.github.tomakehurst:wiremock-jre8-standalone:2.35.1` (HTTP mock for reporting-service)
- `com.nimbusds:nimbus-jose-jwt:9.37.3` (generate test RSA keys for JWT)
- `spring-security-test`

**Storage**: Redis only (no CockroachDB, no Flyway). No database connection.

**Testing**: JUnit 5 + Mockito + Testcontainers (Redis) + `grpc-testing` InProcessServer + WireMock

**Target Platform**: Linux container (Kubernetes GKE). Docker Compose for local development.

**Performance Goals**: 503 fallback response delivered within 2 seconds when downstream is unavailable (per SC-003).

**Constraints**:
- No Kafka consumers or producers (gateway is purely REST-in, gRPC/REST-out)
- No CockroachDB connection; no Flyway migrations
- Rate limit key = JWT `sub` claim
- PANs must never appear in logs (Logback TurboFilter)
- JaCoCo: 100% instruction + branch coverage (exclude `ApiGatewayApplication.class`)

**Scale/Scope**: 2 replicas in Kubernetes; handles all external traffic to the payment gateway

## Constitution Check

*GATE: Must pass before implementation. Re-checked after design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Tokenization & Zero Raw Card Data | ✅ PASS | Gateway receives card data in request body and passes it to gRPC InitiateTransaction. PanMaskingFilter (Logback TurboFilter) prevents any PAN from appearing in logs. Raw card data is never stored or logged. |
| II. Test-First / 100% Coverage | ✅ PLANNED | TDD mandatory. 100% JaCoCo gate enforced in build. Contract tests for gRPC stubs. Integration tests with Testcontainers + WireMock. |
| III. Idempotent Event Consumers | ✅ N/A | No Kafka consumers in this service. |
| IV. Iterative One-Step Development | ✅ COMPLIANT | All 6 prior services are complete. api-gateway is the 7th and final backend service, implemented one class at a time. |
| V. Layered Architecture & Communication Tiers | ⚠️ PARTIAL — see exception below | Transaction-service: gRPC ✅. Reporting-service: internal REST ⚠️ (documented exception). |
| VI. Observability & Audit Trail | ✅ PLANNED | Structured JSON logging (Logstash encoder). Trace ID via `X-Trace-Id` HTTP header. Micrometer Tracing (Brave). Audit log for all 401/403/429/503 rejections. |
| VII. Proto-First Service Contracts | ✅ COMPLIANT | Uses existing `proto/v1/transaction_service.proto`. No new proto files required (api-gateway is a client). |
| VIII. Frontend Code Sharing | ✅ N/A | Backend service only. |

## Project Structure

### Documentation (this feature)

```text
specs/007-api-gateway/
├── plan.md              # This file
├── research.md          # Phase 0 — architectural decisions
├── data-model.md        # Phase 1 — logical entities
├── quickstart.md        # Phase 1 — integration scenarios
├── contracts/
│   └── rest-api-v1.md  # Phase 1 — external REST contract
└── tasks.md             # Phase 2 — task breakdown (created by /speckit-tasks)
```

### Source Code

```text
services/api-gateway/
├── build.gradle
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/com/creditcard/gateway/
    │   │   ├── ApiGatewayApplication.java
    │   │   ├── config/
    │   │   │   ├── SecurityConfig.java                 # SecurityWebFilterChain, JWT ReactiveJwtAuthenticationConverter
    │   │   │   ├── RateLimitConfig.java                # RedisRateLimiter bean, JwtSubjectKeyResolver bean
    │   │   │   ├── CircuitBreakerConfig.java           # ReactiveCircuitBreakerFactory + Resilience4j registry
    │   │   │   ├── GrpcClientConfig.java               # ManagedChannel + TransactionServiceFutureStub beans
    │   │   │   ├── RouteConfig.java                    # RouteLocatorBuilder — all SCG routes
    │   │   │   └── logging/
    │   │   │       └── PanMaskingFilter.java           # Logback TurboFilter — blocks PAN in any log
    │   │   ├── filter/
    │   │   │   ├── GrpcTransactionGatewayFilterFactory.java  # AbstractGatewayFilterFactory: REST→gRPC→REST
    │   │   │   ├── IdentityPropagationFilter.java      # GlobalFilter: injects X-User-Sub, X-User-Role, X-Merchant-Id
    │   │   │   ├── MerchantScopeEnforcementFilter.java # GlobalFilter: enforces merchant path param matches JWT
    │   │   │   └── AuditLoggingFilter.java             # GlobalFilter (Ordered): logs all 401/403/429/503
    │   │   ├── fallback/
    │   │   │   └── FallbackController.java             # @RestController at /fallback/{service}: returns 503 JSON
    │   │   └── exception/
    │   │       └── GatewayAuthExceptionHandler.java    # ServerAuthenticationEntryPoint (401) + AccessDeniedHandler (403)
    │   └── resources/
    │       ├── application.yml                         # Routes, rate limits, circuit breaker, gRPC client, Redis
    │       ├── application-test.yml                    # Testcontainers overrides, test circuit breaker thresholds
    │       └── logback-spring.xml                      # PanMaskingFilter wired; Logstash prod, pattern test
    └── test/
        └── java/com/creditcard/gateway/
            ├── config/
            │   ├── SecurityConfigTest.java             # JWT converter, role extraction, SecurityWebFilterChain
            │   ├── RateLimitConfigTest.java            # KeyResolver extracts sub, fallback for missing principal
            │   └── CircuitBreakerConfigTest.java       # CircuitBreakerRegistry instances configured correctly
            ├── filter/
            │   ├── GrpcTransactionGatewayFilterFactoryTest.java  # INITIATE, GET, error path, merchant mismatch
            │   ├── IdentityPropagationFilterTest.java  # MERCHANT headers, ADMIN (no X-Merchant-Id), SUPPORT
            │   ├── MerchantScopeEnforcementFilterTest.java       # path match vs JWT merchantId
            │   └── AuditLoggingFilterTest.java         # 401/403 logged; PAN absent from log output
            ├── fallback/
            │   └── FallbackControllerTest.java         # /fallback/transaction, /fallback/reporting → 503 JSON
            ├── exception/
            │   └── GatewayAuthExceptionHandlerTest.java # 401 JSON format, 403 JSON format
            └── integration/
                └── ApiGatewayIntegrationTest.java      # Full gateway: Testcontainers Redis + InProcessServer + WireMock
```

### Configuration: `application.yml` key sections

```yaml
server:
  port: 8080

management:
  server:
    port: 8090
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always

spring:
  application:
    name: api-gateway
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  security:
    oauth2:
      resourceserver:
        jwt:
          jwks-uri: ${AUTH_SERVICE_JWKS_URI:http://auth-service:9000/.well-known/jwks.json}
  cloud:
    gateway:
      routes:
        # Transaction routes (gRPC via GrpcTransactionGatewayFilterFactory)
        - id: transaction-submit
          uri: no-op://transaction-grpc
          predicates:
            - Path=/api/v1/transactions
            - Method=POST
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 10
                redis-rate-limiter.burstCapacity: 20
                key-resolver: "#{@jwtSubjectKeyResolver}"
            - name: CircuitBreaker
              args:
                name: transaction-service-cb
                fallbackUri: forward:/fallback/transaction
            - name: GrpcTransaction
              args:
                operation: INITIATE

        - id: transaction-get
          uri: no-op://transaction-grpc
          predicates:
            - Path=/api/v1/transactions/{id}
            - Method=GET
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 50
                redis-rate-limiter.burstCapacity: 100
                key-resolver: "#{@jwtSubjectKeyResolver}"
            - name: CircuitBreaker
              args:
                name: transaction-service-cb
                fallbackUri: forward:/fallback/transaction
            - name: GrpcTransaction
              args:
                operation: GET

        # Reporting routes (HTTP proxy to reporting-service)
        - id: reporting-transactions
          uri: ${REPORTING_SERVICE_URL:http://reporting-service:8086}
          predicates:
            - Path=/api/v1/reporting/transactions
            - Method=GET
          filters:
            - RewritePath=/api/v1/reporting/(?<segment>.*), /internal/reporting/$\{segment}
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 30
                redis-rate-limiter.burstCapacity: 60
                key-resolver: "#{@jwtSubjectKeyResolver}"
            - name: CircuitBreaker
              args:
                name: reporting-service-cb
                fallbackUri: forward:/fallback/reporting

        # ... additional reporting routes (same pattern)

grpc:
  client:
    transaction-service:
      address: ${TRANSACTION_SERVICE_GRPC_ADDRESS:static://transaction-service:9090}
      negotiation-type: plaintext  # mTLS in production via K8s service mesh

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

### Kubernetes manifests

```text
k8s/api-gateway/
├── deployment.yaml   # 2 replicas, port 8080, management port 8090, liveness/readiness probes
└── service.yaml      # ClusterIP, port 8080
```

### Docker Compose

```yaml
# docker-compose.yml addition:
api-gateway:
  build:
    context: .
    dockerfile: services/api-gateway/Dockerfile
  depends_on:
    redis:
      condition: service_healthy
    transaction-service:
      condition: service_started
    reporting-service:
      condition: service_started
  ports:
    - "8087:8080"    # HTTP (8087 on host to avoid conflict with cockroachdb's 8080)
    - "8088:8090"    # Management
  environment:
    REDIS_HOST: redis
    REDIS_PORT: 6379
    AUTH_SERVICE_JWKS_URI: http://auth-service:9000/.well-known/jwks.json
    TRANSACTION_SERVICE_GRPC_ADDRESS: static://transaction-service:9090
    REPORTING_SERVICE_URL: http://reporting-service:8086
```

### Redis infrastructure (new docker-compose service)

```yaml
redis:
  image: redis:7-alpine
  ports:
    - "6379:6379"
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
    interval: 5s
    timeout: 3s
    retries: 5
```

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| Reporting-service via internal REST (Principle V) | reporting-service (Phase 6) was built as a read-only REST service without a gRPC interface. Adding gRPC now would require reopening a completed service. | Reopening reporting-service would violate Principle IV (iterative one-step development). The data is read-only and non-financial, making REST vs gRPC consistency semantics negligible for this use case. This is tracked as technical debt: reporting-service v2 should expose gRPC. |

## Implementation Order (per Principle IV)

Complete each class in order. Do not start the next class until the current one has a passing test.

**Phase 1 — Setup & Infrastructure**
1. `build.gradle` — Spring Cloud Gateway BOM, all dependencies, gRPC protobuf plugin, JaCoCo gate
2. `ApiGatewayApplication.java` — `@SpringBootApplication`
3. `application.yml` + `application-test.yml` — routes, Redis, gRPC client, circuit breaker config
4. `logback-spring.xml` + `PanMaskingFilter.java` — PAN masking TurboFilter (same pattern as reporting-service)
5. `Dockerfile` — multi-stage eclipse-temurin:17
6. `settings.gradle` — add `:services:api-gateway` include
7. Docker Compose — add `redis` service + `api-gateway` service

**Phase 2 — Security**
8. `GatewayAuthExceptionHandler.java` + test — 401/403 JSON responses (ServerAuthenticationEntryPoint, ServerAccessDeniedHandler)
9. `SecurityConfig.java` + test — SecurityWebFilterChain, ReactiveJwtAuthenticationConverter (role + merchantId extraction), path-level authorization rules

**Phase 3 — Rate Limiting**
10. `RateLimitConfig.java` + test — JwtSubjectKeyResolver bean, RedisRateLimiter bean

**Phase 4 — Circuit Breaker + Fallback**
11. `CircuitBreakerConfig.java` + test — ReactiveCircuitBreakerFactory, Resilience4j registry
12. `FallbackController.java` + test — `/fallback/transaction` and `/fallback/reporting` → 503 JSON

**Phase 5 — Identity Propagation & Scoping Filters**
13. `IdentityPropagationFilter.java` + test — GlobalFilter: inject X-User-Sub, X-User-Role, X-Merchant-Id
14. `MerchantScopeEnforcementFilter.java` + test — GlobalFilter: validate {merchantId} path var matches JWT for MERCHANT role on merchant-specific routes

**Phase 6 — gRPC Routing**
15. `GrpcClientConfig.java` + test — ManagedChannel configured from grpc-client-spring-boot-starter, FutureStub bean
16. `GrpcTransactionGatewayFilterFactory.java` + test — AbstractGatewayFilterFactory; INITIATE operation (POST), GET operation, merchant ownership validation for GET, error mapping (gRPC StatusRuntimeException → HTTP status)

**Phase 7 — Audit Logging**
17. `AuditLoggingFilter.java` + test — GlobalFilter (Ordered.LOWEST_PRECEDENCE): logs path, method, response status for 401/403/429/503; verifies PAN not in log

**Phase 8 — Routes**
18. `RouteConfig.java` + test — RouteLocatorBuilder wiring all 7 routes with rate limiter, circuit breaker, gRPC filter

**Phase 9 — Kubernetes & Docker**
19. `k8s/api-gateway/deployment.yaml` + `k8s/api-gateway/service.yaml`

**Phase 10 — Integration Tests**
20. `ApiGatewayIntegrationTest.java` — full gateway with Testcontainers Redis + InProcessServer (gRPC mock) + WireMock (reporting HTTP mock); covers all Scenario Groups 1–8 from quickstart.md

## gRPC Error → HTTP Status Mapping

| gRPC Status | HTTP Status |
|-------------|-------------|
| `OK` | 200 / 201 |
| `NOT_FOUND` | 404 |
| `INVALID_ARGUMENT` | 400 |
| `PERMISSION_DENIED` | 403 |
| `UNAUTHENTICATED` | 401 |
| `UNAVAILABLE` | 503 (triggers circuit breaker) |
| `INTERNAL` | 500 |
| All others | 502 |

## JaCoCo Configuration

```groovy
jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = 'INSTRUCTION'
                value   = 'COVEREDRATIO'
                minimum = 1.0
            }
            limit {
                counter = 'BRANCH'
                value   = 'COVEREDRATIO'
                minimum = 1.0
            }
        }
    }
    afterEvaluate {
        classDirectories.setFrom(files(classDirectories.files.collect {
            fileTree(dir: it, exclude: ['com/creditcard/gateway/ApiGatewayApplication.class'])
        }))
    }
}
```

## Coverage Gap Watchlist (verify after each class)

| Class | Branch to watch |
|-------|----------------|
| `GrpcTransactionGatewayFilterFactory` | INITIATE vs GET operation branch; gRPC exception path; merchant mismatch (GET) |
| `IdentityPropagationFilter` | MERCHANT (with merchantId) vs ADMIN/SUPPORT (without merchantId) |
| `MerchantScopeEnforcementFilter` | path matches merchant route vs other routes; merchantId match vs mismatch |
| `SecurityConfig` JWT converter | role `MERCHANT` vs `ADMIN` vs `SUPPORT`; missing merchantId claim |
| `RateLimitConfig` KeyResolver | authenticated principal present vs absent |
| `AuditLoggingFilter` | status code 401, 403, 429, 503 vs 200 (not logged) |
