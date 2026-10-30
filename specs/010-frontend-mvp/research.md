# Phase 0 Research: Frontend MVP (Staff Room Board)

## 0. There is no standing JWT-issuing service to log in against today — a gap in the input, not just a port number

**Decision**: Add a new, small, persistent `auth-mock` service to `docker-compose.yml`
(container name `auth-service`, matching the hostname `api-gateway` already expects)
that serves JWKS **and** a new `POST /dev/login` endpoint issuing a role-scoped JWT.
`scripts/e2e-test.sh` is left completely untouched.

**Rationale**: The plan input says the login screen "fetches a JWT from the existing
auth-mock" — but there is no existing *standing* auth-mock. The only thing that has
ever played that role is `scripts/e2e-test.sh`'s `setup_auth_mock`: it generates a
fresh RSA keypair with `openssl` on the **host**, signs JWTs itself in a local Python
heredoc (never over HTTP), and runs a throwaway `auth-service` container *only* for
the duration of one e2e run, deleting the keypair and the container in `cleanup()`.
Nothing in `docker-compose.yml` defines `auth-service` at all — `AUTH_SERVICE_JWKS_URI`
(`http://auth-service:9000/...`) resolves to nothing on a plain `docker compose up`.
Confirmed directly: `grep -n auth-service docker-compose.yml` matches only the env var
string, no service block. Practically, this means a developer bringing the stack up
the normal way has no way to obtain a JWT at all — the login screen (and the P1
checkpoint the user explicitly asked to click through) would have nothing to call.
This is the same class of mistake as the `:8088` port in research.md §1 below — an
assumption in the input that doesn't match what's actually there — except this one
blocks the feature entirely rather than just mis-routing traffic, so it's resolved
here as a real (small) addition rather than a one-line correction.

The new service:
- `scripts/dev-auth-mock.py` (stdlib `http.server` + `cryptography`, same proven
  pattern `setup_auth_mock` already uses for JWKS generation — `pip install` of
  `cryptography` only, run once at container start via the service's `command:`, no
  new Docker image).
- Generates one RSA keypair in memory when the container starts (fresh on every
  `docker compose up`/restart — harmless, since JWKS and signing always come from the
  same in-memory key for that container's lifetime).
- `GET /.well-known/jwks.json` and `GET /oauth2/jwks` — same JWKS shape as the e2e
  mock.
- `POST /dev/login` with `{"role": "FRONT_DESK"}` or `{"role": "HOUSEKEEPING"}` →
  `{"token": "<jwt>"}`. Claims match the e2e mock's own convention exactly (`sub`,
  `iss: http://auth-service:9000`, `iat`, `exp`, `authorities: ["ROLE_<role>"]`) — the
  `ROLE_` prefix is required because `SecurityConfig.extractAuthorities` passes the
  `authorities` claim straight through as granted authorities with no prefixing of
  its own, and `hasAnyRole(...)` matches against the `ROLE_`-prefixed form. A 4-hour
  expiry (a dev-only constant, long enough that no reasonable demo/manual-test session
  hits it).
- Added to `docker-compose.yml` as its own service (`auth-mock`, `container_name:
  auth-service`), with a port mapping for host access and no new `depends_on` pressure
  on anything beyond what `api-gateway` already has.

**Why `scripts/e2e-test.sh` needs no change**: it already does `docker rm -f
auth-service` before starting its own container and again in `cleanup()` — so even
with the compose-managed `auth-service` present, the e2e run forcibly replaces it with
its own fresh-keypair version for the run's duration and removes it afterward. The
existing 14-step suite's behavior is unaffected; confirmed by reasoning through
`setup_auth_mock`'s exact teardown/recreate sequence, not by assumption.

