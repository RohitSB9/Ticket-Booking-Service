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
                 ┌─────────────────┐        Redis (seat locks, TTL)
   React SPA ──▶ │ Reservation Svc  │ ─────▶ SETNX seat:{id} -> userId
                 └─────────────────┘
                          │ publishes SeatReserved
                          ▼
                 ┌─────────────────┐
                 │ Redpanda (Kafka) │
                 └─────────────────┘
                          │ consumes SeatReserved
                          ▼
                 ┌─────────────────┐        simulated gateway
                 │  Payment Service │ ─────▶ publishes PaymentConfirmed /
                 └─────────────────┘         PaymentFailed
```

## Status

- [x] Step 1: Event Service (this repo) — CRUD for events/seats, Postgres-backed
- [x] Step 2: Wire Reservation Service to Event Service, no locking yet
- [x] Step 3: Add Redis distributed locking to Reservation Service
- [x] Step 4: Add Kafka + Payment Service
- [x] Step 5: Expiry sweep for abandoned reservations
- [ ] Step 6: Load test proving no double-booking under concurrency
- [ ] Step 7: Dockerize, deploy, write architecture doc

## Event Service — what it does

Owns event and seat inventory. Seats are generated automatically when an event
is created (`rows` x `seatsPerRow`), labeled like `A1`, `A2`, ... `B1`, `B2`.
Every seat starts as `AVAILABLE`. The Reservation Service reads seat state
from here and owns the actual reserve/release transitions, serialized by its
own Redis lock (step 3).

A `@Version` field on `Seat` gives an optimistic-lock backstop at the DB layer,
independent of the Redis lock the Reservation Service uses — worth mentioning
in interviews as defense-in-depth against lost updates.

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
it's given. Event Service stays a dumb data store on purpose: concurrency
safety lives in the Reservation Service's Redis lock (step 3), not here.

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
Event Service to flip the seat to `RESERVED`, then publishes a
`seat-reserved` Kafka event. Both create and cancel run inside a per-seat
Redis lock.

It also consumes `payment-confirmed`/`payment-failed` from Payment Service:
a confirmed payment moves the reservation to `CONFIRMED` and the seat to
`SOLD`; a failed one moves the reservation to `CANCELLED` (with the failure
reason recorded) and releases the seat back to `AVAILABLE`. Both consumers
are idempotent — Kafka is at-least-once delivery, so a redelivered event for
a reservation that's already past `PENDING` is treated as a no-op, not an
error.

### Expiry sweep

In normal operation a `PENDING` reservation is resolved by Payment Service
within a few hundred ms. But nothing guarantees that response ever
arrives — Payment Service could be down, a message could get lost, or in a
real system a payment gateway could be waiting on user input that never
comes. Left alone, that reservation (and the seat it's holding as
`RESERVED`) would be stuck forever.

Every reservation is created with an `expiresAt` (`pending-ttl-seconds`
after creation, default 60s). A `@Scheduled` sweep
(`expiry-sweep.fixed-delay-ms`, default every 10s) queries for `PENDING`
rows past their `expiresAt`, and for each one: acquires that seat's Redis
lock (same lock `createReservation`/`cancelReservation`/`confirmReservation`/
`failReservation` all use), re-confirms it's still `PENDING`, then sets it
to `EXPIRED` and releases the seat back to `AVAILABLE` — the same
idempotency guard as the Kafka consumers, so a reservation that Payment
Service resolves in the gap between the sweep's query and its lock
acquisition is left alone rather than double-processed. If the lock is
already held by something else, the sweep just skips that reservation and
retries it next tick.

One honest trade-off: this is eventual, not instant, consistency — an
abandoned seat becomes reservable again within one sweep interval, not the
moment it technically expires. A synchronous TTL check on every read would
close that gap but adds complexity this project doesn't need; a periodic
sweep is the standard real-world answer here and is what was asked for.

Adding this step surfaced a real `ddl-auto: update` limitation: Hibernate
generated `ADD COLUMN expires_at ... NOT NULL` against a table that already
had rows, which Postgres rejects without a default, and separately never
touched the existing `status` CHECK constraint to add `EXPIRED` to the
allowed values — both had to be patched by hand against the running dev
database. `ddl-auto: update` is fine for a from-scratch demo table, but this
is exactly the kind of gap real migration tooling (Flyway/Liquibase) exists
to close; this project doesn't use one since every environment here starts
from an empty database, but it's worth knowing the difference.

### Locking

Every path that changes a seat's reservation state — create, cancel,
Payment Service's confirm/fail callbacks, and the expiry sweep below — is
wrapped in a distributed lock keyed by seat, so none of them can interleave
with each other on the same seat:

```
SETNX seat:{seatId} -> userId   EX 5s
... check availability, write reservation, call Event Service ...
DEL seat:{seatId}               (only if the value still matches — Lua script)
```

- **`SETNX`** (`SET ... NX`) makes acquisition atomic — exactly one concurrent
  caller gets the lock; everyone else gets `false` back immediately and the
  request fails fast with `409 Conflict`, no blocking/retrying.
- **5s TTL** is a crash safety net: if the process dies mid-reservation, the
  lock expires on its own instead of wedging the seat forever.
- **Compare-and-delete unlock** (a small Lua script run atomically) means a
  caller can only release a lock it still owns — without it, a caller whose
  TTL already expired could delete a different caller's lock out from under
  it after that key gets reacquired.

This is what actually closes the race step 2 left open: two requests for the
same seat now serialize on this lock instead of both racing Event Service's
check-then-act. `ReservationControllerIntegrationTest` proves it directly —
`createReservation_secondConcurrentAttemptForSameSeatIsRejected` fires two
concurrent reservation attempts for the same seat against a real Redis
(Testcontainers) and asserts exactly one wins.

You can watch a lock exist mid-request with `redis-cli`:
```bash
docker exec -it ticketing-redis redis-cli
> GET seat:<seat-id>          # shows the owning userId while a request is in flight
> TTL seat:<seat-id>          # counts down from 5
```

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

## Payment Service — what it does

Fully event-driven — no REST calls in or out to the other services, only
Kafka. It consumes `seat-reserved`, "processes" the payment, persists a
`Payment` row, and publishes `payment-confirmed` or `payment-failed`.
Processing is idempotent: a unique constraint on `reservation_id` means a
redelivered `seat-reserved` event for an already-processed reservation is a
no-op, not a duplicate charge.

Payments are **simulated**, not real Stripe calls — deliberately, to keep
the demo self-contained with no external credentials. The fake gateway
(`PaymentSimulator`) sits behind one narrow method (`process(userId)` →
confirmed/failed) specifically so it could be swapped for a real Stripe
`PaymentIntent` call later without touching the Kafka plumbing around it.
Since there's no real card data to fail on, the failure path is triggered
deterministically: any `userId` starting with `fail-` gets a simulated
decline. Everything else confirms.

### Endpoints

| Method | Path                            | Description                        |
|--------|----------------------------------|-------------------------------------|
| GET    | `/api/payments`                 | List all payments                  |
| GET    | `/api/payments/{reservationId}` | Get the payment for a reservation  |

Swagger UI: `http://localhost:8083/swagger-ui.html`

