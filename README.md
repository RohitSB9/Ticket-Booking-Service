# Ticket Seat Locking System

A microservices-based ticket booking platform demonstrating distributed locking,
event-driven service communication (Kafka), and race-condition handling under
concurrent load. Built to mirror real-world booking systems (e.g. Ticketmaster).

## Architecture (target end state)

```
                 ┌─────────────────┐
   React SPA ──▶ │  Event Service   │  REST + Postgres
                 └─────────────────┘
                          │
                          ▼
                 ┌─────────────────┐        Redis (seat locks, TTL) — step 3
   React SPA ──▶ │ Reservation Svc  │ ─────▶ SETNX seat:{id} -> userId
                 └─────────────────┘         (this step: no lock yet)
                          │ publishes SeatReserved
                          ▼
                 ┌─────────────────┐
                 │   Kafka broker   │
                 └─────────────────┘
                          │ consumes SeatReserved
                          ▼
                 ┌─────────────────┐        Stripe test mode
                 │  Payment Service │ ─────▶ publishes PaymentConfirmed /
                 └─────────────────┘         PaymentFailed
```

## Status

- [x] Step 1: Event Service (this repo) — CRUD for events/seats, Postgres-backed
- [x] Step 2: Wire Reservation Service to Event Service, no locking yet
- [ ] Step 3: Add Redis distributed locking to Reservation Service
- [ ] Step 4: Add Kafka + Payment Service
- [ ] Step 5: Expiry sweep for abandoned reservations
- [ ] Step 6: Load test proving no double-booking under concurrency
- [ ] Step 7: Dockerize, deploy, write architecture doc

## Event Service — what it does

Owns event and seat inventory. Seats are generated automatically when an event
is created (`rows` x `seatsPerRow`), labeled like `A1`, `A2`, ... `B1`, `B2`.
Every seat starts as `AVAILABLE`. The Reservation Service (step 2+) will call
into this service to read seat state and will own the actual reserve/release
transitions once Redis locking is introduced.

A `@Version` field on `Seat` gives an optimistic-lock backstop at the DB layer,
independent of the Redis lock the Reservation Service will use — worth
mentioning in interviews as defense-in-depth against lost updates.

### Endpoints

| Method | Path                                          | Description                                  |
|--------|------------------------------------------------|-----------------------------------------------|
| POST   | `/api/events`                                 | Create an event and auto-generate its seats  |
| GET    | `/api/events`                                 | List all events                              |
| GET    | `/api/events/{eventId}`                       | Get one event with full seat list            |
| GET    | `/api/events/{eventId}/seats`                 | List seats (add `?availableOnly=true`)       |
| GET    | `/api/events/{eventId}/seats/{seatId}`        | Get one seat                                 |
| PATCH  | `/api/events/{eventId}/seats/{seatId}`        | Set a seat's status (used by Reservation Service) |

The PATCH endpoint does no transition validation — it writes whatever status
it's given. That's intentional: in step 2 the Reservation Service does a
check-then-act against this endpoint with nothing in between, so double
bookings are possible under concurrent load. Step 3 fixes this with a Redis
lock in the Reservation Service, not with validation here.

Swagger UI: `http://localhost:8081/swagger-ui.html`

### Example request

```bash
curl -X POST http://localhost:8081/api/events \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Dune: Part Three",
    "venue": "Cineplex Yonge-Dundas",
    "startsAt": "2026-08-15T19:30:00Z",
    "rows": 5,
    "seatsPerRow": 10
  }'
```

## Reservation Service — what it does

Owns the reservation lifecycle. On create, it calls Event Service to check
the seat is `AVAILABLE`, writes its own `PENDING` reservation row, then calls
Event Service to flip the seat to `RESERVED`. On cancel, it flips the seat
back to `AVAILABLE`. No locking sits between the check and the write yet —
that's step 3.

### Endpoints

| Method | Path                                | Description                                    |
|--------|--------------------------------------|-------------------------------------------------|
| POST   | `/api/reservations`                 | Reserve a seat for a user                      |
| GET    | `/api/reservations`                 | List reservations (add `?userId=...` to filter) |
| GET    | `/api/reservations/{reservationId}` | Get one reservation                            |
| DELETE | `/api/reservations/{reservationId}` | Cancel a reservation and release the seat      |

Swagger UI: `http://localhost:8082/swagger-ui.html`

### Example request

```bash
curl -X POST http://localhost:8082/api/reservations \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "<event-id-from-event-service>",
    "seatId": "<seat-id-from-event-service>",
    "userId": "user-123"
  }'
```

## Running locally

Requires Java 21, Maven, and Docker.

```bash
# 1. Start Postgres
docker compose up -d

# 2. Run Event Service (port 8081)
cd event-service
./mvnw spring-boot:run

# 3. In another terminal, run Reservation Service (port 8082)
cd reservation-service
./mvnw spring-boot:run
```

Reservation Service reads `event-service.base-url` (defaults to
`http://localhost:8081`) to talk to Event Service, so Event Service needs to
be up first.

## Testing

Integration tests spin up a real Postgres instance via Testcontainers — no
mocking of the database layer, so tests exercise actual SQL and constraints.
Reservation Service's tests mock `EventServiceClient` instead of running
Event Service, since only the HTTP boundary is faked — reservation
persistence and orchestration logic still run for real.

```bash
cd event-service
./mvnw test

cd reservation-service
./mvnw test
```

## Why this project

Built to demonstrate distributed-systems patterns beyond basic CRUD: seat
reservation under concurrent access, TTL-based distributed locks (Redis),
async inter-service communication (Kafka), and a state machine for
reserve → pay → confirm/release. See the root of this repo for the full
build log as later services are added.
