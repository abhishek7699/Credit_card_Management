<!--
SYNC IMPACT REPORT
==================
Version change: 1.2.1 → 1.3.0 (MINOR — governed scope expanded: booking-service added
to Principle V's list of in-scope services, per the Roadmap in CLAUDE.md starting the
"2a Booking" layer on top of the existing 7-service payment core)

Modified principles:
  - V. Layered Microservice Architecture & Communication Tiers — "Services in scope"
    list now includes `booking-service`
  - (no principle renamed or restructured)

Security & Compliance Standards: no changes

Infrastructure & Technology Stack: no changes

Governance:
  - Last Amended date updated

Templates reviewed:
  - .specify/templates/plan-template.md   ✅ no scope-list references found
  - .specify/templates/spec-template.md   ✅ no scope-list references found
  - .specify/templates/tasks-template.md  ✅ no scope-list references found

Deferred TODOs: none
-->

# Credit Card Payment Gateway Constitution

## Core Principles

### I. Tokenization & Zero Raw Card Data (NON-NEGOTIABLE)

Raw Primary Account Numbers (PANs) MUST never be stored, logged, transmitted, or held in
memory beyond the instant of tokenization. All card numbers entering the system MUST be
replaced with opaque tokens before any persistence or downstream event emission occurs.
CVV/CVC values MUST never be stored under any circumstances — not even transiently after
the authorization response is received. Any violation of this principle constitutes a
critical security incident and MUST block the pipeline.

- PANs in any service payload MUST be masked (e.g., `****-****-****-1234`) before logging.
- Token creation is the sole responsibility of the `transaction-service`; no other service
  receives or processes raw card numbers.
- Code reviewers MUST explicitly verify this rule on every PR touching card data paths.

### II. Test-First / 100% Coverage (NON-NEGOTIABLE)

TDD is mandatory across all services. The cycle is strictly: write failing test → get
explicit approval from the developer → implement the minimum code to pass → refactor.
No implementation code is written before its corresponding test exists and has been
confirmed to fail for the right reason. 100% line and branch coverage is the non-negotiable
gate for every class in every service. Coverage reports MUST be generated and pass before
any pull request can proceed with GitHub Actions.

- Unit tests: JUnit 5 + Mockito for all service and domain classes.
- Integration tests: Spring Boot Test (`@SpringBootTest`) with Testcontainers for
  CockroachDB, Kafka, and Redis dependencies.
- Contract tests: required for every inter-service gRPC stub (using generated proto-based
  mocks), every Kafka event schema, and every external REST endpoint on the api-gateway.
- The GitHub Actions workflow MUST fail the build if coverage drops below 100%.

### III. Idempotent Event Consumers (NON-NEGOTIABLE)

Every Kafka consumer in every service MUST be idempotent. Re-delivery of any event MUST
produce exactly the same observable outcome as the first delivery — no duplicate
transactions, no double charges, no redundant notifications. Idempotency keys MUST be
persisted in CockroachDB within the same transaction as the business effect so that
restarts and rebalances cannot re-process committed events.

- Each consumer MUST check for the idempotency key before processing.
- Idempotency key schema: `{servicePrefix}:{eventType}:{eventId}` stored in a dedicated
  `processed_events` table per service database.
- Consumers MUST commit the idempotency record and the business state atomically (single
  CockroachDB transaction).
- Tests MUST verify that sending the same event twice produces a single side effect.

### IV. Iterative One-Step Development (NON-NEGOTIABLE)

No step advances until the current step is fully implemented, covered by passing tests,
and explicitly approved. This applies at three levels:

1. **Service level** — one microservice reaches a working, tested state before the next
   service is scaffolded.
2. **Class level** — within a service, one class is complete before the next is started.
3. **Method level** — within a class, one method is implemented, tested, and reviewed
   before the next method is written.

"Approved" means the developer has read the code, run the tests, confirmed the behaviour,
and stated explicit agreement. Partial implementations, stubs beyond immediate test needs,
and speculative scaffolding are prohibited.

### V. Layered Microservice Architecture & Communication Tiers

Each service MUST honour a strict three-layer internal boundary:

| Layer | Responsibility | Prohibited |
|-------|---------------|------------|
| API Layer (Controller / gRPC Handler / Kafka Listener) | Request routing, deserialization, input validation | Business logic, DB access |
| Service Layer | All business logic and orchestration | Direct DB calls, outbound network calls |
| Repository Layer | Data access via Spring Data / CockroachDB only | Business rules, network calls |

Cross-service communication is governed by three tiers — the correct tier MUST be chosen
based on the interaction type:

