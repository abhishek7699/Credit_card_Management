# Implementation Plan: Frontend MVP (Staff Room Board)

**Branch**: `010-frontend-mvp` | **Date**: 2026-10-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/010-frontend-mvp/spec.md`

## Summary

Connect the existing React prototype's staff tooling to the real backend: a new,
self-contained `/staff` area (dev-only JWT login with a FRONT_DESK/HOUSEKEEPING role
picker, a polling room board, and the book → confirm → check-in → check-out → clean
cycle) sitting alongside — not inside — the existing PIN-auth-gated prototype, which
stays untouched and out of scope. The existing `store/api.ts` RTK Query instance gets
a real `fetchBaseQuery` (already present, just unauthenticated and pointed at a
build-time default) wired to send the staff session's bearer token and routed through
a corrected Vite dev proxy. The one backend change approved in Clarifications is an
additive extension to `booking-service`'s `GET /api/v1/rooms/board` response
(`currentBookingId`, `arrivingBookingId` + guest name), needed so check-in/check-out
work for any room the board shows, not only ones a given staff session happened to
create.

**One thing research surfaced that wasn't in the original ask**: "the existing
auth-mock" the login screen is supposed to call doesn't exist as a standing service —
only as `scripts/e2e-test.sh`'s own ephemeral, host-signed, per-run container (see
research.md §0). Without something a browser can actually call, there is no way to log
in at all on a plain `docker compose up`. This plan adds a small, persistent
`auth-mock` docker-compose service (JWKS + a new `POST /dev/login`) to close that gap
— `scripts/e2e-test.sh` itself is untouched and keeps working exactly as today.

## Technical Context

**Language/Version**: TypeScript 5.6 (strict mode, already enforced in
`frontend/tsconfig.json`), React 18.3. Backend touch is Java 17 / Spring Boot 3.2
(existing `booking-service`, additive-only).

**Primary Dependencies**: Vite 5, Redux Toolkit + RTK Query 2.3, react-router-dom 6,
Tailwind CSS (all existing in `frontend/`). New, test-only: `msw` (component-test API
mocking), `@playwright/test` (one smoke test).

**Storage**: No new persisted backend data — the additive board fields are derived at
read time from `booking-service`'s existing `bookings` table (no migration). Client
side, the only new persisted state is the dev staff session (role + token) in
`sessionStorage` — not business data, cleared when the tab closes. One new,
dev-only, unpersisted RSA keypair lives in-memory in the new `auth-mock` container for
its process lifetime (research.md §0) — nothing it issues is durable across a restart,
by design.

**Testing**: Vitest + React Testing Library + MSW for new/changed frontend
components (matching the existing `frontend/` convention); one Playwright smoke test
against a live `docker-compose` stack. JUnit 5 + Mockito + Testcontainers (CockroachDB)
+ JaCoCo 100% instruction/branch coverage for the `booking-service` additive change
(same convention as layers 2a/2b).

**Target Platform**: Modern desktop browser (Chrome/Edge/Firefox) — B2B internal staff
tool. No mobile/responsive work this layer (spec Assumptions).

**Project Type**: Web application — existing React SPA (`frontend/`) + existing
8-service backend (`services/`, unchanged in topology; one service's response
additively extended).

**Performance Goals**: Board and booking-status views visibly current within one
10-second polling interval (spec SC-001/SC-002); no new backend throughput requirement.

**Constraints**: Card details reach the backend directly and are never placed in
Redux state, `sessionStorage`/`localStorage`, or any log statement the app writes
(FR-013) — this is the one rule in this layer with real security weight, same spirit
as Constitution Principle I's zero-raw-card-data rule for the backend. No websocket or
server push (Assumptions — polling only). No restructuring of `frontend/` into the
Constitution's `apps/web` + `apps/mobile` + `packages/shared` monorepo shape — see
Complexity Tracking below.

**Scale/Scope**: 18 rooms / 3 floors (existing seed, unchanged), one staff session per
browser tab, 5 user stories, 1 new frontend route subtree, 1 additively-extended
backend endpoint.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|---|---|---|
| I. Tokenization & Zero Raw Card Data | PASS | Card details are forwarded straight to `POST /api/v1/bookings` and never held in Redux/storage/logs (FR-013) — the frontend-side mirror of this principle; no change to how `booking-service`/`transaction-service` handle or tokenize the PAN. |
| II. Test-First / 100% Coverage | PASS | New backend code (the board-query additive fields) follows the existing write-test-first, 100% JaCoCo convention. New frontend code gets Vitest + RTL + MSW coverage (research.md documents the chosen threshold mechanism, since there's no JaCoCo equivalent in this stack). |
| III. Idempotent Event Consumers | N/A | No new Kafka consumer or event in this layer. |
| IV. Iterative One-Step Development | PASS | Implemented and committed phase-by-phase per CLAUDE.md's standing per-phase-approval convention, same as layers 2a/2b. |
| V. Layered Microservice Architecture & Communication Tiers | PASS | The one backend change stays inside `booking-service`'s existing Controller→Service→Repository layering and its existing external REST tier (gateway → plain REST, no gRPC/Kafka involved) — no new service, no new tier. |
| VI. Observability & Audit Trail | N/A | No new financial operation; existing audit logging for booking/payment actions is untouched. |
| VII. Proto-First Service Contracts | N/A | No new or changed gRPC contract. |
| VIII. Frontend Code Sharing — Web + Mobile | **DEVIATION (justified below)** | Constitution mandates `frontend/apps/web` + `apps/mobile` + `packages/shared`. The actual `frontend/` is — and stays, in this plan — a flat single app. See Complexity Tracking. |

## Project Structure

### Documentation (this feature)

```text
specs/010-frontend-mvp/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── api.md           # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit-tasks — not created by this command)
```

### Source Code (repository root)

```text
scripts/
└── dev-auth-mock.py                                 # NEW — standing JWKS + POST /dev/login
                                                        #   (research.md §0); scripts/e2e-test.sh
                                                        #   is unchanged and unaffected

