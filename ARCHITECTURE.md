# Architecture

This document is the "why," not the "what" — endpoint lists and run
commands live in [README.md](README.md). This is the design reasoning: why
three services and not one or five, how consistency is actually maintained
across them, what happens when a step fails partway through, and what was
learned building it. Read the README first if you haven't; this assumes
you know what each service does.

## System overview

```
                 ┌─────────────────┐
   client   ───▶ │  Event Service   │  REST + Postgres
                 └─────────────────┘
                          ▲  reads seat state, writes reserve/release
                          │  via PATCH (no validation — see below)
                 ┌─────────────────┐        Redis
   client   ───▶ │ Reservation Svc  │ ─────▶ SETNX seat:{id} -> userId, EX 5s
                 └─────────────────┘
                     │            ▲
        publishes    │            │  consumes payment-confirmed/
        seat-reserved│            │  payment-failed
                      ▼            │
                 ┌─────────────────┐
                 │ Redpanda (Kafka) │
                 └─────────────────┘
                          │ consumes seat-reserved
                          ▼
                 ┌─────────────────┐
                 │  Payment Service │ ── simulated gateway, no external calls
                 └─────────────────┘
                          │ publishes payment-confirmed / payment-failed
                          ▼
                     (back to Reservation Service, above)
```

Three Spring Boot services, one shared Postgres instance (separate
databases would be more correct — see [Known limitations](#known-limitations)),
one Redis, one Redpanda broker. Everything is independently runnable
(`./mvnw spring-boot:run` per service) or fully containerized
(`docker compose up -d` brings up all six containers, app services included).

## Why three services, not one or five

**Event Service** owns event/seat inventory and nothing else. It has no
opinion about reservations, payments, or locking — it's a dumb data store
on purpose (see below). This boundary exists because seat inventory is a
genuinely separate concern from booking workflow: a real system might have
different teams, different scaling needs (read-heavy browsing vs.
write-heavy checkout), and different data lifecycles for these two things.

**Reservation Service** owns the booking workflow and is the only service
with business logic about *when* a seat transitions between states. It's
also the only service that talks to Redis (the lock) and the only one that
calls Event Service over REST. Payment Service reports outcomes to it via
Kafka rather than calling it directly, which keeps Payment Service from
needing to know Reservation Service's REST contract at all.

**Payment Service** is intentionally the simplest and most isolated: it has
*no* REST dependency on either other service, only Kafka in and Kafka out.
That isolation is deliberate — it means Payment Service can be deployed,
scaled, or replaced independently, and a Payment Service outage degrades to
"reservations sit PENDING until the expiry sweep catches them" rather than
cascading failures elsewhere. See [Failure handling](#failure-handling--what-happens-when-things-break).

Three was the right number for what this project demonstrates
(REST-owned inventory, lock-guarded booking, event-driven payment). A
fourth (notifications, a real gateway adapter) would be a natural next
service; collapsing to two would blur the "who owns concurrency safety"
line that's the whole point of steps 2–3.

## Data model & consistency

Each service owns its own tables (`events`/`seats`, `reservations`,
`payments`) and never reads another service's tables directly — all
cross-service reads go through REST (Reservation → Event) or Kafka
(Reservation ↔ Payment). That's the right *logical* boundary. The
honest gap: all three point at the same physical Postgres instance
(`ticketing`), not separate databases. A schema change in one service's
tables can't accidentally corrupt another's data, but they do share
connection pool capacity and a failure domain. True database-per-service
was cut for this project's scope — see [Known limitations](#known-limitations).

**Event Service's seat status is the single source of truth for "is this
seat free," but Event Service does no transition validation.** `PATCH
/api/events/{id}/seats/{seatId}` writes whatever status it's given —
`AVAILABLE → SOLD` is accepted exactly as readily as `SOLD → AVAILABLE`.
This was a deliberate choice, not an oversight: concurrency safety is
Reservation Service's job (the Redis lock), and duplicating that logic
in Event Service would mean two services independently deciding "is this
transition legal," which is exactly the kind of split-brain that causes
subtle bugs. One writer decides; the other one just writes. Event
Service's only independent safeguard is `Seat.version` (JPA optimistic
locking) — a backstop against lost updates, not a substitute for the
Redis lock. [The load test](#the-concurrency-story) demonstrates the
difference between the two concretely.

## The concurrency story

This is the actual point of the project, so it's worth walking through as
a narrative rather than a features list:

1. **Step 2** wired Reservation Service to Event Service with a plain
   check-then-act: read the seat, confirm it's `AVAILABLE`, write a
   reservation, tell Event Service to flip it to `RESERVED`. Nothing sat
   between the read and the write. Two concurrent requests for the same
   seat could both pass the check and both "win."

2. **Step 3** closed that race with a Redis lock keyed per seat
   (`SETNX seat:{seatId} -> userId, EX 5s`), wrapped around the entire
   check-then-act. A second concurrent request now fails fast with `409`
   instead of racing the first. The 5-second TTL is a crash safety net —
   if the process dies mid-reservation, the lock expires on its own instead
   of wedging the seat forever. Every seat-state-changing path (create,
   cancel, Payment Service's confirm/fail callbacks, and the step-5 expiry
   sweep) goes through this same lock, so none of them can interleave with
   each other on the same seat either.

3. **Step 6** proved it, empirically, against the real running system —
   [`load-test/DoubleBookingLoadTest.java`](load-test/DoubleBookingLoadTest.java)
   fires 200 truly-concurrent HTTP requests (10 racing for each of 20
   seats, released simultaneously via a `CountDownLatch` start gate) and
   asserts every seat has exactly one winner, counted from actual response
   bodies rather than trusted from aggregate HTTP status codes.

   The test was itself verified to be meaningful, not a rubber stamp: with
   the lock's `tryLock` check temporarily short-circuited, the *same*
   200-request burst produced real double-booking — one seat had 5
   simultaneous "winners." Interestingly, Event Service's `@Version`
   optimistic-lock backstop wasn't useless even then: it converted some of
   the races into `ObjectOptimisticLockingFailureException`s (12 of the 200
   requests failed loudly instead of succeeding wrongly) rather than
   silent double-bookings. But 4 seats still ended up double-booked. That's
   the concrete version of "defense in depth reduces blast radius, it
   doesn't replace the primary safeguard" — the Redis lock is what actually
   closes the race; the DB-level version column is what limits the damage
   on the rare path where something upstream of it still gets it wrong.

## Event-driven flow & idempotency

Reservation Service and Payment Service never call each other over REST —
only Kafka, in both directions. Three topics, each carrying one event type:

| Topic               | Producer            | Consumer            | Payload |
|----------------------|---------------------|----------------------|---------|
| `seat-reserved`      | Reservation Service | Payment Service      | `SeatReservedEvent` |
| `payment-confirmed`  | Payment Service      | Reservation Service | `PaymentConfirmedEvent` |
| `payment-failed`     | Payment Service      | Reservation Service | `PaymentFailedEvent` |

Each service has its own copy of these event records in its own package —
no shared library between services, matching how `SeatDto`/`SeatStatus` are
already duplicated between Event Service and Reservation Service elsewhere
in this codebase. Messages carry a short type token (`seatReserved`,
`paymentConfirmed`, `paymentFailed`) in the `__TypeId__` header instead of a
fully-qualified class name (`spring.json.type.mapping` in each service's
`application.yml`), which is what lets two different classes in two
different packages deserialize the same wire format correctly without
either service knowing the other's class names.

**Kafka is at-least-once delivery, so every consumer is written to be
idempotent against redelivery.** `confirmReservation`, `failReservation`,
and the expiry sweep's `expireReservation` all check the reservation is
still `PENDING` before acting and treat anything else as an already-applied
duplicate, not an error. Payment Service's consumer checks for an existing
`Payment` row by `reservation_id` (unique constraint) before processing, so
a redelivered `seat-reserved` event can't produce a second charge.

## Failure handling — what happens when things break

- **Reservation Service dies mid-request, after acquiring the Redis lock:**
  the lock's 5s TTL expires it automatically; the seat isn't permanently
  wedged.
- **Payment Service is down (or a message is lost) when a reservation is
  created:** the reservation sits `PENDING` past its `expiresAt`
  (`reservation.pending-ttl-seconds`, default 60s). The step-5 scheduled
  sweep (`reservation.expiry-sweep.fixed-delay-ms`, default every 10s)
  finds it, acquires the same seat lock everything else uses, and expires
  it — releasing the seat back to `AVAILABLE`. This is deliberately
  eventual, not instant: an abandoned seat becomes reservable again within
  one sweep interval, not the moment it technically expires. A synchronous
  TTL check on every read would close that gap but wasn't needed for what
  this step asked for.
- **A payment-confirmed/failed event arrives for a reservation that's
  already been resolved another way** (e.g., the sweep beat it there): the
  idempotency guard above no-ops it. This is the same lock-then-recheck
  pattern regardless of which of the four writers (create, cancel,
  Kafka confirm/fail, sweep) gets there — none of them assume they're the
  only writer.
- **Event Service is unreachable from Reservation Service:** surfaces as
  `503 Service Unavailable` to the caller (`GlobalExceptionHandler` catches
  `RestClientException`), not a hang or a silent wrong answer.

## Testing strategy

Integration tests run against real Postgres, Redis, and Kafka
(Testcontainers) — no mocking of the database, lock, or messaging layer, so
tests exercise actual SQL, `SETNX`/TTL/unlock semantics, and real
produce/consume round trips. The only thing ever mocked in any test is
Reservation Service's REST call to Event Service (`EventServiceClient`);
Payment Service has no REST dependency on either other service to mock in
the first place. Async consumer tests poll the row a listener writes (or
the REST endpoint backed by it) instead of sleeping a fixed guess.

The expiry sweep test runs in its own Spring context with a short TTL/sweep
interval — deliberately separate from the main test class, since those
settings applied globally would let the sweep expire `PENDING` reservations
out from under unrelated tests mid-run.

The load test (above) is intentionally *not* a JUnit test — it needs the
real, already-running services on real ports, which is a different kind of
artifact than the self-contained, ephemeral-infra suites `mvn test` runs.
Keeping it as a standalone script keeps that distinction honest instead of
folding a fundamentally different kind of check into the CI-style suite.

## Deployment

`docker compose up -d` brings up all six containers — Postgres, Redis,
Redpanda, and all three app services — each app service built from a
multi-stage `Dockerfile` (Maven build stage, `eclipse-temurin:21-jre-alpine`
runtime stage, non-root user). Container-to-container config is entirely
environment-variable overrides of the same `application.yml` properties
used for local dev (Spring Boot's relaxed env-var binding —
`SPRING_DATASOURCE_URL`, `EVENT_SERVICE_BASE_URL`, etc.) — no code branches
between "running locally" and "running in Docker."

One non-obvious piece: Redpanda runs **two listeners**, not one. Host-run
services (`mvn spring-boot:run`, this repo's usual dev loop) reach it at
`localhost:9092`; the containerized app services reach it at
`redpanda:29092` on the compose network. A Kafka broker's *advertised*
address is what clients get redirected to after the initial connection —
a single "advertise as localhost" listener works for host clients but
breaks for anything calling in from inside another container, where
`localhost` means that container itself, not the broker. Postgres and
Redis don't need this: they have no advertised-address concept, so
container-network access (`postgres:5432`) and host access
(`localhost:5433`) work simultaneously through ordinary Docker port
publishing.

## Known limitations

Written down rather than glossed over, because knowing the gap is more
useful than pretending it isn't there:

- **Shared Postgres instance, not database-per-service.** All three
  services' tables live in one `ticketing` database. Logically separated
  (no service reads another's tables), but not physically isolated —
  they share connection pool capacity and a failure domain. True
  database-per-service was out of scope here.
- **`ddl-auto: update`, not a real migration tool.** This bit during step 5:
  Hibernate generated `ADD COLUMN expires_at ... NOT NULL` against a table
  that already had rows, which Postgres rejects without a default, and
  separately never updated the existing `status` `CHECK` constraint to
  allow the new `EXPIRED` value — both had to be patched by hand against
  the running dev database. Fine for a from-scratch demo table; exactly
  the kind of gap Flyway/Liquibase exist to close in anything longer-lived.
- **Payments are simulated, not real Stripe calls** — deliberate, to keep
  the demo free of external credentials. `PaymentSimulator` sits behind one
  narrow method (`process(userId)` → confirmed/failed) specifically so it
  could be swapped for a real `PaymentIntent` call later without touching
  the Kafka plumbing around it. Failure is triggered deterministically (a
  `fail-` userId prefix) rather than by simulating real decline reasons.
- **No auth anywhere.** Every endpoint on every service is unauthenticated.
  `userId` is a client-supplied string, trusted at face value — anyone can
  reserve a seat "as" any user. Fine for a local demo; a first production
  requirement.
- **Single-broker Redpanda, not a cluster.** No replication, no partition
  tolerance story. Each topic runs on Redpanda's default partition count
  with no manual provisioning (`auto_create_topics_enabled` handles topic
  creation).
- **JSON over Kafka with a hand-rolled type-token convention
  (`spring.json.type.mapping`), not a schema registry.** Works fine at this
  scale with three services maintained by one person; would not scale past
  a handful of services without either strict change discipline or moving
  to Avro/Protobuf with a real registry.

## What would change for production

Roughly in priority order: authn/authz on every endpoint; database-per-service
(or at least per-service credentials/schemas with enforced isolation);
Flyway/Liquibase migrations; a real payment gateway behind the
`PaymentSimulator` seam; a multi-broker Kafka/Redpanda cluster with
replication; observability (structured logs are there, but no tracing/metrics
export yet — this is the natural next thing given three services already
talk to each other over both REST and Kafka); rate limiting on the reservation
endpoint specifically, since it's the one under adversarial load in a real
on-sale scenario; and a dead-letter topic for Kafka consumer failures instead
of the current default (log and retry a few times, per Spring Kafka's default
error handler).