**A gap in that same reasoning, caught by an actual CI failure rather than by
re-reading it closely enough the first time**: "removes it afterward" means exactly
that — once `scripts/e2e-test.sh` finishes, *nothing* is listening on `:9000` until
the compose-managed service is explicitly brought back up. That's harmless for
`scripts/e2e-test.sh` itself, but anything run *after* it in the same job (this
layer's own Playwright smoke test, added in Phase 8) needs `/dev/login` again and
got `ECONNREFUSED` through Vite's dev proxy the first time this ran in CI. Fixed
with an explicit `docker compose up -d auth-mock` (+ a wait for `/dev/login` to
actually respond) between the e2e step and the Playwright step — see
`.github/workflows/ci.yml`'s "Restart auth-mock" step.

**Alternatives considered**: Teaching the frontend to mint its own JWT client-side (the
way the e2e *script* does, on the host) — rejected outright, it would require shipping
the signing private key to the browser, defeating the entire point of a signature.
Reusing `scripts/e2e-test.sh`'s mock as the standing one (run it once, leave the
container up) — rejected: it's designed to tear itself down, deletes its own keypair
on exit, and conflating "the e2e test's fixture" with "the thing a developer logs in
against all day" would make both harder to reason about and risk exactly the kind of
accidental coupling that breaks one when changing the other.

## 1. Vite dev proxy target — correcting a second factual error in the plan input

**Decision**: The dev proxy (and `VITE_API_BASE_URL`'s default) targets
`http://localhost:8087`, not `:8088` as the plan-phase instruction stated.

**Rationale**: Confirmed directly against `docker-compose.yml`'s `api-gateway` port
mapping: `8087:8080` is the gateway's real REST listener (container port 8080, where
`RouteConfig`'s routes are registered); `8088:8090` is its Spring Boot *management*
port (actuator only — `/actuator/health`, no business routes). `scripts/e2e-test.sh`
confirms the same split (`GW_PORT=8087` for every actual API call, `GW_MGMT=8088` only
for health polling). Proxying `/api` to `:8088` would route every staff request at a
port with no matching endpoint. `frontend/vite.config.ts` already defaults
`VITE_API_BASE_URL` to `http://localhost:8087` today — this plan corrects the proxy to
match that existing, correct default rather than introducing a second, wrong one.

**Alternatives considered**: Following the stated `:8088` as given — rejected outright,
it's not a legitimate alternative, it's a wrong port that would make every request
404/connection-refuse.

## 2. Where the bearer token is attached

**Decision**: `store/api.ts`'s existing `fetchBaseQuery` branch gets a
`prepareHeaders(headers, { getState })` that reads `state.staffSession.token` and, if
present, sets `Authorization: Bearer <token>`.

**Rationale**: The task explicitly says to replace the mock base query on the
*existing* `createApi` instance, not stand up a second one — and every current
consumer of that instance (payment-submission, transaction-history, the owner-\*
screens) needs to keep working unauthenticated exactly as today. A single
`prepareHeaders` that no-ops when there's no staff session satisfies both: staff
requests get the header, every other existing query is unaffected.

**Alternatives considered**: A second `createApi` instance scoped to staff endpoints —
rejected: duplicates the base-query/retry/cache-tag plumbing for no benefit, and the
task's own wording ("replace the mock base query") points at the one instance that
already exists.

## 3. Session persistence mechanism

**Decision**: `sessionStorage`, holding only `{ role, token }`.

**Rationale**: Spec Assumptions rule out a "remember me"/long-lived credential
(`localStorage` would survive browser restarts indefinitely for a dev-only mock
credential — too durable for what it is) but a reload shouldn't force a fresh login
mid-shift either. `sessionStorage` is the standard middle ground: survives a reload
within the same tab, gone when the tab closes. Card details are never written here —
only the session's own role/token, a deliberate scope boundary from FR-013.

**Alternatives considered**: In-memory only (React state/Redux, no storage) — rejected,
loses the session on every reload, which is needlessly disruptive for a shift-long dev
tool and not something the spec asked for. `localStorage` — rejected per spec
Assumptions (no long-lived credential storage).

## 4. Keeping the new staff area isolated from the existing PIN flow

**Decision**: `App.tsx` moves `<AuthGate>` to wrap only the existing `<Routes>` tree
(all current paths unchanged), and adds a new sibling route, `/staff`, rendering
`<StaffAuthGate><RoomBoard /></StaffAuthGate>` — its own gate, its own session slice,
no interaction with `AuthGate`/`authSlice`/`PinRegistration`/`PinUnlock` at all.

**Rationale**: The spec's own Assumptions are explicit that the PIN flow "remains
as-is, unused by this flow." Nesting the new area inside the existing `AuthGate` would
force every staff visit through an unrelated PIN pairing screen first; a sibling route
with its own gate is the smallest change that achieves true isolation — one `App.tsx`
edit (moving where `<AuthGate>` wraps), zero changes to any PIN-flow file.

**Alternatives considered**: Reusing `authSlice`/`AuthGate` by adding a `role` field to
it — rejected: conflates two unrelated auth mechanisms (device-paired PIN vs. a
dev-only role-picker JWT) in one slice/gate, exactly the kind of coupling that would
make the PIN flow harder to reason about later for no benefit now.

## 5. Stopping polling once a booking reaches a final state

**Decision**: A small custom hook (`useGetBookingQuery`, wrapping the generated
`useGetBookingQueryBase`) holds `pollingInterval` in component state, starting at
`10_000`; a `useEffect` watching the query's own `data.status` calls
`setPollingInterval(0)` once it's `CONFIRMED` or `CANCELLED`. The state change
re-renders with the new option value, which RTK Query's subscription picks up.

**Correction to this document's own earlier draft**: an initial version of this
decision assumed `pollingInterval` itself accepts a function evaluated against the
latest cache entry. Checked directly against the installed `@reduxjs/toolkit`
(2.3.x)'s own type definitions (`SubscriptionOptions.pollingInterval?: number` —
`node_modules/@reduxjs/toolkit/dist/query/index.d.ts`): it's a plain number, nothing
else. The component-state-driven approach above is the actual, commonly-used RTK
Query pattern for this — caught before writing any code against the wrong premise.