docker-compose.yml                                    # + auth-mock service (container_name:
                                                        #   auth-service), matching the hostname
                                                        #   AUTH_SERVICE_JWKS_URI already expects
SECURITY.md                                            # + note on the new standing dev-login
                                                        #   endpoint (dev-only, same spirit as
                                                        #   its existing "Mock JWT issuer" entry)

services/booking-service/                           # existing, additive-only change
├── src/main/java/com/creditcard/booking/
│   ├── controller/dto/RoomBoardResponse.java        # + currentBookingId, arrivingBookingId,
│   │                                                 #   arrivingGuestName fields
│   ├── repository/BookingRepository.java            # + findByStatusAndCheckIn(status, date)
│   └── service/RoomService.java                     # getBoard() populates the new fields
└── src/test/java/com/creditcard/booking/             # extended unit/integration tests,
                                                        # 100% coverage maintained

frontend/src/
├── App.tsx                                    # restructured: AuthGate now wraps only the
│                                               #   existing routes; new sibling /staff route
├── store/
│   ├── api.ts                                 # fetchBaseQuery gains prepareHeaders (bearer
│   │                                           #   token) + an onQueryStarted 401 listener
│   └── index.ts                               # + staffSession reducer
├── features/
│   ├── staff-session/
│   │   ├── staffSessionSlice.ts               # NEW — {role, token, status}
│   │   ├── staffAuthApi.ts                    # NEW — login(role) against the auth-mock
│   │   ├── LoginScreen.tsx                     # NEW — role picker + sign-in
│   │   ├── StaffAuthGate.tsx                   # NEW — gates the /staff subtree
│   │   └── *.test.tsx
│   ├── room-board/
│   │   ├── roomBoardApi.ts                    # NEW — GET /api/v1/rooms/board, 10s polling
│   │   ├── roomStatusApi.ts                   # NEW — PATCH /api/v1/rooms/{id}/status
│   │   ├── RoomBoard.tsx                       # NEW — grid by floor, role-aware click routing
│   │   ├── RoomTile.tsx                        # NEW — one room cell
│   │   ├── RoomActionPanel.tsx                 # NEW — action panel for a clicked room
│   │   ├── RetryBanner.tsx                     # NEW — shared 503/unreachable indicator
│   │   └── *.test.tsx
│   └── booking/
│       ├── bookingApi.ts                      # NEW — create/get/check-in/check-out
│       ├── BookingForm.tsx                     # NEW — guest name, dates, card
│       ├── BookingStatusView.tsx               # NEW — polls until CONFIRMED/CANCELLED
│       └── *.test.tsx
├── types/
│   └── staff.ts                               # NEW — RoomBoardEntry (extended), Booking,
│                                               #   StaffRole view-model types
└── mocks/
    ├── handlers.ts                            # NEW — MSW request handlers for the above
    └── server.ts                              # NEW — MSW node server, wired into setupTests.ts

frontend/
├── playwright.config.ts                       # NEW
├── e2e/
│   └── staff-room-board.spec.ts               # NEW — login → book → CONFIRMED → check-in →
│                                               #   board shows OCCUPIED
├── vite.config.ts                             # dev proxy corrected to :8087 (see research.md)
├── .env.example                                # NEW — VITE_API_BASE_URL documented
└── package.json                               # + msw, @playwright/test; + e2e script

.github/workflows/ci.yml                        # + parallel frontend-build-test job;
                                                  #   + Playwright step in the existing e2e job
README.md                                        # + "Frontend" section
```

**Structure Decision**: Single existing web application (`frontend/`), extended with
three new feature folders and one new top-level route subtree, following the
project's established `features/<name>/<name>Api.ts` + component convention
unchanged. The one backend touch stays inside `booking-service`'s existing package
structure. No new service, no new top-level directory beyond the frontend's own `e2e/`
for the Playwright spec.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| Constitution Principle VIII (frontend must be an `apps/web` + `apps/mobile` + `packages/shared` monorepo) is not adopted here — `frontend/` stays a flat single app. | `frontend/` already exists as a flat app, predating the project's pivot from "payment gateway + mobile app" to the current CLAUDE.md Roadmap (payment core + layered hotel features, no mobile app planned anywhere in that Roadmap). This layer's own instruction is explicitly to connect the *existing* prototype, not restructure it. | Restructuring into `apps/web`/`apps/mobile`/`packages/shared` now would mean inventing a `packages/shared` with no second consumer (no mobile app exists, scheduled, or requested) and moving every existing file — a large, speculative, high-blast-radius refactor that contradicts CLAUDE.md's own "make it operable first... no speculative features" working approach, for a structural requirement nothing in the current Roadmap needs yet. Flagged here explicitly rather than silently ignored or silently blocking this layer; worth an explicit constitution amendment if/when a mobile app actually enters the Roadmap. |