### Try the failure path

```bash
curl -X POST http://localhost:8082/api/reservations \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "<event-id>",
    "seatId": "<seat-id>",
    "userId": "fail-user-1"
  }'

# a couple seconds later, once Payment Service has processed the event:
curl http://localhost:8082/api/reservations/<reservation-id>
# → status CANCELLED, failureReason "Card declined (simulated)", seat back to AVAILABLE
```

### Cross-service event contracts

Each service that touches Kafka has its own copy of the event records
(`SeatReservedEvent`, `PaymentConfirmedEvent`, `PaymentFailedEvent`) in its
own package — no shared library, matching how `SeatDto`/`SeatStatus` are
already duplicated between Event Service and Reservation Service. Messages
carry a short type token (`seatReserved`, `paymentConfirmed`,
`paymentFailed`) in the `__TypeId__` header instead of a fully-qualified
class name, via `spring.json.type.mapping` in each service's
`application.yml` — that's what lets two different classes in two different
packages deserialize the same message correctly.

## Running locally

Requires Java 21, Maven, and Docker.

```bash
# 1. Start Postgres + Redis + Redpanda
docker compose up -d

# 2. Run Event Service (port 8081)
cd event-service
./mvnw spring-boot:run

# 3. In another terminal, run Reservation Service (port 8082)
cd reservation-service
./mvnw spring-boot:run

# 4. In another terminal, run Payment Service (port 8083)
cd payment-service
./mvnw spring-boot:run
```

Reservation Service reads `event-service.base-url` (defaults to
`http://localhost:8081`) to talk to Event Service, so Event Service needs to
be up first. Reservation Service also needs Redis
(`spring.data.redis.host`/`port`, default `localhost:6379`) for its seat
lock. Both Reservation Service and Payment Service need Redpanda reachable
at `spring.kafka.bootstrap-servers` (default `localhost:9092`); it
auto-creates the `seat-reserved`/`payment-confirmed`/`payment-failed` topics
on first use, no manual provisioning needed.

Postgres is published on host port **5433**, not 5432 — set that way in
`docker-compose.yml` to avoid colliding with a native Postgres install. If
`docker compose up -d` and the app still can't connect, check for a port
conflict the same way (`netstat`/`lsof` on the port in question) before
assuming it's a code problem.

## Testing

Integration tests spin up real Postgres, Redis, and Kafka (Testcontainers) —
no mocking of the database, lock, or messaging layer, so tests exercise
actual SQL, `SETNX`/TTL/unlock semantics, and real produce/consume round
trips. The only thing ever mocked is Reservation Service's REST call to
Event Service (`EventServiceClient`); Payment Service has no REST
dependency on either other service at all — it's reached only through Kafka,
which is real in its tests too. Async consumer tests poll the row a listener
writes (or the REST endpoint backed by it) instead of sleeping a fixed guess.

The expiry sweep test (`ReservationExpirySweepTest`) runs in its own Spring
context with `pending-ttl-seconds`/`expiry-sweep.fixed-delay-ms` overridden
to a couple of seconds — deliberately a separate test class rather than
folded into the main one, since those settings applied globally would let
the sweep expire PENDING reservations out from under other tests mid-run.

```bash
cd event-service
./mvnw test

cd reservation-service
./mvnw test

cd payment-service
./mvnw test
```

## Why this project

Built to demonstrate distributed-systems patterns beyond basic CRUD: seat
reservation under concurrent access, TTL-based distributed locks (Redis),
async inter-service communication (Kafka), a state machine for
reserve → pay → confirm/release/expire, and a scheduled sweep as the safety
net for the failure modes events alone don't cover. See the root of this
repo for the full build log as later services are added.
