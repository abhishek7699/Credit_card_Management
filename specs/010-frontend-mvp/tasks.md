---

description: "Task list for the Frontend MVP (layer 2d, staff room board)"
---

# Tasks: Frontend MVP (Staff Room Board)

**Input**: Design documents from `specs/010-frontend-mvp/` (plan.md, spec.md,
research.md, data-model.md, contracts/, quickstart.md)

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/,
quickstart.md — all present.

**Tests**: Included, and not optional for this project. Constitution Principle II
(NON-NEGOTIABLE) mandates 100% test coverage for every class in every service; this
layer's own research.md §8 extends that to the frontend (Vitest coverage thresholds),
and keeps 100% JaCoCo for the one backend change, same as layers 2a/2b.

**Organization**: Tasks are grouped by user story (from spec.md: US1 = P1 sign-in +
live board, US2 = P2 book a room, US3 = P3 check-in, US4 = P4 check-out, US5 = P5
room-status updates).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1-US5)
- File paths are exact, relative to the repository root

## Path Conventions

Additive changes inside the existing `frontend/` app (no restructuring — plan.md's
Complexity Tracking) and the existing `services/booking-service/` module (no new
module), plus one new, independent docker-compose service (`scripts/dev-auth-mock.py`)
— per plan.md's Project Structure.

---

## Phase 1: Setup

**Purpose**: Nothing in Foundational or any user story can be clicked through by a
human until a standing login mechanism and a correctly-routed dev server exist
(research.md §0/§1).

- [X] T001 `scripts/dev-auth-mock.py`: stdlib `http.server`, generates one RSA keypair
      at process start, serves `GET /.well-known/jwks.json` + `GET /oauth2/jwks` (JWKS)
      and `POST /dev/login` (`{"role": "FRONT_DESK"|"HOUSEKEEPING"}` →
      `{"token": "<RS256 JWT>"}`, claims matching `scripts/e2e-test.sh`'s own
      convention: `sub`, `iss: http://auth-service:9000`, `iat`, `exp` (+4h),
      `authorities: ["ROLE_<role>"]`); `400` for a missing/invalid `role` (research.md §0)
