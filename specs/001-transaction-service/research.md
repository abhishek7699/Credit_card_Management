# Research: Transaction Service

**Phase**: 0 — Pre-implementation research
**Date**: 2026-06-11
**Feeds into**: plan.md, data-model.md, contracts/

---

## 1. gRPC Library for Spring Boot 3.x

**Decision**: Use `net.devh:grpc-spring-boot-starter` v3.x (LogNet)

**Rationale**: LogNet's starter is the most widely adopted gRPC integration for Spring Boot.
v3.x supports Spring Boot 3.x / Spring 6 / Jakarta EE. It provides `@GrpcService` to
register gRPC service implementations as Spring beans, auto-configures the Netty gRPC server
on a configurable port (default 9090), and integrates with Spring's dependency injection,
health indicators, and graceful shutdown. Alternative (`grpc-java` directly) requires manual
server lifecycle management — no benefit in a Spring Boot context.

**Alternatives considered**:
- `io.grpc` direct: More control, but requires manual server boot/shutdown wiring. Adds
  boilerplate with no benefit for a Spring Boot service.
- Quarkus gRPC: Not applicable — project stack is Spring Boot.

**Key dependency versions** (resolved from Spring Boot 3.2.x BOM):
```
net.devh:grpc-spring-boot-starter:3.1.0.RELEASE
io.grpc:grpc-protobuf:1.62.x
io.grpc:grpc-stub:1.62.x
com.google.protobuf:protobuf-java:3.25.x
```

**Gradle plugin for proto stub generation**:
```groovy
plugins {
    id 'com.google.protobuf' version '0.9.4'
}
protobuf {
    protoc { artifact = 'com.google.protobuf:protoc:3.25.x' }
    plugins { grpc { artifact = 'io.grpc:protoc-gen-grpc-java:1.62.x' } }
    generateProtoTasks {
        all()*.plugins { grpc {} }
    }
}
```

Generated stubs land in `build/generated/source/proto/main/` and are added to the source
set automatically. Stubs are NOT committed to the repository (Principle VII).

---

## 2. HashiCorp Vault Transit Secrets Engine Integration

**Decision**: Use Spring Vault (`org.springframework.vault:spring-vault-core:3.1.x`) with
`VaultTemplate.opsForTransit()` for encrypt/decrypt operations.

**Rationale**: Spring Vault is the official Spring integration for HashiCorp Vault. It
provides idiomatic Spring abstractions (`VaultTemplate`, `@VaultPropertySource`) and manages
Vault token renewal. The Transit Secrets Engine provides AES-256-GCM encryption-as-a-service
— the raw PAN is never stored by the Vault server in a retrievable plaintext form; it is
encrypted and the ciphertext is returned. The ciphertext IS the opaque card token we store.

**How Vault Transit tokenization works**:
1. Create a named encryption key in Vault: `vault write -f transit/keys/card-pan-key`
2. Encrypt a PAN: POST `/v1/transit/encrypt/card-pan-key` with base64-encoded PAN
   → response contains `data.ciphertext` (e.g., `vault:v1:8SDd3WHDOjf7mq69CyCqYjBXA...`)
3. The ciphertext string IS the card token stored in our database. It begins with `vault:v1:`
   which identifies it as a Vault-encrypted value.
4. To reveal the original PAN (for future auth lookup): POST `/v1/transit/decrypt/card-pan-key`
   → returns base64-decoded original PAN (only possible by calling Vault — the transaction
   service has no decryption capability by itself)

**Important**: The transaction-service will ONLY call ENCRYPT during payment submission.
It will NEVER call DECRYPT — that responsibility belongs to the authorization-service
(which needs to pass the real PAN to the card network), not the transaction-service.

**Spring Vault integration pattern**:
```java
@Service
public class TokenizationService {
    private final VaultOperations vaultOperations;
    private final String keyName = "card-pan-key"; // from config

    public String tokenize(String rawPan) {
        VaultTransitContext context = VaultTransitContext.empty();
        String ciphertext = vaultOperations.opsForTransit()
            .encrypt(keyName, VaultTransitRequest.create(rawPan));
        return ciphertext; // store this as the card token
    }
}
```

**Vault auth method for the service**: AppRole authentication (recommended for machine-to-machine).
In local Docker: dev mode (token-based for simplicity). In K8s: Vault Agent Injector or
Kubernetes auth method.

**Alternatives considered**:
- Direct Vault HTTP client (`okhttp3`): More control, but no Spring lifecycle management or
  token renewal. Adds complexity.
- AWS KMS / GCP KMS: Viable but constitutes a constitution amendment (not HashiCorp Vault).