| Tier | Protocol | When to Use |
|------|----------|-------------|
| External (client → api-gateway) | REST over HTTPS | All inbound traffic from React frontend or external consumers |
| Internal synchronous (service → service) | gRPC over HTTP/2 with Protocol Buffers | Blocking calls requiring an immediate response (e.g., authorization check) |
| Internal asynchronous (service → service) | Apache Kafka | Fire-and-forget events, eventual consistency, cross-service side effects |

Rules:
- gRPC is the **only** permitted synchronous communication mechanism between internal
  services. Plain REST service-to-service calls are prohibited.
- Kafka is the **only** permitted asynchronous communication mechanism. Shared DB tables
  as a messaging channel are prohibited.
- Each service owns its own CockroachDB schema; no service may query another service's
  tables directly.
- The `api-gateway` terminates all external REST traffic and routes internally via gRPC
  or Kafka — it does not forward raw HTTP to downstream services.

Services in scope: `api-gateway`, `transaction-service`, `authorization-service`,
`fraud-detection-service`, `notification-service`, `settlement-service`,
`reporting-service`, `booking-service`.

### VI. Observability & Audit Trail

All financial operations MUST produce structured, machine-parseable audit log entries
(JSON) containing: timestamp (ISO-8601), correlation/trace ID, service name, event type,
and a masked representation of any card-related identifier. Raw card data in logs is
an unconditional violation of Principle I and this principle simultaneously.

- Correlation IDs (Micrometer Tracing) MUST be propagated across all three communication
  tiers: Kafka message headers, gRPC metadata headers, and HTTP headers (`X-Trace-Id`).
- Every Kafka event MUST include: `eventId`, `eventType`, `sourceService`, `traceId`,
  `occurredAt`.
- Every gRPC call MUST propagate the trace ID via gRPC metadata
  (`x-b3-traceid` / W3C `traceparent`).
- Redis cache hits/misses for rate-limiting decisions MUST be logged at DEBUG level.
- A dedicated audit log appender (separate from application logs) MUST capture all
  card-related state transitions (authorization, settlement, chargeback).

### VII. Proto-First Service Contracts

Protocol Buffer (`.proto`) files are the single source of truth for all internal
synchronous service contracts. A gRPC service interface MUST be defined in a `.proto`
file and approved before any implementation of that service begins. This principle
directly reinforces Principle IV: the `.proto` file is the deliverable for the
design step of any service-to-service interaction.

- All `.proto` files MUST reside in a shared `proto/` directory at repository root,
  versioned with the rest of the codebase.
- Breaking changes to a `.proto` file (removing fields, changing field numbers, renaming
  RPCs) require an explicit constitution-level review and a new `.proto` version package
  (e.g., `v1` → `v2`).
- Generated stubs (`*Grpc.java`, `*OuterClass.java`) MUST be produced by the build system
  (Gradle/Maven protoc plugin) and MUST NOT be manually edited or committed to the repo.
- Contract tests MUST be written against the generated stubs before any service
  implementation proceeds, consistent with Principle II.
- `.proto` files MUST document each RPC and message field with inline comments explaining
  its business purpose.

### VIII. Frontend Code Sharing — Web + Mobile

Shared business logic MUST NOT be duplicated between the React TypeScript web application
(Phase 2) and the React Native mobile application (Phase 3). All logic that is not
platform-specific MUST live in a shared package consumed by both apps.

The frontend workspace MUST use a monorepo structure with npm/pnpm workspaces:

```
frontend/
├── apps/
│   ├── web/        # React + TypeScript (Phase 2) — browser-specific UI only
│   └── mobile/     # React Native + TypeScript (Phase 3) — native UI only
└── packages/
    └── shared/     # Consumed by both apps — no platform-specific code
```

What MUST live in `packages/shared`:
- API client code (calls to the backend api-gateway)
- Custom hooks containing business logic
- TypeScript type definitions and interfaces (aligned with proto-generated types)
- Data validation utilities
- Formatting utilities (currency, masked card numbers, dates)
- Constants and configuration schemas

What MUST NOT live in `packages/shared`:
- Any React Native–specific imports (`react-native`, native modules)
- Any browser-specific APIs (`window`, `document`, DOM types)
- Platform-specific styling or component trees

Rules:
- Phase 3 (mobile) does NOT begin until Phase 2 (web) is stable, tested, and explicitly
  approved — consistent with Principle IV at the frontend level.