**Rationale**: Matches FR-012 exactly ("shown... through to its final outcome") without
an unbounded background poll continuing to hit the backend after there's nothing left
to learn. The room board itself has no such terminal state — it polls for as long as
it's mounted, per FR-008.

**Alternatives considered**: A fixed-count poll limit (e.g., "poll 10 times, then
stop") — rejected: arbitrary, and could stop before a slow backend settlement actually
resolves the booking, which the HELD→CONFIRMED/CANCELLED saga's own timing already
governs independently of this UI.

## 6. 401 / 403 / 503 handling

**Decision**:
- **401**: a small RTK Query middleware (in `store/index.ts`) inspects every rejected
  query/mutation action; if its `error.status === 401`, it dispatches
  `staffSession/sessionExpired` — `StaffAuthGate` reacts to that state and renders
  `LoginScreen`. One place, applies to every staff-area request uniformly.
- **403**: handled locally, per query/mutation, by the component that issued it (it
  already has the specific context — "you can't do that" belongs next to the action
  that was refused, not as a global redirect) — rendered as a plain inline message,
  session untouched.
- **503** (or a network failure): handled locally by `RoomBoard`/`BookingStatusView` —
  render `RetryBanner` in place of the stale content; RTK Query's own cache keeps
  retrying on the existing polling interval, so "retry" is really just "stay mounted
  and let the next poll tick land," with a manual "Retry now" button for impatience.

**Rationale**: 401 is identical everywhere (the session itself is invalid — there's
nothing query-specific about that), so centralizing it avoids repeating the same check
in every feature. 403 and 503 are each contextual to the specific action/view, so a
global interceptor would either lose that context or need to thread it back out —
simpler to let the component that already knows what it was trying to do handle its
own refusal or outage.

**Alternatives considered**: Handling 401 locally too, same as 403/503 — rejected:
would need the identical "go back to login" logic duplicated in every feature file
that calls the API, with no benefit over one middleware.

## 7. Additive room-board fields (approved in Clarifications)

**Decision**: `RoomBoardResponse.RoomBoardEntry` (Java record) gains three fields,
all `null` unless noted — no existing field renamed, retyped, or removed:

| Field | Present when | Source |
|---|---|---|
| `currentBookingId` | `status == OCCUPIED` | the room's `CHECKED_IN` booking's id (same row `getBoard()` already loads for `guestName`/`checkIn`/`checkOut` — just also expose its id) |
| `arrivingBookingId` | a `CONFIRMED` booking exists for this room with `checkIn == today` | a new `BookingRepository.findByStatusAndCheckIn(CONFIRMED, LocalDate.now())` query, mapped by `roomId` the same way the existing `CHECKED_IN` map already is |
| `arrivingGuestName` | same as `arrivingBookingId` | that booking's `guestName` |

A deliberately **separate** field from the existing `guestName` (which stays governed
by 2b's own FR-013: present only when `OCCUPIED`) — a room can legitimately have both
an occupant checking out today *and* a different guest's confirmed booking checking in
today (same-day turnover), so the two must be independently populated, never merged
into one field.

**Rationale**: This is the exact shape the Clarifications session approved. "Arriving
today" is scoped to the booking's `checkIn` date (not its confirmation date) because
that's the date front-desk actually cares about for a check-in action — a booking
confirmed weeks ahead for a future stay shouldn't show as actionable yet.

**Alternatives considered**: A separate `GET /api/v1/bookings?roomId=&status=` lookup
endpoint the frontend calls per room — rejected: more total round-trips for the board
(one per room instead of one call total), and a general booking-search endpoint is
explicitly out of scope / already a tracked backlog item, not something to half-build
as a side effect of this layer.

## 8. Frontend coverage gate

**Decision**: `vitest.config` (via `vite.config.ts`'s existing `test` block) adds a
`coverage.thresholds` of 100% lines/branches/functions/statements for every file this
layer touches, enforced by `npm run test:coverage` in CI (new frontend CI job, see
research.md §9).

**Rationale**: Constitution Principle II's 100% coverage gate is stated service- and
class-agnostically ("the non-negotiable gate for every class in every service") — the
project has held every backend service to it without exception, and the frontend is
not a documented exception. `@vitest/coverage-v8` (already a devDependency) supports
the same threshold mechanism Vitest's own docs describe, keeping this consistent with
JaCoCo's role on the backend side without introducing a new tool.

**Alternatives considered**: No enforced frontend threshold (test what seems
reasonable) — rejected as inconsistent with how every other part of this project
treats Principle II, and likely to quietly regress once this layer isn't the most
recent thing worked on.

## 9. Playwright placement and CI wiring

**Decision**: `frontend/e2e/staff-room-board.spec.ts` + `frontend/playwright.config.ts`,
run as `npm run e2e` inside `frontend/`. In CI: a new, separate `frontend-build-test`
job (lint, typecheck/build, `vitest run --coverage`) runs in parallel with the existing
backend job for speed; the Playwright smoke test itself is added as an additional step
*inside* the existing `build-test-e2e` job, right after `scripts/e2e-test.sh` passes —
reusing the same already-running `docker-compose` stack (which now includes the
standing `auth-mock` service from research.md §0 — the Playwright test logs in through
the real `/dev/login` endpoint via the UI, exactly like a real user, with no dependency
on `scripts/e2e-test.sh`'s own ephemeral mock).

**Rationale**: Playwright needs the real, live backend — piggybacking on the existing
e2e job's already-provisioned stack is the only way to get that without duplicating
Compose's startup/seeding/teardown in a second job. The Vitest/lint/build work, by
contrast, needs nothing from the backend and is pure overhead if it waits on the
slower backend job — hence its own parallel job.

**Alternatives considered**: One single job doing everything sequentially — rejected,
needlessly serializes fast frontend unit-level checks behind the slow backend build/e2e
run. A dedicated third job just for Playwright, with its own `docker compose up` —
rejected, duplicates setup/teardown the existing e2e job already does and roughly
doubles total CI wall-clock time for one smoke test.

## 10. Constitution Principle VIII (frontend monorepo) gap

Not a technical unknown to resolve here — recorded in `plan.md`'s Complexity Tracking
as an explicit, justified deviation. Noted in this document only as a pointer so it
isn't mistaken for an oversight: the existing `frontend/` is not restructured into
`apps/web`/`apps/mobile`/`packages/shared` by this plan.
