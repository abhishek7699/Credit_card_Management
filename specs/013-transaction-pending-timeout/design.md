# 013 — PENDING transaction timeout with credit release (DESIGN v3)

Status: implemented. Phases 1, 2 and 4 are done; phase 3 is superseded by option Y (see Phases).
Point 5 decided: option Y. Phase 1 follow-up applies it (see "Decisions applied after phase 1").
Booking startup check for timer rule 2 is deferred to docs/BACKLOG.md.

## Owner answers applied

| Item | Value |
|---|---|
| `P` — PENDING timeout | 300 s |
| `I` — sweep interval | 30 s |
| `K` — max Kafka delivery lag (documented assumption) | 60 s |
| `m` — margin | 60 s |
| Settlement consumer | Approved: on `transaction.timed_out`, settlement voids or skips any unsettled AUTHORIZED row for that transaction so it is never settled |
| Constitution | 1.4.0 (Principle IX) confirmed |

## Findings (re-checked against post-2c `main`)

1. **Booking can record a timed-out transaction as paid.** Post-2c, payment happens at checkout.
   `BookingService.checkOut` creates a `CheckoutPaymentAttempt` (PENDING), calls transaction-service
   over gRPC, and stores the returned `transactionId`. Outcomes arrive on `transaction.status.updated`,
   which is written by **authorization-service** (`RESULT_TOPIC`), not by transaction-service.
   Booking's `handlePaymentOutcome` applies this matrix:
   - PENDING + AUTHORIZED → attempt AUTHORIZED, booking CHECKED_OUT, room DIRTY
   - PENDING + DECLINED / FAILED → attempt DECLINED / FAILED, booking back to CHECKED_IN
   - EXPIRED + AUTHORIZED → attempt **LATE_AUTHORIZED**, counted as **paid** toward the balance
   - EXPIRED + DECLINED / FAILED → no-op

   Two paths can end with a timed-out transaction recorded as paid:
   - **Path 1:** authorization's AUTHORIZED reaches booking, but transaction-service misses it.
     The transaction then times out and its credit is released, while booking is CHECKED_OUT.
   - **Path 2:** `CheckoutAttemptSweepService` expires the attempt at `BOOKING_CHECKOUT_TIMEOUT_MINUTES`
     (default 10). A later AUTHORIZED becomes LATE_AUTHORIZED and is recorded as paid, even if 013 has
     already FAILED the transaction and released its credit. The transaction-side terminal guard does
     not stop booking from reading authorization's result.

2. **Settlement can settle a timed-out transaction.** settlement-service's `TransactionAuthorizedListener`
   saves an AUTHORIZED row from `transaction.authorized` without checking the transaction. The batch reader
   selects `WHERE status = 'AUTHORIZED'`. Nothing on the timeout path reaches settlement today.

3. **The terminal-state guard is not safe under concurrency.** transaction-service's
   `handleAuthorized/Declined/Failed` does `findById` → `isTerminal()` → `UPDATE ... WHERE id = ?` with no
   status predicate. A timeout committing between the read and the write can be overwritten by a late
   AUTHORIZED. Every transition needs `AND status = 'PENDING'`.

4. **Option B cannot close path 1 or path 2.** Booking would keep reading authorization's result directly,
   and booking cannot reverse a CHECKED_OUT booking (refunds are out of scope). So option B only documents
   the race.

## Recommended design: booking follows transaction-service's final status (option A)

### Topics

- `transaction.authorization.result` (new, internal): authorization-service publishes its raw decision here.
  Only transaction-service consumes it.
- `transaction.status.updated` (existing): **only transaction-service publishes final outcomes here**
  (`TransactionAuthorized`, `TransactionDeclined`, `TransactionFailed`). Booking and notification consume it.
- `transaction.timed_out` (new): published by the 013 sweep. Consumed by authorization-service and settlement-service.
- `transaction.authorized` (existing): unchanged; settlement consumes it.

### transaction-service

- Flyway V3: `ALTER TABLE transactions ADD COLUMN failure_reason VARCHAR(32)` (nullable). Only `TIMEOUT` is
  written here. No new status.
- Consumer of `transaction.authorization.result` replaces `TransactionStatusListener`. Each result runs as
  `UPDATE transactions SET status=?, ... WHERE id=? AND status='PENDING'`. The final event is published only
  when that update returns 1, so each final outcome is published at most once.