- Both `apps/web` and `apps/mobile` MUST use TypeScript throughout; no `.js` files permitted.
- `packages/shared` MUST have its own test suite; shared logic is tested once, not in each app.
- Any PR touching `packages/shared` MUST be validated against both `apps/web` and
  `apps/mobile` to confirm no breakage — even before the mobile app exists (add a type-check
  pass for the mobile app's expected usage as soon as Phase 3 specs are written).

## Security & Compliance Standards

PCI-DSS awareness is a design constraint applied at every architectural decision, not a
post-hoc compliance checkbox. The following rules apply throughout:

- **Encryption in transit**: TLS 1.2+ is mandatory for all external REST traffic.
  gRPC between internal services MUST use mTLS in production (Kubernetes service mesh
  or explicit cert configuration). Plain HTTP and unencrypted gRPC are prohibited in all
  environments beyond local Docker Compose development.
- **Encryption at rest**: CockroachDB Serverless provides encryption at rest by default;
  no unencrypted external volumes may store card-related data.
- **Secrets management**: All credentials (DB connection strings, Kafka SASL creds,
  Redis auth tokens, gRPC TLS certs) MUST be injected via Kubernetes Secrets or
  environment variables sourced from GCP Secret Manager. Secrets in source code or
  Docker images are a blocking violation.
- **Rate limiting**: Redis-backed rate limiting is enforced at the `api-gateway` for all
  inbound REST endpoints. Thresholds MUST be defined per endpoint and per client key.
- **Dependency scanning**: GitHub Actions workflow MUST include OWASP dependency-check or
  equivalent; HIGH/CRITICAL CVEs block the merge.
- **Least privilege**: Each Kubernetes service account and CockroachDB role MUST have
  only the permissions required for its service — no shared superuser credentials.

## Infrastructure & Technology Stack

The canonical stack is fixed for this project. Deviations require an explicit constitution
amendment.

| Concern | Technology |
|---------|-----------|
| Language / Runtime | Java 17 (LTS) |
| Application Framework | Spring Boot 3.x |
| External API Gateway | Spring Cloud Gateway |
| Internal Service Communication | gRPC with Protocol Buffers |
| Async Messaging | Apache Kafka |
| Cloud Database | CockroachDB Serverless |
| Caching / Rate Limiting | Redis |
| Local Development | Docker Compose (all services + infrastructure) |
| Production Deployment | Kubernetes on GKE (Google Kubernetes Engine) |
| CI/CD | GitHub Actions |
| Frontend Web (Phase 2) | React + TypeScript — not started until all backend services are stable |
| Mobile App (Phase 3) | React Native + TypeScript (iOS + Android) — not started until Phase 2 web app is stable and approved |

**Docker Compose is authoritative for local development.** Every service MUST run via
`docker-compose up` with no manual setup steps. The `docker-compose.yml` at repository
root MUST define all services and infrastructure dependencies (Kafka, KRaft or ZooKeeper,
CockroachDB, Redis). gRPC ports for each service MUST be exposed in Compose for
local integration testing.

**Kubernetes manifests** live under `k8s/` at repository root, organized by service.
The GitHub Actions workflow MUST apply manifests to GKE on merge to `main`.

**Proto files** live under `proto/` at repository root. Stub generation MUST be
automated via the build tool (Gradle `protobuf` plugin or Maven `protoc-jar`).

**Frontend workspace** lives under `frontend/` at repository root (Principle VIII).
The monorepo contains `apps/web` (Phase 2), `apps/mobile` (Phase 3), and `packages/shared`
(code shared between both). Phase 2 and Phase 3 are built sequentially — mobile does not
begin until the web app is stable and approved.

**Build sequence** (enforced by Principle IV):
1. Phase 1 — All 7 backend microservices (one at a time, in dependency order)
2. Phase 2 — `frontend/apps/web` (React + TypeScript)
3. Phase 3 — `frontend/apps/mobile` (React Native + TypeScript, iOS + Android)

## Governance

- This constitution supersedes all other team conventions, coding standards, and verbal
  agreements. When a conflict exists, the constitution wins.
- Amendments require: (1) a written proposal describing the change and rationale,
  (2) explicit agreement from all active contributors, (3) a migration plan for any
  code already written under the prior rule, and (4) a version bump per semantic
  versioning rules before any new work begins under the amended rule.
- All pull requests MUST include a "Constitution Check" section confirming compliance
  with all eight Core Principles.
- Any pipeline that cannot demonstrate 100% test coverage (Principle II) or that
  contains a raw card data leak in logs (Principle I / VI) MUST be blocked and not
  merged under any circumstances, including release deadlines.
- Any `.proto` breaking change that lacks a version migration path (Principle VII) MUST
  block the merge.
- For runtime development guidance, refer to `CLAUDE.md` at the repository root.

**Version**: 1.3.0 | **Ratified**: 2026-06-11 | **Last Amended**: 2026-10-29
