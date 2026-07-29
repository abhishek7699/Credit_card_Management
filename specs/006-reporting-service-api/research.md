# Research: Reporting Service API

## Decision 1: Cross-Schema Read Access Pattern

**Decision**: The reporting-service reads directly from the tables owned by the settlement-service (`settlement_transactions`, `merchant_settlements`, `settlement_batches`) using a dedicated read-only database role.

**Rationale**: A pure CQRS read-side service that only queries (never writes) is a well-established and justified exception to the "each service owns its own schema" rule. The alternatives — maintaining a separate event-sourced projection or running an ETL pipeline — would require a Kafka consumer, synchronization lag, additional schema, and added operational complexity for no business benefit beyond schema isolation. The read-only constraint is enforced at the application layer (no write operations) and at the database layer (read-only credentials).

**Alternatives Considered**:
- ETL/projection: Adds Kafka consumer, schema duplication, synchronization complexity. Rejected as over-engineering for a reporting service.
- Separate read replica: Adds infrastructure cost and replica lag. Not warranted at current scale.
- GraphQL federation: Premature complexity; REST is sufficient for the defined queries.

**Constitution Note**: This is a documented, justified deviation from Principle V ("each service owns its own CockroachDB schema"). The justification is recorded in the Complexity Tracking section of `plan.md`. The deviation is strictly bounded: read-only access, dedicated DB role, no cross-service writes or shared application-layer state.

---

## Decision 2: Dynamic Filtering via JPA Specifications

**Decision**: Use Spring Data JPA `Specification<T>` API for composable, type-safe filtering of transaction queries. Combined filters (card token + date range + status) are composed via `Specification.and()`.

**Rationale**: Spring Data JPA Specifications are the standard pattern for dynamic multi-predicate queries in Spring Boot. They produce type-safe, composable predicates without string concatenation, avoid N+1 query risks, and integrate natively with `JpaSpecificationExecutor<T>`. They are well-tested and require no additional dependencies.

**Alternatives Considered**:
- QueryDSL: More powerful but adds `querydsl-jpa` dependency and code generation step; over-engineering for 3–4 filter parameters.
- Native @Query with optional parameters: Works but produces hard-to-maintain SQL strings as filter combinations grow.
- Spring Data JDBC: Less ORM overhead but lacks Specification support out of the box.

---

## Decision 3: No Flyway in Production; SQL Init in Tests

**Decision**: The reporting-service does NOT run Flyway migrations in production (`spring.flyway.enabled=false`). The tables it reads are created and owned by the settlement-service. For integration tests, the needed tables are created via `@Sql` annotation pointing to test SQL scripts included in `src/test/resources/db/`.

**Rationale**: Running Flyway in the reporting-service on tables owned by another service would create a dual-management problem (two services running migrations on the same tables, potential conflict). The reporting-service is a pure consumer of the settlement-service's schema.

**Test Strategy**: Integration tests use `postgres:15` Testcontainer with `spring.jpa.hibernate.ddl-auto=create-drop` in the test profile. The `@Sql` annotation creates the tables before tests and drops them after. The SQL DDL for test setup is taken directly from the settlement-service's migration files.

---

## Decision 4: Offset Pagination via Spring Pageable

**Decision**: Use Spring Data's `Pageable` (page number + page size) for list endpoints. Default page size: 20. Maximum page size: 100 (enforced via `@Max(100)` validation on the `size` parameter). Results ordered by relevant timestamp descending.

**Rationale**: Offset pagination is simpler to implement and sufficient at the expected query volumes (internal tooling, not high-throughput consumer API). Cursor-based pagination would be more efficient for very large datasets but adds implementation complexity. The 90-day date range cap (Decision 5) bounds the result set size.

**Alternatives Considered**:
- Cursor-based pagination (keyset pagination): More efficient for large datasets, avoids "moving page" problem. Deferred to future version.

---

## Decision 5: 90-Day Date Range Enforcement

**Decision**: When `from` and `to` date parameters are supplied, the maximum span is 90 calendar days. Requests exceeding this return HTTP 400 with a descriptive error. If `to` is not supplied, it defaults to now. If `from` is not supplied, `to - 30 days` is the default window.

**Rationale**: Without a range cap, queries against the full `settlement_transactions` table could become full-table scans. 90 days covers month-end reconciliation, quarter-end reporting, and chargeback dispute windows (typically 60–120 days). The existing indices on `authorized_at` and `settlement_date` are designed for range queries.

---

## Decision 6: Port Assignments

**Decision**: Reporting-service uses port `8086` for the application server and port `8091` for the Spring Boot Actuator management server. These are free in the current `docker-compose.yml` and Kubernetes manifests.

**Rationale**: Existing port assignments: transaction-service (8081), authorization-service (8081 app / 8082 mgmt), fraud-detection (8083), notification-service (8084), settlement-service (8080 / 8085). Reporting-service takes 8086 (app) and 8091 (mgmt).

---

## Decision 7: Merchant Summary Source

**Decision**: Merchant summaries are computed from the `merchant_settlements` table (pre-aggregated per batch) rather than aggregating raw `settlement_transactions` at query time.

**Rationale**: The `merchant_settlements` table already contains pre-aggregated `transaction_count`, `gross_amount`, `total_interchange_fee`, and `net_settlement_amount` per (batch_id, merchant_id). Querying this table is O(1) per merchant-batch pair, whereas re-aggregating transactions would be O(n). For the "all merchants across a date range" summary, the service sums across `merchant_settlements` grouped by `merchant_id` and filtered by `settlement_date`.