---

## 3. CockroachDB with Spring Data JPA

**Decision**: Spring Data JPA with `org.postgresql:postgresql` JDBC driver. CockroachDB
Serverless speaks the PostgreSQL wire protocol — no special CockroachDB driver needed.

**Key considerations**:
- **Serialization retries**: CockroachDB uses optimistic concurrency control. Concurrent
  transactions on the same rows may fail with `SQLSTATE 40001` (serialization failure).
  Spring's `@Transactional` with `@Retryable` (Spring Retry) handles this transparently.
  Add `spring-retry` and annotate `@Retryable(retryFor = DataAccessException.class)` on
  service methods that write to the DB.
- **Dialect**: Use `org.hibernate.dialect.PostgreSQLDialect` (Hibernate 6 with Spring Boot 3).
  CockroachDB is compatible with PostgreSQL 13+.
- **UUID primary keys**: CockroachDB natively supports `UUID PRIMARY KEY DEFAULT gen_random_uuid()`.
  Use `@GeneratedValue` with `UUIDGenerator` in JPA to avoid sequence contention.
- **Timestamps**: Use `TIMESTAMPTZ` columns mapped to `java.time.Instant` in JPA entities.
- **Connection pool**: Hikari (default in Spring Boot). `maximumPoolSize` = 10 per service
  instance (CockroachDB Serverless has connection limits).
- **Idempotency atomicity**: The `processed_events` INSERT and the `transactions` UPDATE must
  execute in the same CockroachDB transaction. Spring `@Transactional` ensures this.

**Testcontainers for CockroachDB**:
```java
@Container
static CockroachDBContainer cockroachDB =
    new CockroachDBContainer("cockroachdb/cockroach:v23.2.x");
```
`testcontainers:cockroachdb` module is available. Alternatively, use
`testcontainers:postgresql` with `cockroachdb/cockroach:v23.2.x` image directly.

---

## 4. Kafka Configuration for Spring Boot 3.x

**Decision**: Spring Kafka (`org.springframework.kafka:spring-kafka`) with manual topic
configuration and explicit serializer/deserializer setup.

**Producer (TransactionInitiated event)**:
- Topic: `transaction.initiated`
- Serializer: JSON (`JsonSerializer`) with typed `TransactionInitiatedEvent` POJO
- Key: `transactionId` (UUID string) — ensures event ordering per transaction
- Acks: `all` — required for financial events (ensures leader and replicas acknowledge)
- Idempotent producer: `enable.idempotence=true` to prevent duplicate publishes on retry

**Consumer (status update events)**:
- Topic: `transaction.status.updated`
- Group ID: `transaction-service-status-consumer`
- Deserializer: JSON (`ErrorHandlingDeserializer` wrapping `JsonDeserializer`)
- `auto.offset.reset`: `earliest` — process from the beginning on new consumer group
- Manual acknowledgment (`AckMode.MANUAL_IMMEDIATE`) — ACK only after idempotency check
  and business state committed to CockroachDB atomically

**Idempotency key pattern** (Principle III):
```
eventKey = "tx:" + eventType + ":" + eventId
```
Example: `"tx:TransactionAuthorized:550e8400-e29b-41d4-a716-446655440000"`

Consumer logic:
1. Check `processed_events` table for `eventKey`
2. If found → log at DEBUG level, acknowledge Kafka message, return
3. If not found → apply business state change + INSERT `processed_events` row in one `@Transactional`

**Testcontainers for Kafka**:
```java
@Container
static KafkaContainer kafka = new KafkaContainer(
    DockerImageName.parse("confluentinc/cp-kafka:7.6.x"));
```

---

## 5. Distributed Tracing

**Decision**: Micrometer Tracing with Brave bridge (`micrometer-tracing-bridge-brave`).

**Trace propagation**:
- gRPC: LogNet starter auto-propagates trace ID via gRPC metadata headers when
  `micrometer-tracing` is on the classpath. Uses `grpc-context` propagation.
  Verify `X-B3-TraceId` header is forwarded in gRPC metadata (W3C `traceparent` also supported).
- Kafka: `micrometer-tracing` + Spring Kafka propagates trace ID via Kafka message headers
  automatically when `KafkaTemplateObservation` is enabled.
- All log lines automatically include `traceId` and `spanId` when using Micrometer.

**Structured logging**: Use Logback with `logstash-logback-encoder` to produce JSON log output.
Every log entry will include: `timestamp`, `level`, `service`, `traceId`, `spanId`, `message`.
A separate `audit-log` appender writes financial state transitions to a dedicated file/stream.

