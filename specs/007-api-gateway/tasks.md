# Tasks: API Gateway

**Input**: Design documents from `specs/007-api-gateway/`

**Prerequisites**: plan.md ✅ | spec.md ✅ | research.md ✅ | data-model.md ✅ | contracts/rest-api-v1.md ✅ | quickstart.md ✅

**Tests**: TDD with 100% JaCoCo instruction/branch coverage is MANDATORY per spec. All test tasks MUST be written before their corresponding implementation. Confirm tests FAIL before implementing.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no unmet dependencies)
- **[Story]**: Which user story this task belongs to
- TDD: Write the test first, confirm it fails, then implement

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project scaffolding, build system, Docker, Kubernetes, logging infrastructure

- [X] T001 Create `services/api-gateway/build.gradle` with plugins (java, spring-boot 3.2.5, io.spring.dependency-management 1.1.4, com.google.protobuf 0.9.4, jacoco), Spring Cloud BOM 2023.0.1, all runtime deps (spring-cloud-starter-gateway, spring-boot-starter-oauth2-resource-server, spring-cloud-starter-circuitbreaker-resilience4j, spring-boot-starter-data-redis-reactive, net.devh:grpc-client-spring-boot-starter:3.1.0.RELEASE, io.grpc:grpc-protobuf:1.63.0, io.grpc:grpc-stub:1.63.0, com.google.protobuf:protobuf-java:3.25.3, micrometer-tracing-bridge-brave, zipkin-reporter-brave, logstash-logback-encoder:7.4), test deps (spring-boot-starter-test, io.grpc:grpc-testing:1.63.0, testcontainers:1.19.7, wiremock-jre8-standalone:2.35.1, nimbus-jose-jwt:9.37.3, spring-security-test), protobuf plugin pointing to proto/v1/, JaCoCo 0.8.11 gate at 100% instruction+branch excluding ApiGatewayApplication.class
- [X] Update `settings.gradle` to add `// Phase 7 — api-gateway (active)` comment and `include ':services:api-gateway'`
- [X] Create `services/api-gateway/src/main/resources/application.yml` with server.port: 8080, management.server.port: 8090, management health endpoints, spring.application.name: api-gateway, spring.data.redis config (REDIS_HOST, REDIS_PORT env vars), spring.security.oauth2.resourceserver.jwt.jwks-uri (AUTH_SERVICE_JWKS_URI env var), grpc.client.transaction-service config (TRANSACTION_SERVICE_GRPC_ADDRESS env var, negotiation-type: plaintext), resilience4j circuit breaker instances (transaction-service-cb and reporting-service-cb: slidingWindowSize 10, failureRateThreshold 50%, waitDurationInOpenState 30s, permittedNumberOfCallsInHalfOpenState 3), spring.cloud.gateway.routes with all 7 routes (transaction-submit, transaction-get, reporting-transactions, reporting-merchant-summary, reporting-all-merchants, reporting-settlement-batch, reporting-merchant-settlement) including RequestRateLimiter and CircuitBreaker filters, REPORTING_SERVICE_URL env var
- [X] [P] Create `services/api-gateway/src/main/resources/application-test.yml` overriding: spring.data.redis.host and port (set via @DynamicPropertySource), AUTH_SERVICE_JWKS_URI override for test RSA key, grpc.client.transaction-service.address set to in-process channel (inprocess:transaction-test), REPORTING_SERVICE_URL to WireMock URL, resilience4j waitDurationInOpenState: 1s (fast test), spring.cloud.gateway.default-filters empty override
- [X] [P] Create `services/api-gateway/src/main/resources/logback-spring.xml` with TurboFilter class com.creditcard.gateway.config.logging.PanMaskingFilter; `!test` profile: LogstashEncoder appender; test profile: PatternLayout encoder; root level INFO
- [X] Write `services/api-gateway/src/test/java/com/creditcard/gateway/config/logging/PanMaskingFilterTest.java` with 10 tests: PAN in format string blocked (13-digit, 19-digit), PAN in formatted params blocked, non-PAN number (12 digits) passes, null format returns NEUTRAL, PAN in first of multiple params blocked, PAN only in formatted result (not format string) blocked, safe log with masked number ****-****-****-1234 passes, null params array passes, mixed safe and PAN params: blocked if any param contains PAN; then implement `services/api-gateway/src/main/java/com/creditcard/gateway/config/logging/PanMaskingFilter.java` (Logback TurboFilter, same pattern as reporting-service: Pattern.compile("\\b\\d{13,19}\\b"), check format string then MessageFormatter.arrayFormat result)
- [X] [P] Create `services/api-gateway/src/main/java/com/creditcard/gateway/ApiGatewayApplication.java` with @SpringBootApplication
- [X] [P] Create `services/api-gateway/Dockerfile` using multi-stage build: eclipse-temurin:17-jdk-alpine for build (gradle bootJar), eclipse-temurin:17-jre-alpine for runtime; securityContext runAsNonRoot; EXPOSE 8080 8090
- [X] Add `redis` service to `docker-compose.yml` (image: redis:7-alpine, ports: 6379:6379, healthcheck: redis-cli ping, interval 5s retries 5); add to `volumes:` section if needed
- [X] T010 [P] Add `api-gateway` service to `docker-compose.yml` (build: context: ., dockerfile: services/api-gateway/Dockerfile; depends_on: redis service_healthy, transaction-service service_started, reporting-service service_started; ports: 8087:8080 and 8088:8090; environment: REDIS_HOST, REDIS_PORT, AUTH_SERVICE_JWKS_URI, TRANSACTION_SERVICE_GRPC_ADDRESS: static://transaction-service:9090, REPORTING_SERVICE_URL: http://reporting-service:8086)
- [X] T011 [P] Create `k8s/api-gateway/deployment.yaml` (apiVersion apps/v1, 2 replicas, selector app: api-gateway, securityContext runAsNonRoot runAsUser 1000, container port 8080 and 8090, envFrom secretRef: api-gateway-secrets, resources requests cpu 250m memory 256Mi limits cpu 500m memory 512Mi, livenessProbe GET /actuator/health/liveness port 8090 initialDelay 30 period 10, readinessProbe GET /actuator/health/readiness port 8090 initialDelay 15 period 5)
- [X] T012 [P] Create `k8s/api-gateway/service.yaml` (ClusterIP, selector app: api-gateway, port 8080 name http, targetPort 8080)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Security, shared filters, gRPC client, rate limit beans, circuit breaker beans, fallback, audit logging — ALL user stories depend on every item in this phase.

**⚠️ CRITICAL**: No user story work begins until this phase is complete. Write each test first, confirm it fails, then implement.

- [X] T013 Write `services/api-gateway/src/test/java/com/creditcard/gateway/exception/GatewayAuthExceptionHandlerTest.java`: 4 tests: (1) onAuthenticationException returns 401 with JSON body `{"error":"Unauthorized","message":"A valid bearer token is required."}` and Content-Type application/json; (2) onAccessDeniedException returns 403 with JSON body `{"error":"Forbidden","message":"Your role does not have permission to perform this action."}`; (3) 401 body is valid JSON (not empty); (4) 403 body is valid JSON; use MockServerWebExchange
- [X] T014 Implement `services/api-gateway/src/main/java/com/creditcard/gateway/exception/GatewayAuthExceptionHandler.java` (@Component implements ServerAuthenticationEntryPoint and ServerAccessDeniedHandler; both methods write JSON to ServerHttpResponse with correct status codes and Content-Type; use DataBufferFactory to encode response body)
- [X] T015 Write `services/api-gateway/src/test/java/com/creditcard/gateway/config/SecurityConfigTest.java`: 8 tests using @WebFluxTest + SecurityMockServerConfigurers.mockJwt(): (1) request without JWT → 401; (2) expired JWT → 401; (3) MERCHANT JWT → POST /api/v1/transactions → not 401/403 (passes security); (4) SUPPORT JWT → POST /api/v1/transactions → 403; (5) ADMIN JWT → GET /api/v1/reporting/merchants/summary → passes; (6) MERCHANT JWT → GET /api/v1/reporting/merchants/summary → 403; (7) any JWT → GET /actuator/health → 200 (permit all); (8) JWT converter test: MERCHANT JWT with merchantId claim → GrantedAuthority contains ROLE_MERCHANT + merchantId extracted into custom principal
- [X] T016 Implement `services/api-gateway/src/main/java/com/creditcard/gateway/config/SecurityConfig.java` (@EnableWebFluxSecurity; SecurityWebFilterChain with: csrf disabled; authorizeExchange: POST /api/v1/transactions hasAnyRole MERCHANT ADMIN; GET /api/v1/transactions/** authenticated; GET /api/v1/reporting/merchants/summary hasAnyRole ADMIN SUPPORT; GET /api/v1/reporting/merchants/*/summary authenticated; GET /api/v1/reporting/settlements/** hasAnyRole ADMIN SUPPORT; GET /api/v1/reporting/transactions authenticated; /actuator/** permitAll; /fallback/** permitAll; anyExchange authenticated; oauth2ResourceServer reactive JWT with ReactiveJwtAuthenticationConverter that maps authorities claim to SimpleGrantedAuthority and extracts merchantId into custom GatewayPrincipal; exceptionHandling wires GatewayAuthExceptionHandler for both 401 and 403)
- [X] T017 [P] Write `services/api-gateway/src/test/java/com/creditcard/gateway/filter/IdentityPropagationFilterTest.java`: 5 tests using MockServerWebExchange + mock JwtAuthenticationToken: (1) MERCHANT role → X-User-Sub, X-User-Role=MERCHANT, X-Merchant-Id all set; (2) ADMIN role → X-User-Sub set, X-User-Role=ADMIN, X-Merchant-Id NOT set; (3) SUPPORT role → X-User-Sub set, X-User-Role=SUPPORT, X-Merchant-Id NOT set; (4) unauthenticated (no principal) → no headers injected, chain continues; (5) MERCHANT with merchantId=merchant-001 → X-Merchant-Id=merchant-001 exactly
- [X] T018 Implement `services/api-gateway/src/main/java/com/creditcard/gateway/filter/IdentityPropagationFilter.java` (implements GlobalFilter, Ordered at HIGHEST_PRECEDENCE+50; extract principal from SecurityContext; if JwtAuthenticationToken set X-User-Sub from sub claim, X-User-Role from highest-priority role, X-Merchant-Id from merchantId claim if present; mutate request with new headers; call chain.filter)
- [X] T019 [P] Write `services/api-gateway/src/test/java/com/creditcard/gateway/config/GrpcClientConfigTest.java`: 3 tests: (1) GrpcClientConfig bean loads without exception when grpc-client-spring-boot-starter is on classpath; (2) transactionServiceFutureStub bean is not null; (3) stub is instance of TransactionServiceGrpc.TransactionServiceFutureStub (verify via @SpringBootTest with @ActiveProfiles("test"))
- [X] T020 Implement `services/api-gateway/src/main/java/com/creditcard/gateway/config/GrpcClientConfig.java` (@Configuration; @GrpcClient("transaction-service") injects ManagedChannel; @Bean TransactionServiceGrpc.TransactionServiceFutureStub transactionServiceFutureStub(ManagedChannel channel) — uses the channel injected by grpc-client-spring-boot-starter; channel address and TLS configured via grpc.client.transaction-service.address in application.yml)
- [X] T021 [P] Write `services/api-gateway/src/test/java/com/creditcard/gateway/config/RateLimitConfigTest.java`: 4 tests: (1) jwtSubjectKeyResolver returns Mono.just(sub) when principal is JwtAuthenticationToken with sub=user-123; (2) jwtSubjectKeyResolver returns Mono.empty() when no principal (unauthenticated); (3) jwtSubjectKeyResolver returns Mono.empty() when principal is not JwtAuthenticationToken; (4) redisRateLimiter bean configures replenishRate and burstCapacity from application.yml values (verify defaults)
- [X] T022 Implement `services/api-gateway/src/main/java/com/creditcard/gateway/config/RateLimitConfig.java` (@Configuration; @Bean KeyResolver jwtSubjectKeyResolver: extracts sub from SecurityContext principal if JwtAuthenticationToken, else Mono.empty(); @Bean RedisRateLimiter with default replenishRate and burstCapacity from @Value properties; relies on ReactiveRedisTemplate from spring-data-redis-reactive auto-config)
- [X] T023 [P] Write `services/api-gateway/src/test/java/com/creditcard/gateway/config/CircuitBreakerConfigTest.java`: 3 tests: (1) ReactiveCircuitBreakerFactory bean loads without NPE; (2) getCircuitBreaker("transaction-service-cb") returns a non-null ReactiveCircuitBreaker; (3) getCircuitBreaker("reporting-service-cb") returns a non-null ReactiveCircuitBreaker (use @SpringBootTest + @ActiveProfiles("test"))
- [X] T024 Implement `services/api-gateway/src/main/java/com/creditcard/gateway/config/CircuitBreakerConfig.java` (@Configuration; @Bean ReactiveCircuitBreakerFactory configured with Resilience4jReactiveBulkheadProvider or ReactiveResilience4JCircuitBreakerFactory; customize with resilience4j.circuitbreaker.instances.transaction-service-cb and reporting-service-cb from application.yml; expose ReactiveCircuitBreakerFactory bean for injection into GrpcTransactionGatewayFilterFactory)
- [X] T025 [P] Write `services/api-gateway/src/test/java/com/creditcard/gateway/fallback/FallbackControllerTest.java`: 4 tests with @WebFluxTest(FallbackController.class): (1) GET /fallback/transaction → 503 JSON with body {"error":"Service Unavailable","message":"Transaction service temporarily unavailable. Please retry shortly."}; (2) GET /fallback/reporting → 503 JSON with body {"error":"Service Unavailable","message":"Reporting service temporarily unavailable. Please retry shortly."}; (3) response Content-Type is application/json for both; (4) GET /fallback/unknown → 503 JSON with generic message
- [X] T026 Implement `services/api-gateway/src/main/java/com/creditcard/gateway/fallback/FallbackController.java` (@RestController; @GetMapping("/fallback/{service}") returns ResponseEntity 503 with Map body {"error": "Service Unavailable", "message": "<service-specific message or generic>"}; @ResponseStatus not used — explicit ResponseEntity to control status code)
- [X] T027 [P] Write `services/api-gateway/src/test/java/com/creditcard/gateway/filter/AuditLoggingFilterTest.java`: 6 tests: (1) response with status 401 → logs WARN with path, method, status, userSub; (2) response with status 403 → logs WARN with path, method, status; (3) response with status 429 → logs WARN; (4) response with status 503 → logs WARN; (5) response with status 200 → no WARN log emitted (check using in-memory appender or captor); (6) log entries do NOT contain card number pattern \b\d{13,19}\b in any field (verify with request that had a card number in path or header)
- [X] T028 Implement `services/api-gateway/src/main/java/com/creditcard/gateway/filter/AuditLoggingFilter.java` (implements GlobalFilter, Ordered at Ordered.LOWEST_PRECEDENCE so it observes the response after all other filters; intercepts post-filter phase via ServerWebExchange mutate + decorating ServerHttpResponse to observe status code; if status is 401/403/429/503 → log.warn structured entry with path, method, status, user sub from X-User-Sub header; do not log request body; do not log any value matching PAN pattern)

**Checkpoint**: All foundational beans and filters exist. User story implementation can now begin.

---

## Phase 3: User Story 1 — Merchants Submit Payment Transactions (Priority: P1) 🎯 MVP

**Goal**: A MERCHANT or ADMIN with a valid JWT can submit a payment transaction (POST /api/v1/transactions) and retrieve its status (GET /api/v1/transactions/{id}). Requests are translated to gRPC calls to transaction-service.

**Independent Test**: Start the gateway with an in-process gRPC mock server. POST a valid transaction request with a MERCHANT JWT → verify HTTP 201 with transactionId+PENDING. GET the same ID → verify 200 with masked card number. POST without JWT → verify 401.

### Tests for User Story 1 ⚠️ Write these FIRST — confirm they FAIL before T031

- [X] T029 [US1] Write `services/api-gateway/src/test/java/com/creditcard/gateway/filter/GrpcTransactionGatewayFilterFactoryTest.java`: 9 unit tests using MockServerWebExchange + Mockito mock TransactionServiceGrpc.TransactionServiceFutureStub: (1) apply(INITIATE) reads body → calls initiateTransaction stub → writes 201 JSON {transactionId, status=PENDING}; (2) apply(GET) reads path var {id} → calls getTransaction stub → writes 200 JSON with all response fields; (3) apply(GET) with stub returning different merchantId than X-Merchant-Id header (MERCHANT role) → writes 403 JSON without calling chain; (4) apply(GET) with ADMIN role + different merchantId → 200 (no scoping for ADMIN); (5) stub throws StatusRuntimeException(UNAVAILABLE) → Mono.error propagated (not caught — let CircuitBreaker filter catch it); (6) stub throws StatusRuntimeException(NOT_FOUND) → 404 JSON response; (7) stub throws StatusRuntimeException(INVALID_ARGUMENT) → 400 JSON response; (8) apply(INITIATE) with invalid body (non-JSON) → 400 JSON response; (9) operation=UNKNOWN → passes to chain.filter

- [X] T030 [US1] Implement `services/api-gateway/src/main/java/com/creditcard/gateway/filter/GrpcTransactionGatewayFilterFactory.java` (extends AbstractGatewayFilterFactory<GrpcTransactionGatewayFilterFactory.Config> with Config.operation String field; INITIATE operation: collect request body → ObjectMapper deserialize to TransactionRequestDto → build InitiateTransactionRequest proto → Mono.fromFuture(futureStub.initiateTransaction(req)) → map InitiateTransactionResponse to JSON → write to exchange response with 201 Created + Content-Type JSON + setComplete(); GET operation: extract {id} from path → build GetTransactionRequest → Mono.fromFuture(futureStub.getTransaction(req)) → if MERCHANT role AND returned merchantId ≠ X-Merchant-Id header → write 403 → else map GetTransactionResponse to JSON → write 200 + setComplete(); gRPC error mapping: UNAVAILABLE/INTERNAL → Mono.error(e) [propagates to CircuitBreaker]; NOT_FOUND → 404; INVALID_ARGUMENT → 400; PERMISSION_DENIED → 403; default → 502; inner DTOs: TransactionRequestDto, TransactionInitiateResponseDto, TransactionGetResponseDto; inject TransactionServiceGrpc.TransactionServiceFutureStub and ObjectMapper)

- [X] T031 [US1] Write `services/api-gateway/src/test/java/com/creditcard/gateway/config/RouteConfigTest.java`: 7 route registration tests using @WebFluxTest + RouteLocatorBuilder: (1) route transaction-submit exists with Path=/api/v1/transactions Method=POST; (2) route transaction-get exists with Path=/api/v1/transactions/{id} Method=GET; (3) route reporting-transactions exists with Path=/api/v1/reporting/transactions; (4) route reporting-merchant-summary with Path=/api/v1/reporting/merchants/{merchantId}/summary; (5) route reporting-all-merchants with Path=/api/v1/reporting/merchants/summary; (6) route reporting-settlement-batch with Path=/api/v1/reporting/settlements/{batchId}; (7) route reporting-merchant-settlement with Path=/api/v1/reporting/settlements/{batchId}/merchants/{merchantId}; verify each reporting route has RewritePath filter mapping /api/v1/reporting/** → /internal/reporting/**

- [X] T032 [US1] Implement `services/api-gateway/src/main/java/com/creditcard/gateway/config/RouteConfig.java` (@Configuration; @Bean RouteLocator using RouteLocatorBuilder; route transaction-submit: predicate Path=/api/v1/transactions + Method=POST, uri=http://localhost (unused — GrpcTransaction filter short-circuits), filters: RequestRateLimiter(replenishRate=10 burstCapacity=20 key-resolver=#{@jwtSubjectKeyResolver}), CircuitBreaker(name=transaction-service-cb fallbackUri=forward:/fallback/transaction), GrpcTransaction(operation=INITIATE); route transaction-get: predicate Path=/api/v1/transactions/{id} + Method=GET, same filters with replenishRate=50 burstCapacity=100, GrpcTransaction(operation=GET); 5 reporting routes: uri=${REPORTING_SERVICE_URL}, filters: RewritePath(/api/v1/reporting/(?<segment>.*) → /internal/reporting/${segment}), RequestRateLimiter(replenishRate=30 burstCapacity=60), CircuitBreaker(name=reporting-service-cb fallbackUri=forward:/fallback/reporting); all routes use jwtSubjectKeyResolver KeyResolver)

**Checkpoint**: Transaction submission and retrieval work end-to-end with JWT auth, gRPC routing, rate limiting, and circuit breaking. Test independently with InProcessServer gRPC mock.

---

## Phase 4: User Story 2 — Users Access Transaction History and Settlement Reports (Priority: P1)

**Goal**: MERCHANT (own data only), ADMIN, and SUPPORT users can query transaction history and settlement reports. Merchants are prevented from accessing other merchants' data via path variable checks.

**Independent Test**: Use WireMock to mock reporting-service. MERCHANT JWT with merchantId=m-001 queries /api/v1/reporting/merchants/m-002/summary → 403 (path var mismatch). MERCHANT queries /api/v1/reporting/merchants/m-001/summary → WireMock receives /internal/reporting/merchants/m-001/summary → 200. ADMIN queries /api/v1/reporting/merchants/summary → WireMock receives /internal/reporting/merchants/summary → 200.

### Tests for User Story 2 ⚠️ Write these FIRST — confirm they FAIL before T034

- [X] T033 [US2] Write `services/api-gateway/src/test/java/com/creditcard/gateway/filter/MerchantScopeEnforcementFilterTest.java`: 7 tests using MockServerWebExchange + mock JwtAuthenticationToken: (1) MERCHANT role, path /api/v1/reporting/merchants/merchant-001/summary, X-Merchant-Id=merchant-001 → 200 (match, pass through); (2) MERCHANT role, path /api/v1/reporting/merchants/merchant-002/summary, X-Merchant-Id=merchant-001 → 403 (mismatch, short-circuit); (3) ADMIN role, path /api/v1/reporting/merchants/merchant-002/summary → pass through (no scoping for ADMIN); (4) SUPPORT role → pass through (no scoping); (5) path /api/v1/reporting/merchants/merchant-001/settlement/batch-1/merchants/merchant-002 (different merchantId in path) with MERCHANT role → 403; (6) path not matching any merchant-scoped pattern → pass through regardless of role; (7) MERCHANT role, /api/v1/reporting/transactions (no {merchantId} path var) → pass through

- [X] T034 [US2] Implement `services/api-gateway/src/main/java/com/creditcard/gateway/filter/MerchantScopeEnforcementFilter.java` (implements GlobalFilter, Ordered at HIGHEST_PRECEDENCE+60 — runs after IdentityPropagationFilter; extract X-Merchant-Id and X-User-Role headers from request; define merchant-scoped route patterns: /api/v1/reporting/merchants/{merchantId}/summary and /api/v1/reporting/settlements/{batchId}/merchants/{merchantId}; if role is MERCHANT and path matches a scoped pattern, extract {merchantId} from path using UriTemplate/AntPathMatcher and compare to X-Merchant-Id header; if mismatch → write 403 JSON to response and return setComplete(); otherwise call chain.filter)

**Checkpoint**: All 7 reporting routes work correctly with merchant data scoping enforced. MERCHANT users cannot access other merchants' data.

---

## Phase 5: User Story 3 — Per-Client Usage Limits Prevent Abuse (Priority: P2)

**Goal**: Each client (identified by JWT sub) is limited to a per-route request quota. Exceeding the quota returns 429 with X-RateLimit-Retry-After-Seconds header. Different clients have independent limits.

**Independent Test**: Using Testcontainers Redis + full gateway, send 21 POST /api/v1/transactions in <1 second with burst=20 configured. Verify first 20 succeed, 21st returns 429 with retry header. Send 1 request from a different client (different JWT sub) → succeeds.

### Tests for User Story 3 ⚠️ Write these FIRST — confirm they FAIL before T036

- [X] T035 [US3] Write rate limiting integration scenarios in `services/api-gateway/src/test/java/com/creditcard/gateway/integration/RateLimitIntegrationTest.java` with @SpringBootTest(webEnvironment=RANDOM_PORT) + @Testcontainers + RedisContainer (redis:7-alpine) + InProcessServer gRPC mock: (1) single client within quota (10 requests, burst=20) → all 200/201; (2) single client exceeds burst (21 requests) → first 20 succeed, 21st → 429 with header X-RateLimit-Retry-After-Seconds; (3) two clients (different JWT sub) each send 20 requests → both succeed (independent buckets); (4) client receives 429, waits for window reset (assert header value), then retries → 200; (5) rate limiting on reporting routes (separate limit bucket) → verified independently from transaction routes

- [X] T036 [US3] No new source files — rate limiting is fully implemented via RateLimitConfig (T022) + RouteConfig (T032) + RedisRateLimiter. Verify by running T035 test suite passes. If any test fails, fix RateLimitConfig or RouteConfig. Update this task to [X] only after all T035 scenarios pass.

**Checkpoint**: Per-client rate limiting works correctly in a real Redis environment. Each client has independent quota buckets per route.

---

## Phase 6: User Story 4 — Gateway Remains Responsive When Downstream Services Fail (Priority: P2)

**Goal**: When transaction-service or reporting-service is unavailable, the gateway returns a timely 503 fallback response (within 2 seconds). After the service recovers, the gateway automatically resumes normal routing.

**Independent Test**: Using full gateway + InProcessServer configured to return UNAVAILABLE → send 6 requests to open the circuit → verify 503 response from FallbackController. Wait for waitDurationInOpenState (1s in test config) → send 1 request with gRPC mock now returning success → verify 201 (circuit transitioned to HALF_OPEN then CLOSED).

### Tests for User Story 4 ⚠️ Write these FIRST — confirm they FAIL before T038

- [X] T037 [US4] Write circuit breaker integration scenarios in `services/api-gateway/src/test/java/com/creditcard/gateway/integration/CircuitBreakerIntegrationTest.java` with @SpringBootTest + @Testcontainers (Redis) + InProcessServer gRPC mock + WireMock: (1) gRPC mock returns UNAVAILABLE → 6 consecutive requests → circuit opens → subsequent requests return 503 JSON from FallbackController (not hanging); (2) 503 response arrives within 2 seconds; (3) after waitDurationInOpenState=1s (test config), gRPC mock returns success → 1 request → 201 (circuit recovers); (4) reporting-service WireMock returns 503 → 6 requests → reporting circuit opens → subsequent reporting requests return 503 from /fallback/reporting; (5) reporting circuit recovers when WireMock returns 200; (6) transaction circuit OPEN does NOT affect reporting circuit (they are independent)

- [X] T038 [US4] No new source files — circuit breaker is fully implemented via CircuitBreakerConfig (T024) + FallbackController (T026) + RouteConfig (T032) + GrpcTransactionGatewayFilterFactory error propagation (T030). Verify by running T037 test suite. Update this task to [X] only after all T037 scenarios pass.

**Checkpoint**: Gateway returns 503 within 2 seconds when downstream is unavailable and automatically recovers. Circuits are independent per service.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Comprehensive integration test covering all 8 quickstart.md scenario groups, final coverage verification

- [X] T039 Write comprehensive `services/api-gateway/src/test/java/com/creditcard/gateway/integration/ApiGatewayIntegrationTest.java` with @SpringBootTest(webEnvironment=RANDOM_PORT) + @Testcontainers + GenericContainer("redis:7-alpine") + @DynamicPropertySource (Redis URL, gRPC address → InProcessServer "transaction-test", reporting URL → WireMock base URL) + TestRestTemplate/WebTestClient; implement all 8 scenario groups from quickstart.md: Group 1 (JWT auth: 1.1-1.4), Group 2 (RBAC: 2.1-2.5), Group 3 (merchant scoping: 3.1-3.5), Group 4 (rate limiting: 4.1-4.3), Group 5 (circuit breaker: 5.1-5.3), Group 6 (identity headers: 6.1-6.2), Group 7 (audit logging: 7.1-7.2 including PAN absence check), Group 8 (health: 8.1-8.2); helper: generate test RSA JWK and serve JWKS via WireMock; helper: build JWT with arbitrary role/merchantId/sub claims signed with test key
- [X] T040 [P] Run `./gradlew :services:api-gateway:test jacocoTestCoverageVerification` and verify BUILD SUCCESSFUL with 100% instruction and branch coverage; if any gap found, add targeted test to the appropriate unit test class (refer to Coverage Gap Watchlist in plan.md); update this task [X] only after the command exits successfully with zero coverage violations

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Setup (specifically T001-T005); BLOCKS all user story work
- **US1 (Phase 3)**: Depends on Foundational completion — T029 requires GrpcClientConfig (T020) to be compilable
- **US2 (Phase 4)**: Depends on US1 (Phase 3) completion — uses RouteConfig which is defined in T032
- **US3 (Phase 5)**: Depends on Foundational (RateLimitConfig) + US1 (RouteConfig routes with RequestRateLimiter)
- **US4 (Phase 6)**: Depends on Foundational (CircuitBreakerConfig, FallbackController) + US1 (RouteConfig with CircuitBreaker)
- **Polish (Phase 7)**: Depends on all prior phases

### User Story Dependencies

- **US1 (P1)**: Can start after Phase 2 complete. No dependency on US2/US3/US4.
- **US2 (P1)**: Can start after US1 complete (shares RouteConfig). Independent from US3/US4.
- **US3 (P2)**: Can start after Phase 2 complete (RateLimitConfig done) + US1 complete (RouteConfig with rate limiter). No dependency on US2/US4.
- **US4 (P2)**: Can start after Phase 2 complete (CB config done) + US1 complete (RouteConfig with CB). No dependency on US2/US3.

### Within Each Phase

- **Tests MUST be written before implementation** (TDD). Confirm tests FAIL before implementing.
- Within Phase 2: T013→T014→T015→T016 (sequential, security core); T017-T028 can start after T016 (parallel pairs)
- Within Phase 3: T029→T030 (test then impl for GrpcFilter); T031→T032 (test then impl for RouteConfig)
- Each test+impl pair within Phase 2 parallel group: write test → confirm failure → implement

### Parallel Opportunities

Within Phase 1: T004, T005, T007, T008, T010, T011, T012 can all run in parallel after T001-T003.
Within Phase 2 (after T016): Each filter pair is independent — IdentityPropagation, GrpcClient, RateLimit, CircuitBreaker, Fallback, AuditLogging pairs can run in parallel with each other.
US3 (T035-T036) and US4 (T037-T038) can run in parallel with each other after US1 is complete.

---

## Parallel Example: Phase 2 (After T016)

```bash
# These 6 pairs can be developed simultaneously after SecurityConfig is complete:
Pair A: T017 (IdentityPropagationFilterTest) → T018 (IdentityPropagationFilter)
Pair B: T019 (GrpcClientConfigTest) → T020 (GrpcClientConfig)
Pair C: T021 (RateLimitConfigTest) → T022 (RateLimitConfig)
Pair D: T023 (CircuitBreakerConfigTest) → T024 (CircuitBreakerConfig)
Pair E: T025 (FallbackControllerTest) → T026 (FallbackController)
Pair F: T027 (AuditLoggingFilterTest) → T028 (AuditLoggingFilter)
```

---

## Implementation Strategy

### MVP First (US1 Only — Transaction Submission + Retrieval)

1. Complete Phase 1 (Setup)
2. Complete Phase 2 (Foundational)
3. Complete Phase 3 (US1: GrpcTransactionGatewayFilterFactory + RouteConfig)
4. **STOP and VALIDATE**: POST /api/v1/transactions with MERCHANT JWT + in-process gRPC mock → 201. GET /api/v1/transactions/{id} → 200. No JWT → 401. SUPPORT JWT → 403.
5. Run `./gradlew :services:api-gateway:test` → must pass

### Incremental Delivery

1. Setup + Foundational → all infrastructure ready
2. US1 → Transaction routing works (gateway is functional for core payment flow)
3. US2 → Reporting access works (full read-side complete)
4. US3 → Rate limiting verified in Redis environment
5. US4 → Circuit breaker verified (gateway resilient to outages)
6. Polish → 100% coverage gate confirmed

---

## Notes

- `no://op` or `http://localhost` is used as the dummy route URI for gRPC-backed routes — the GrpcTransaction filter short-circuits before any actual HTTP routing occurs
- `@Immutable` is NOT used here (no JPA entities in this service)
- grpc-client-spring-boot-starter 3.1.0.RELEASE: the `@GrpcClient` annotation on a `ManagedChannel` parameter or field injects the configured channel. Use `TransactionServiceGrpc.newFutureStub(channel)` to create the stub.
- For test JWT generation: use `com.nimbusds.jose.jwk.gen.RsaJwkGenerator.generateJwk(2048)` to create a test RSA key; serve the JWKS JSON via WireMock at the path configured in application-test.yml's AUTH_SERVICE_JWKS_URI
- InProcessServer for gRPC tests: `InProcessServerBuilder.forName("transaction-test").addService(mockServiceImpl).build().start()` + `InProcessChannelBuilder.forName("transaction-test").directExecutor().build()`
- AuditLoggingFilter runs at `Ordered.LOWEST_PRECEDENCE` so it observes the final response status AFTER all other filters (including Spring Security) have set it
- PAN masking: `\b\d{13,19}\b` regex must block any log message containing a raw card number; always verify no PAN appears in any log output during integration tests