- [X] T002 `docker-compose.yml`: add an `auth-mock` service (`container_name:
      auth-service`, `image: python:3.11-slim`, `command` pip-installs `cryptography`
      then runs T001's script, mounted read-only), port-mapped for host access —
      `scripts/e2e-test.sh` is NOT modified (it already force-recreates/removes any
      container named `auth-service` regardless of what else is running)
- [X] T003 `SECURITY.md`: document the new standing `/dev/login` endpoint under a
      "Mock JWT issuer" update — dev-only, no password/MFA, must never be exposed
      outside local dev (same spirit as the existing entry, now covering both the
      standing service and `scripts/e2e-test.sh`'s separate ephemeral one)
- [X] T004 [P] `frontend/vite.config.ts`: correct the dev proxy — `/api` →
      `http://localhost:8087` (not `:8088`, research.md §1), add `/dev` →
      `http://localhost:9000` (T001's service) so `staffAuthApi.ts` (T009) can call a
      relative path the same way the rest of the app does
- [X] T005 [P] `frontend/.env.example`: document `VITE_API_BASE_URL` (defaults to the
      proxy target above) for later non-local deployment
- [X] T006 [P] `frontend/package.json`: add `msw` and `@playwright/test` dev
      dependencies; add an `"e2e": "playwright test"` script; add
      `coverage.thresholds` (100% lines/branches/functions/statements) to the `test`
      block in `vite.config.ts` (research.md §8)

**Checkpoint**: `docker compose up -d --build` brings up a stack where
`curl -X POST localhost:9000/dev/login -d '{"role":"FRONT_DESK"}'` returns a token,
and `frontend`'s dev server proxies `/api` and `/dev` to the right ports.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared types, state, routing, and error-handling plumbing every user
story depends on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T007 [P] `frontend/src/types/staff.ts`: `StaffRole`, `RoomStatus`,
      `RoomBoardEntry` (full extended shape per data-model.md — including
      `currentBookingId`/`arrivingBookingId`/`arrivingGuestName`, even though no story
      before US3/US4 populates the latter two from the backend yet), `RoomBoardFloor`,
      `BookingStatus`, `Booking`, `BookingFormValues`
- [X] T008 [P] `frontend/src/features/staff-session/staffSessionSlice.ts`: `{role,
      token, status: 'anonymous'|'authenticated'|'expired'}`; `login`,
      `sessionExpired`, `logout` reducers; hydrates once from `sessionStorage` at
      construction, persists on `login`/clears on `sessionExpired`/`logout` (data-model.md §3)
- [X] T009 `frontend/src/features/staff-session/staffAuthApi.ts`: RTK Query mutation
      `login(role)` → `POST /dev/login` (via the `/dev` proxy from T004), dispatches
      `staffSession/login` with the returned token on success (depends on T004, T008)
- [X] T010 `frontend/src/store/api.ts`: `fetchBaseQuery`'s `prepareHeaders` reads
      `state.staffSession.token` and sets `Authorization: Bearer <token>` when present
      — every other existing consumer of this instance is unaffected when absent
      (research.md §2) (depends on T008)
- [X] T011 `frontend/src/store/index.ts`: register the `staffSession` reducer; add a
      small RTK middleware that dispatches `staffSession/sessionExpired` whenever a
      rejected action's `error.status === 401` (research.md §6) (depends on T008)
- [X] T012 [P] `frontend/src/components/RetryBanner.tsx`: shared "can't reach the
      server, retrying…" banner + manual "Retry now" button, used by any view polling
      a now-unreachable endpoint (research.md §6)
- [X] T013 `frontend/src/features/staff-session/LoginScreen.tsx`: role picker
      (FRONT_DESK/HOUSEKEEPING) + sign-in button, calls T009's mutation, shows its
      error inline on failure (depends on T009)
- [X] T014 `frontend/src/features/staff-session/StaffAuthGate.tsx`: renders
      `LoginScreen` unless `staffSession.status === 'authenticated'` (depends on T008, T013)
- [X] T015 `frontend/src/App.tsx`: move `<AuthGate>` to wrap only the existing
      `<Routes>` tree (every current path unchanged); add a new sibling route,
      `/staff`, rendering `<StaffAuthGate>` (research.md §4) (depends on T014)
- [X] T016 [P] `frontend/src/mocks/handlers.ts` + `frontend/src/mocks/server.ts`: MSW
      node server wired into `frontend/src/setupTests.ts`, ready for per-test handler
      overrides

### Tests for Foundational

- [X] T017 [P] `frontend/src/features/staff-session/staffSessionSlice.test.ts`: login/
      sessionExpired/logout transitions; `sessionStorage` is written on login and
      cleared on sessionExpired/logout; a prior `sessionStorage` entry hydrates
      `status: 'authenticated'` on construction
- [X] T018 [P] `frontend/src/features/staff-session/LoginScreen.test.tsx`: both roles
      selectable; submit calls the login mutation with the selected role; a failed
      login shows an inline error and does not navigate anywhere
- [X] T019 [P] `frontend/src/features/staff-session/StaffAuthGate.test.tsx`: renders
      `LoginScreen` when `status` is `'anonymous'` or `'expired'`; renders children
      when `'authenticated'`
- [X] T020 [P] `frontend/src/store/api.test.ts`: a request made with a staff session
      present carries the bearer header; one made with no session doesn't; a `401`
      response dispatches `sessionExpired`
- [X] T021 [P] `frontend/src/App.test.tsx`: visiting any existing route (e.g. `/`,
      `/dashboard`) still goes through the PIN `AuthGate` unchanged; visiting `/staff`
      does not

**Checkpoint**: Foundation compiles, all Foundational tests pass at 100% coverage for
the files above; `/staff` shows the login screen, existing routes are provably
unaffected.

---

## Phase 3: User Story 1 — Staff Log In and View the Live Room Board (Priority: P1) 🎯 layer MVP

**Goal**: Front-desk or housekeeping staff sign in and see the real room board,
refreshing on its own.

**Independent Test**: Sign in as each role, confirm the board renders every room from
the real backend grouped and colored correctly, and confirm an externally-made status
change appears within one polling cycle (spec.md Story 1).

### Tests for User Story 1 ⚠️ (write first, confirm they fail)

- [X] T022 [P] [US1] `frontend/src/features/room-board/roomBoardApi.test.ts` (MSW):
      fetches and shapes `GET /api/v1/rooms/board`; refetches after the polling
      interval elapses (fake timers)
- [X] T023 [P] [US1] `frontend/src/features/room-board/RoomBoard.test.tsx` (MSW):
      renders every room grouped by floor, ordered by position, colored by status,
      guest name shown only when `OCCUPIED`; a `503`/network error from the mock
      renders `RetryBanner` instead of stale/blank content; a `401` mid-session ends
      with `LoginScreen` rendered (via the real store + T011's middleware, not a stub)

### Implementation for User Story 1

- [X] T024 [US1] `frontend/src/features/room-board/roomBoardApi.ts`: RTK Query query
      `getBoard()` → `GET /api/v1/rooms/board`, `pollingInterval: 10_000` (depends on T007, T010)
- [X] T025 [US1] `frontend/src/features/room-board/RoomTile.tsx`: one room cell —
      color by status, guest name only when `OCCUPIED` (depends on T007)
- [X] T026 [US1] `frontend/src/features/room-board/RoomBoard.tsx`: renders the grid
      from T024, floor grouping/position ordering as returned, `RetryBanner` (T012) on
      error, loading state while the first fetch is in flight (depends on T024, T025, T012)
- [X] T027 [US1] Wire `RoomBoard` as the `/staff` route's content inside `StaffAuthGate` (depends on T015, T026)

**Checkpoint**: `docker compose up -d --build` + `npm run dev` in `frontend/` → sign in
at `/staff`, see the live board, watch an externally-made status change (via `curl`
with a `/dev/login` token) appear within 10 seconds. Tests passing, 100% coverage.
**Per spec.md's own priority ordering, this is independently demonstrable before US2.**

---

## Phase 4: User Story 2 — Staff Book a Room for a Guest (Priority: P2)

**Goal**: Front-desk staff click a vacant, clean room and submit a real booking,
watching it resolve to confirmed or cancelled.

**Independent Test**: As Front Desk, click a `VACANT_CLEAN` room, submit a booking,
confirm the UI tracks it from held to its final state with no reload, and that no
card detail is ever retained anywhere in the app (spec.md Story 2).

### Tests for User Story 2 ⚠️

- [X] T028 [P] [US2] `frontend/src/features/booking/BookingForm.test.tsx` (MSW):
      validates required fields; submits the exact payload shape `POST
      /api/v1/bookings` expects; **a dedicated assertion that after submit (success
      and failure both), no substring of the entered card number/CVV appears anywhere
      in `sessionStorage`, `localStorage`, or the Redux store's serialized state**
      (FR-013); HOUSEKEEPING-signed-in sessions never render this form at all
- [X] T029 [P] [US2] `frontend/src/features/booking/BookingStatusView.test.tsx` (MSW +
      fake timers): polls `GET /api/v1/bookings/{id}` every 10s while `HELD`; stops
      polling once `CONFIRMED` or `CANCELLED`; renders the final state plainly
- [X] T030 [P] [US2] `frontend/src/features/room-board/RoomBoard.test.tsx`
      (extended): clicking a `VACANT_CLEAN` room opens `BookingForm` for Front Desk;
      offers no booking action at all for Housekeeping

### Implementation for User Story 2

- [X] T031 [US2] `frontend/src/features/booking/bookingApi.ts`: RTK Query mutation
      `createBooking` (`POST /api/v1/bookings`) and query `getBooking(id)` (`GET
      /api/v1/bookings/{id}`, dynamic `pollingInterval`: `10_000` while `HELD`, `0`
      once final — research.md §5) (depends on T007, T010)
- [X] T032 [US2] `frontend/src/features/booking/BookingForm.tsx`: guest name/dates/
      card fields (local `useState` only, per data-model.md §3 — never dispatched to
      Redux), submits via T031, hands off to `BookingStatusView` on success (depends on T031)
- [X] T033 [US2] `frontend/src/features/booking/BookingStatusView.tsx`: renders the
      polled booking's progress (`HELD` → `CONFIRMED`/`CANCELLED`) (depends on T031)
- [X] T034 [US2] `frontend/src/features/room-board/RoomActionPanel.tsx` (new): the
      per-room action surface clicking a tile opens — for a `VACANT_CLEAN` room and a
      FRONT_DESK session, renders `BookingForm`; for HOUSEKEEPING, renders nothing
      actionable for that room (depends on T032)
- [X] T035 [US2] `RoomTile`/`RoomBoard` wire click-through to `RoomActionPanel`
      (depends on T026, T034)

**Checkpoint — STOP HERE for this turn.** Setup + Foundational + User Story 1 (live
board) + User Story 2 (booking) complete: a staff member can sign in, watch the real
board, book a room, and see it resolve to confirmed or cancelled — entirely in the
browser. Show tests passing, coverage, and the exact commands to click through it
manually before continuing to Phase 5.

---

## Phase 5: User Story 3 — Staff Check a Guest In (Priority: P3)

**Goal**: Front-desk staff check in any room the board shows as having a confirmed
booking arriving today — discoverable from the board itself, not session memory.

**Independent Test**: Carry a booking through to confirmed, check it in from the board
in a *different* browser session than the one that booked it, confirm the room becomes
occupied with the guest's name (spec.md Story 3).

### Tests for User Story 3 ⚠️

- [X] T036 [P] [US3] `services/booking-service/src/test/java/com/creditcard/booking/repository/BookingRepositoryTest.java`
      (extended, or integration-level per existing convention): `findByStatusAndCheckIn`
      returns only `CONFIRMED` bookings whose `checkIn` matches the given date
      — done as an integration test (real CockroachDB) in `RoomStatusFlowIT.java`
      instead, per this task's own stated flexibility; also caught a real bug this
      way — Spring Data's method-name parser read "...CheckIn" as property "check" +
      the reserved `In` keyword, not the literal `checkIn` field, failing with
      `PropertyReferenceException`. Fixed with an explicit `@Query`.
- [X] T037 [P] [US3] `services/booking-service/.../service/RoomServiceTest.java`
      (extended): `getBoard()` populates `arrivingBookingId`/`arrivingGuestName` only
      for a room with a `CONFIRMED` booking checking in today; leaves both `null`
      otherwise, including for a `CONFIRMED` booking arriving on a *different* date
- [X] T038 [P] [US3] `frontend/src/features/room-board/RoomActionPanel.test.tsx`
      (extended, MSW): a room with `arrivingBookingId` set offers check-in for Front
      Desk, calling `POST /api/v1/bookings/{arrivingBookingId}/check-in`; offers
      nothing for Housekeeping; a backend rejection shows a plain message and the
      board's next poll corrects the displayed status

### Implementation for User Story 3

- [X] T039 [US3] `services/booking-service/.../repository/BookingRepository.java`: add
      `findByStatusAndCheckIn(BookingStatus status, LocalDate checkIn)`
- [X] T040 [US3] `services/booking-service/.../controller/dto/RoomBoardResponse.java`:
      add `arrivingBookingId`, `arrivingGuestName` fields to `RoomBoardEntry` (depends on T039)
- [X] T041 [US3] `services/booking-service/.../service/RoomService.java`: `getBoard()`
      builds an `arrivingByRoomId` map from T039 (keyed by `roomId`, queried with
      `LocalDate.now()`) and populates the two new fields independently of the
      existing occupant map (depends on T039, T040)
- [X] T042 [US3] `frontend/src/features/booking/bookingApi.ts`: add `checkIn(bookingId)`
      mutation (`POST /api/v1/bookings/{id}/check-in`) (depends on T031)
- [X] T043 [US3] `frontend/src/features/room-board/RoomActionPanel.tsx`: for a room
      with `arrivingBookingId` set, Front Desk gets a check-in action calling T042
      (depends on T034, T042)

**Checkpoint**: Run `./gradlew :services:booking-service:test
:services:booking-service:jacocoTestCoverageVerification` — 100% coverage maintained.
Manually: book a room as one session, confirm it, check it in from a *second*,
freshly-signed-in session — proving it's board-driven.

---

## Phase 6: User Story 4 — Staff Check a Guest Out (Priority: P4)

**Goal**: Front-desk staff check out any room the board shows as occupied —
discoverable from the board itself, regardless of which session checked that guest in.

**Independent Test**: From an occupied room on the board — one checked in by a
different session — check it out, confirm the room becomes dirty with no payment step
(spec.md Story 4).

### Tests for User Story 4 ⚠️

- [X] T044 [P] [US4] `services/booking-service/.../service/RoomServiceTest.java`
      (extended): `getBoard()` populates `currentBookingId` for an `OCCUPIED` room
      (the same `CHECKED_IN` booking already backing `guestName`), `null` otherwise
- [X] T045 [P] [US4] `frontend/src/features/room-board/RoomActionPanel.test.tsx`
      (extended, MSW): an `OCCUPIED` room offers check-out for Front Desk, calling
      `POST /api/v1/bookings/{currentBookingId}/check-out`, with no payment-related
      element rendered anywhere in that flow; offers nothing for Housekeeping

### Implementation for User Story 4

- [X] T046 [US4] `services/booking-service/.../controller/dto/RoomBoardResponse.java`:
      add `currentBookingId` to `RoomBoardEntry` (depends on T040)
- [X] T047 [US4] `services/booking-service/.../service/RoomService.java`: `getBoard()`
      sets `currentBookingId` from the existing `occupantByRoomId` map's entry (the
      booking id was already loaded for `guestName`/`checkIn`/`checkOut` — just also
      expose it) (depends on T046)
- [X] T048 [US4] `frontend/src/features/booking/bookingApi.ts`: add `checkOut(bookingId)`
      mutation (`POST /api/v1/bookings/{id}/check-out`) (depends on T031)
- [X] T049 [US4] `frontend/src/features/room-board/RoomActionPanel.tsx`: for an
      `OCCUPIED` room, Front Desk gets a check-out action calling T048, rendering no
      payment/charge/balance element (depends on T034, T048)

**Checkpoint**: 100% backend coverage maintained. Manually: check out a room that a
*different* session checked in — proving check-out is board-driven too.

---

## Phase 7: User Story 5 — Staff Update Room Status (Priority: P5)

**Goal**: Either role marks a dirty room clean, or takes a room into/out of
maintenance, from the board.

**Independent Test**: From a dirty room, mark it clean and confirm it becomes
bookable again; mark a clean/dirty room under maintenance and confirm it disappears
from what's bookable (spec.md Story 5).

### Tests for User Story 5 ⚠️

- [ ] T050 [P] [US5] `frontend/src/features/room-board/roomStatusApi.test.ts` (MSW):
      `PATCH /api/v1/rooms/{id}/status` called with the expected body
- [ ] T051 [P] [US5] `frontend/src/features/room-board/RoomActionPanel.test.tsx`
      (extended, MSW): a `DIRTY` room offers "mark clean" for either role; a
      `VACANT_CLEAN`/`DIRTY` room offers "mark maintenance"; a `MAINTENANCE` room
      offers "mark clean"; no status action is ever offered for an `OCCUPIED` room; a
      `409` response shows a plain message and leaves the displayed status to the next
      poll's correction

### Implementation for User Story 5

- [ ] T052 [US5] `frontend/src/features/room-board/roomStatusApi.ts`: RTK Query
      mutation `updateStatus(roomId, status)` (`PATCH /api/v1/rooms/{id}/status`) (depends on T007, T010)
- [ ] T053 [US5] `frontend/src/features/room-board/RoomActionPanel.tsx`: status-change
      actions per FR-018/FR-019's allowed-transition set, offered to both roles,
      calling T052 (depends on T034, T052)

**Checkpoint**: All five user stories independently functional — the full staff
lifecycle (sign in → board → book → confirm → check-in → check-out → clean) works
end to end through the UI alone.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: The one new e2e smoke test, CI wiring, and documentation.

- [ ] T054 [P] Run every scenario in `specs/010-frontend-mvp/quickstart.md` end to end
- [ ] T055 `frontend/playwright.config.ts` + `frontend/e2e/staff-room-board.spec.ts`:
      sign in (Front Desk) → book a vacant room → booking reaches `CONFIRMED` → check
      it in → board shows that room `OCCUPIED` (research.md §9)
- [ ] T056 `.github/workflows/ci.yml`: new parallel `frontend-build-test` job (Node
      setup, `npm ci`, lint, `tsc -b`/`vite build`, `npm run test:coverage`); add the
      Playwright smoke test (T055) as a step inside the existing `build-test-e2e` job,
      immediately after `scripts/e2e-test.sh` passes, reusing that job's already-running
      stack (research.md §9)
- [ ] T057 [P] `README.md`: add a "Frontend" section — what `/staff` is, the exact
      commands to run it against the real backend, and a pointer to this layer in the
      Roadmap/services list
- [ ] T058 Verify 100% coverage gates (Vitest for `frontend/`, JaCoCo for
      `booking-service`) and fix any gap
- [ ] T059 Confirm no change was needed to any other service, `k8s/`, or the owner-\*
      prototype screens — verify only

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately.
- **Foundational (Phase 2)**: Depends on Setup (T004's corrected proxy, T001's login
  endpoint) — BLOCKS all user stories.
- **User Story 1 (Phase 3)**: Depends on Foundational only. **Independently
  demonstrable on its own.**
- **User Story 2 (Phase 4)**: Depends on Foundational; reuses US1's `RoomBoard`/
  `RoomTile` for the click target but adds no dependency on US1's own task
  completion beyond that shared file. **This turn stops after this phase.**
- **User Story 3 (Phase 5)**: Depends on Foundational and US2's `bookingApi.ts` (T031,
  same file extended) and `RoomActionPanel` (T034, same file extended).
- **User Story 4 (Phase 6)**: Depends on Foundational, US2's `bookingApi.ts`, and
  US3's `RoomBoardResponse.java`/`RoomActionPanel.tsx` (same files extended again) —
  independent of US3's own backend fields otherwise.
- **User Story 5 (Phase 7)**: Depends on Foundational and US2's `RoomActionPanel` only
  — no dependency on US3/US4's backend changes.
- **Polish (Phase 8)**: Depends on all five user stories being complete.

### Within Each User Story

- Tests are written first and confirmed to fail before implementation (Principle II).
- Types/state (Foundational) before API slices; API slices before components.

### Parallel Opportunities

- Setup T004-T006 (different files) run in parallel.
- Foundational T007, T008, T012, T016 (different files, no dependencies on each
  other) run in parallel; T017-T021 (tests, different files) run in parallel.
- All test tasks within a story marked [P] run in parallel (different test files).
- User Stories 3, 4, and 5 can all start as soon as US2 is done — they extend
  different (or additively-shared, non-conflicting) files and don't depend on each
  other, only on Phase 2 + US2.

---

## Parallel Example: User Story 1

```bash
# Tests for User Story 1, together:
Task: "roomBoardApi test in frontend/src/features/room-board/roomBoardApi.test.ts"
Task: "RoomBoard test in frontend/src/features/room-board/RoomBoard.test.tsx"
```

---

## Implementation Strategy

### This turn: through the explicit checkpoint

1. Phase 1: Setup (standing login + corrected proxy)
2. Phase 2: Foundational (blocks everything)
3. Phase 3: User Story 1 (live board)
4. Phase 4: User Story 2 (booking)
5. **STOP**: tests passing, coverage, the exact commands to run the backend and
   frontend for a manual click-through — then wait for the go-ahead before Phase 5.

### Afterward: Incremental Delivery

1. Phase 5: User Story 3 (check-in) → validate independently
2. Phase 6: User Story 4 (check-out) → validate independently
3. Phase 7: User Story 5 (room-status updates) → validate independently
4. Phase 8: Polish → Playwright smoke test, CI wiring, README, coverage gate

---

## Notes

- Per CLAUDE.md's per-phase approval convention, each phase above is implemented and
  committed as a whole before stopping — not task by task.
- Avoid: implementing Phase 5+ work before Phase 4's explicit checkpoint is shown and
  approved.