---

## 6. Gradle Multi-Project Build Setup

**Decision**: Gradle 8.x with Kotlin DSL (`.gradle.kts`) for the multi-project build.
Simpler projects may use Groovy DSL — for this project, Groovy DSL (`build.gradle`) is used
since it's more readable for Java developers not yet familiar with Kotlin DSL.

**Root `settings.gradle`**:
```groovy
rootProject.name = 'credit-card-payment-gateway'
include ':services:transaction-service'
// Additional services added here when Principle IV allows
```

**Root `build.gradle`**: Shared plugin versions via `plugins {}` block + version catalog
(`gradle/libs.versions.toml`). All subprojects inherit common configuration via
`allprojects {}` / `subprojects {}` blocks.

**`services/transaction-service/build.gradle`**: Applies `java`, `org.springframework.boot`,
`io.spring.dependency-management`, and `com.google.protobuf` plugins. JaCoCo configured with
100% instruction + branch coverage thresholds.

---

## 7. TDD Order of Implementation (Principle II + IV)

The correct TDD order for each class, as derived from the layered architecture (Principle V):

```
Phase 1 (tests first, then implementation):
  1. Domain: Transaction.java, TransactionStatus.java, ProcessedEvent.java
  2. Repository: TransactionRepository.java (integration test with Testcontainers)
  3. Repository: ProcessedEventRepository.java (integration test with Testcontainers)
  4. Service: TokenizationService.java (unit test with mocked VaultOperations)
  5. Service: IdempotencyService.java (unit test with mocked ProcessedEventRepository)
  6. Service: TransactionService.java (unit test — mocks TokenizationService, TransactionRepository, IdempotencyService, KafkaTemplate)
  7. API: TransactionGrpcService.java (unit test with mocked TransactionService)
  8. API: TransactionStatusListener.java (unit test with mocked TransactionService + IdempotencyService)
  9. Config classes (minimal test — application context loads)

Phase 2 (integration + contract tests):
  10. TransactionCreationIntegrationTest (full flow: gRPC → Vault → CockroachDB → Kafka)
  11. StatusUpdateIntegrationTest (Kafka → idempotency check → CockroachDB)
  12. TransactionGrpcContractTest (validates generated stubs match proto contract)
```

No implementation code is written before its test exists and is confirmed to fail (RED).
Each test is approved before the implementation that makes it pass (GREEN) is written.

---

## 8. Vault Local Setup (Docker Compose)

**Local Docker Compose Vault config**:
```yaml
vault:
  image: hashicorp/vault:1.16
  ports:
    - "8200:8200"
  environment:
    VAULT_DEV_ROOT_TOKEN_ID: "dev-root-token"
    VAULT_DEV_LISTEN_ADDRESS: "0.0.0.0:8200"
  cap_add:
    - IPC_LOCK
  command: ["server", "-dev"]
```

**Vault initialization script** (runs once after Vault starts):
```bash
export VAULT_ADDR='http://localhost:8200'
export VAULT_TOKEN='dev-root-token'
vault secrets enable transit
vault write -f transit/keys/card-pan-key
```

In production (K8s), Vault runs as a StatefulSet with Raft storage. The transaction-service
authenticates via Kubernetes service account (Vault Kubernetes auth method).

**Spring Vault `application-local.yml` config**:
```yaml
spring:
  cloud:
    vault:
      host: localhost
      port: 8200
      scheme: http
      authentication: TOKEN
      token: dev-root-token
```

---

## Summary: All Unknowns Resolved

| Unknown | Resolution |
|---------|-----------|
| gRPC library | `net.devh:grpc-spring-boot-starter:3.1.x` (LogNet) |
| Vault integration pattern | Spring Vault `VaultTemplate.opsForTransit().encrypt()` |
| Vault ciphertext as token | Vault ciphertext IS the opaque card token stored in DB |
| CockroachDB dialect | `PostgreSQLDialect` + serialization retry with `@Retryable` |
| UUID primary keys | `gen_random_uuid()` — avoids CockroachDB sequence hotspots |
| Kafka idempotency | `processed_events` table + `@Transactional` atomic commit |
| Kafka serialization | `JsonSerializer` / `JsonDeserializer` with typed POJOs |
| Trace propagation | Micrometer Tracing — auto-propagates in gRPC metadata + Kafka headers |
| Build tool | Gradle 8.x with `com.google.protobuf:0.9.4` plugin |
| TDD order | Domain → Repository → Service → API → Integration → Contract |
| Vault local setup | Docker Compose dev mode; `dev-root-token`; Transit engine enabled via init script |