- Sweep (`@Scheduled(fixedDelay = 30s)`): select PENDING rows with `created_at < now() - 300 s`, in batches.
  For each row, `UPDATE ... SET status='FAILED', failure_reason='TIMEOUT' WHERE id=? AND status='PENDING'`.
  If the update returns 1, publish `transaction.status.updated` (`TransactionFailed`, reason TIMEOUT) and
  `transaction.timed_out`. Two replicas: exactly one update wins per row; the other gets 0 and skips.
- Publishing happens inside the DB transaction, as `transaction.initiated` already does. A publish failure
  after commit loses the event. This is the existing gap, and it matters more under option A (see below).

### authorization-service

- Consumer of `transaction.timed_out`, using the `processed_events` pattern (key
  `authorization:TransactionTimedOut:<eventId>`). In one DB transaction, under the card row lock
  (`SELECT ... FOR UPDATE`):
  - Deduction exists (AUTHORIZED record): `available_credit += amount`, record → RELEASED, conditional on its
    current outcome.
  - No record yet: insert a TIMED_OUT marker for the transactionId. A late `transaction.initiated` finds the
    marker and skips authorization (no deduction, no result event).
  - Record DECLINED or already RELEASED: no-op.
- Authorization publishes results on `transaction.authorization.result` instead of `transaction.status.updated`.

### booking-service

- **No change to handlers or consumer logic.** Under option A it receives only final outcomes:
  - Timeout → FAILED → existing FAILED handler: attempt FAILED, booking back to CHECKED_IN, staff can retry.
  - A late AUTHORIZED for a timed-out transaction is never published, because transaction-service ignores it.
    Path 1 and path 2 are both closed.
  - A genuine late success (the transaction was still PENDING when booking's sweep fired) is published as
    AUTHORIZED and becomes LATE_AUTHORIZED, the existing behavior.
- Booking does not need to consume `transaction.timed_out`.

### settlement-service (approved)

- Consumer of `transaction.timed_out`: sets the matching AUTHORIZED row to `VOIDED`, so the batch reader never
  picks it up. A row already SETTLED cannot be reversed (no refunds); it is logged for manual handling.

### notification-service

- Consumes `transaction.status.updated`. It receives only final outcomes, so no change is needed. Confirm in
  phase 1 that it does not read the internal result topic.

## Known gap with option A: lost final event

If transaction-service commits AUTHORIZED and crashes before publishing, booking never hears the outcome. The
attempt then expires as EXPIRED, booking returns to CHECKED_IN, and the credit stays held. Before option A,
this gap affected only the transaction side. Now booking depends on it too.

**Recommendation: a transactional outbox** in transaction-service: one `outbox` table written in the same DB
transaction as the status change, and a relay that publishes from it. It adds one table and one relay component.
It is beyond the brief, so it needs your approval.

## Happy-path note

For a normal transaction, the external outcome does not change, but the internal hop does:
authorization → transaction-service → booking, instead of authorization → booking. The 013 brief says "no
happy-path change," so this needs your explicit waiver.

## Timer ordering

| Symbol | Timer | Value |
|---|---|---|
| `M` | Mastercard read timeout | 10 s |
| `R` | Status-retrieve window `2·M + b`, `b = 2 s` | 22 s |
| `K` | Max delivery lag | 60 s |
| `P` | 013 PENDING timeout | 300 s |
| `I` | 013 sweep interval | 30 s |
| `S` | Booking checkout sweep: `BOOKING_CHECKOUT_TIMEOUT_MINUTES` × 60, from attempt creation, plus a 30 s poll | 600 s (up to 630 s with the poll) |
| `d` | gRPC deadline on `InitiateTransaction` at checkout | 5 s |
| `m` | Margin | 60 s |

Rules:

1. `P ≥ K + M + R + m`: `300 ≥ 152` ✓. The sweep never acts while an AUTHORIZE can still be in flight.
2. `P + m + d ≤ S`: `300 + 60 + 5 = 365 ≤ 600` ✓. A transaction times out before booking's sweep expires the
   attempt. Under option A, booking therefore records FAILED (retry), not EXPIRED followed by a late approval.
3. `I ≤ P`: `30 ≤ 300` ✓.

Startup validation: transaction-service checks rules 1 and 3. booking-service checks rule 2 against
`transaction.pending-timeout`, shared through `docker-compose.yml`.

## Event flow after 013 (option A)

```
api (InitiateTransaction)  → transaction-service   PENDING; publishes transaction.initiated
authorization-service      → transaction.authorization.result   (raw decision, internal)
transaction-service        → conditional PENDING update → transaction.status.updated (FINAL, once)
                           → transaction.authorized (when AUTHORIZED; settlement consumes)
013 sweep (every 30 s)     → conditional PENDING→FAILED(TIMEOUT) → transaction.status.updated (FINAL)
                           → transaction.timed_out → authorization releases; settlement voids
booking-service            ← transaction.status.updated (FINAL only; handlers unchanged)
notification-service       ← transaction.status.updated (FINAL only)
```

## Tests (planned, once approved)

- Stuck PENDING transaction times out: FAILED, reason TIMEOUT, one final event, one `transaction.timed_out`.
- Two sweeps on the same rows (two replicas): each transaction handled exactly once.
- Late AUTHORIZED after a timeout: transaction status unchanged (conditional update, including a concurrent
  case); no final AUTHORIZED published; booking attempt stays FAILED.
- `transaction.timed_out` before `transaction.initiated`: initiated skipped, no deduction.
- Duplicate timeout events: no-ops.
- Release restores credit once (simulator), and is a no-op when no authorization exists.
- Booking: a timed-out checkout attempt ends FAILED and booking returns to CHECKED_IN.
- Booking: a genuine late success after EXPIRED becomes LATE_AUTHORIZED (existing behavior kept).
- Settlement: a timed-out transaction's AUTHORIZED row is voided and never settled; a SETTLED row is not changed.
- `TimerOrderingTest`: accepts the valid combination; rejects a violation of each rule.
- 100% JaCoCo for every changed service.

## Decisions still needed before implementation

1. **Booking option A**: approve (recommended), or choose option B (path 1 and path 2 stay open).
2. **Outbox** in transaction-service for final-outcome publication: approve. Option A depends on it.
3. **Waiver** of "no happy-path change" for the internal topic change in option A.
4. **Topic name** `transaction.authorization.result`: confirm, or give another.

## Approved decisions (owner, latest)

1. Option A: transaction-service is the only publisher of final outcomes.
2. Transactional outbox, used for the final outcome on `transaction.status.updated` and for
   `transaction.timed_out`. Stable eventId = nameUUID(transactionId + ":" + eventType). One outbox
   table, one polling relay. Tests: crash between commit and publish; relay publishes twice.
3. Waiver of "no happy-path change" approved. External behavior must not change: booking and e2e
   tests pass without changes to their expected outcomes.
4. Topic `transaction.authorization.result` confirmed.

## Phases (checkpoint after each)

- **Phase 1 (done, this commit):** V3 migration (failure_reason, transaction_outbox); conditional
  `transitionStatus` replacing the unconditional update; outbox writer (MANDATORY transaction),
  publisher (send then mark sent), polling relay (oldest first, stops at first failure);
  transaction-service consumes `transaction.authorization.result` (renamed to
  `AuthorizationResultListener`); authorization publishes results on the new topic. Booking and
  notification unchanged.
- **Phase 2:** 013 sweep (PENDING > 300 s, every 30 s, conditional PENDING→FAILED with reason TIMEOUT,
  outbox for `TransactionFailed` and `transaction.timed_out`); authorization consumer of
  `transaction.timed_out` (release once, TIMED_OUT marker for late initiated); TimerOrderingTest.
- **Phase 2 (done):** PENDING sweep, `transaction.timed_out`, authorization release, timeout markers,
  TimerOrderingTest.
- **Phase 3: superseded by option Y.** Settlement only receives `transaction.authorized`, which
  transaction-service writes only when the AUTHORIZED update wins, so a timed-out transaction is never
  settled. The integration test `aTimedOutTransactionNeverPublishesTransactionAuthorized` proves it.
  No settlement code was built.
- **Phase 4 (done):** README (architecture, outbox and timeout, known limitations), this status, BACKLOG
  entry, final gate.

## Point 5: should transaction-service publish transaction.authorized? (evaluation, not implemented)

**Current design (X):** authorization publishes `transaction.authorized` as soon as it approves, before
transaction-service commits. Settlement inserts an AUTHORIZED row from it. A timeout voids the row via a
new `transaction.timed_out` consumer.

Race in X: `transaction.timed_out` and `transaction.authorized` come from different producers, so
their order at settlement is not guaranteed. If the timeout arrives first, the void finds no row. The
later `transaction.authorized` then inserts an AUTHORIZED row that settlement will settle. X therefore
needs a tombstone (record "voided before seen" and refuse the later insert). That adds state and a
second code path to get right.

**Option Y:** transaction-service publishes `transaction.authorized` through the same outbox, only when
its conditional PENDING→AUTHORIZED update wins. The payload is built from the Transaction row
(merchantId, maskedCardNumber, amount, currencyCode, cardToken, authorizedAt = now), so it matches
settlement's existing fields. Authorization stops publishing it.

- A timed-out transaction never reaches AUTHORIZED, so it never gets `transaction.authorized`. There is no
  race and no tombstone.
- Settlement's consumer code is unchanged. Only the producer moves.
- The `transaction.timed_out` settlement consumer becomes unnecessary, so that approval is not needed.

**Recommendation: Y.** Settlement only sees transactions that transaction-service recorded as AUTHORIZED,
and the invariant holds by construction. It also removes the one ordering problem in the design. The
cost is that settlement's input now depends on the outbox relay (latency only).

## Owner decisions not recorded above

1. **Branch and number:** 013 is on branch `013-transaction-pending-timeout`, created from `main`, using the next free spec number.
2. **Sequencing:** 011-charges merged to `main` first (PR #13, merge commit 417b04f). Then 013 and 014 were rebased onto it, and the booking part of this design was redone against the post-2c checkout flow before implementation.
3. **Gate G0:** 013 must be implemented and merged to `main` before any 014 task starts.
4. **Scope:** no refunds, voids or fraud reversals in 013. External behavior stays the same, so existing booking and e2e expected outcomes must not change.
5. **Process:** implement one phase at a time. Commit each phase, then stop at a checkpoint. Approval is per phase, not per task.
6. **Values:** the owner sets timeout and sweep values. I ask for them and do not pick them.

## Decisions applied after phase 1 (owner)

1. **Option Y.** transaction-service publishes `transaction.authorized` through the outbox, only when its
   conditional PENDING→AUTHORIZED update wins. Authorization no longer publishes it. Settlement's consumer is
   unchanged. The settlement consumer for `transaction.timed_out` is removed from the plan.
2. **`transaction.initiated` stays a direct publish.** The PENDING sweep covers a crash before it is sent.
   Outbox rows are published immediately after the transaction commits, and marked sent on success. The
   polling relay picks up only rows that are not sent. **Relay poll interval: 500 ms**
   (`TRANSACTION_OUTBOX_POLL_MS`). This adds at most about 0.5 s to the normal path, so **K stays 60 s**.
3. **Relay failure handling.** Ordering matters only within one transaction (`message_key` = transactionId).
   - Kafka transport failure (timeout, broker down): the pass stops. No attempt is counted.
   - Row-specific failure (stored JSON cannot be read, or the broker rejects the record): the attempt is
     counted and only later rows for the same transaction wait. Other transactions still go out.
   - After `transaction.outbox.max-attempts` (default 5) the row is marked failed (`failed_at`, `last_error`),
     logged with its transactionId, and counted in the `transaction_outbox_failed_total` metric.
4. **Values:** P = 300 s, I = 30 s, K = 60 s, m = 60 s.
5. **Stable eventId** = nameUUID(transactionId + ":" + topic + ":" + eventType). The topic is included because
   `transaction.status.updated` and `transaction.authorized` both carry `TransactionAuthorized` for the same
   transaction, and the outbox primary key is eventId. The earlier formula (without topic) would have collided.
6. **Outbox producer** sends stored JSON with type-info headers off. Settlement's consumer falls back to its own
   default type, so a header naming transaction-service's class would break it.
7. **Immediate publish** runs through the publisher's proxy after commit. A failure is logged and the row stays
   for the relay. Calling the publisher from inside itself would skip its transaction.

## Open, not yet decided by the owner

- None for phase 1 follow-up. Phase 2 uses the values above.

