# Tatkal-Scale: a zero-oversell flash booking engine

A train/concert ticketing backend that **cannot sell the same seat twice**, even when
500 threads hit "Book" on the last 5 seats at the same instant. It ships with a
**Stampede Simulator** that proves it against real PostgreSQL, and a deliberately
broken strategy that shows what happens without it.

> **Invariant:** one seat has at most one live hold or booking, at any instant, under any concurrency.

---

## Quick start

Requirements: **Java 21+** and **Maven**. No Docker and no Postgres install: the app starts
a real PostgreSQL 17 in-process (embedded) automatically.

```bash
mvn spring-boot:run
```

Then open **http://localhost:8080** for the live dashboard:

- **Stampede Simulator:** pick mode, threads, seats and options, then press **Run all 4 strategies**.
  You get a pass/fail card per strategy, a latency chart (with a table view), and the arena's seat map.
- **Try it yourself:** click a seat to hold it, then pay or cancel before the 5-minute timer runs out.
  Open two tabs and race for the same seat; only one wins.
- **Run history:** every past stampede, stored in the database.

Or fire the stampede from the command line (500 threads, last 5 tickets, every strategy back to back):

```bash
curl -X POST localhost:8080/api/v1/stampede/compare -H "Content-Type: application/json" -d "{\"mode\":\"ANY_SEAT\",\"threads\":500,\"seats\":5}"
```

### HTTP load test (from outside the app)

The simulator above calls the service layer directly. This script sends 500 **real HTTP requests**
at once through Tomcat and Spring MVC, the way real clients would. It needs Node 18+ and nothing else.

```bash
node loadtest/http-stampede.mjs
```

Options: `--base http://localhost:8080 --threads 500 --seats 5 --mode any|same --strategies naive,optimistic,pessimistic,skip-locked`.
It detects oversell from the outside: two `201` responses naming the same seat means a ticket was sold twice.
Here the database safety index stays **on** (normal config), so NAIVE's duplicate attempts are blocked by the
database and come back as `409 DB_GUARD_REJECTED`. That's the third protection layer working.

Sample run (500 requests, 5 seats):

```
PASS  naive        sold 5/5  phantom tickets: 0   responses: SUCCESS=5, 409 DB_GUARD_REJECTED=5, 409 SOLD_OUT=490
PASS  optimistic   sold 5/5  phantom tickets: 0   responses: SUCCESS=5, 409 SOLD_OUT=495
PASS  pessimistic  sold 5/5  phantom tickets: 0   responses: SUCCESS=5, 409 SOLD_OUT=495
PASS  skip-locked  sold 5/5  phantom tickets: 0   responses: SUCCESS=5, 409 SOLD_OUT=495
```

### Running two copies at once

Only one program can use port 8080. If it's taken, the app stops immediately with a message saying so.
Stop the other copy (Ctrl+C), or start this one on another port:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

The second copy then gets its own embedded database, so the two never share data.

Run all tests (unit + integration tests on real Postgres):

```bash
mvn test
```

To use your own PostgreSQL instead of the embedded one:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:postgresql://localhost:5432/tatkal --spring.datasource.username=postgres --spring.datasource.password=secret"
```

---

## Stack

| Piece | Why |
|---|---|
| Java 21, Spring Boot 3.5 (Web, Data JPA, Validation, Actuator) | Standard production stack; `@Transactional`, `@Scheduled`, ProblemDetail errors |
| PostgreSQL 17 (embedded via `io.zonky.test:embedded-postgres`) | Real row locks, MVCC, `SKIP LOCKED`, partial indexes. H2 locks differently, so it proves nothing here |
| Flyway | Schema is versioned SQL; Hibernate runs with `ddl-auto=validate` |
| HikariCP | Connection pool. **Its size (10) caps real DB concurrency**, not the thread count |
| `ExecutorService` + `CountDownLatch` | Release 500 threads at the same instant |
| Micrometer | Outcome/retry counters, Hikari connection-acquire timer |

---

## Architecture

```
            HTTP                                   Stampede Simulator
             |                                     (500 threads + latches)
             v                                              |
   HoldController (4 endpoints)                             |
             |                                              |
             +-------------------> HoldOrchestrator <-------+
                                   (retry loop, NOT transactional)
                                          |
                     one @Transactional attempt per call (Strategy pattern)
          +----------------+----------------+------------------+
          v                v                v                  v
     NaiveHold      OptimisticHold    PessimisticHold    SkipLockedHold
     (no lock)      (@Version)        (FOR UPDATE)       (FOR UPDATE SKIP LOCKED)
                           \                |                  /
                            +------> HoldWriter (shared write) +
                                          |
                                     PostgreSQL
           seat (state machine + version)   booking (partial unique index = safety net)

  ExpiredHoldSweeper --@Scheduled(fixedDelay 30s)--> one set-based SQL: release expired holds
  HoldLifecycleService: confirm (payment) / release (cancel), idempotent per hold token
```

### Seat state machine (in the `Seat` entity)

```
AVAILABLE --hold()--> HELD --confirm() [token matches, TTL not passed]--> BOOKED
    ^                  |
    +--release()/expire (sweeper, user cancel, or TTL passed = lazy expiry)
```

Transitions live in the entity, so no service can skip a rule. Illegal moves throw.

### Three layers of protection

1. **State machine** rejects illegal transitions (`HELD -> HELD`, confirm with the wrong token, confirm after TTL).
2. **Locking strategy** (optimistic version check, or row lock) stops concurrent writers.
3. **Database:** `CREATE UNIQUE INDEX ... ON booking(seat_id) WHERE status IN ('PENDING_PAYMENT','CONFIRMED')`.
   Even buggy Java code cannot create a second live booking for a seat.

---

## The four strategies

| Strategy | Mechanism | When to use |
|---|---|---|
| `NAIVE` | Read seat, check in Java, blind `UPDATE`. No lock, no version. | Never. It exists to **prove the problem is real** |
| `OPTIMISTIC` | `@Version`: `UPDATE ... WHERE id=? AND version=?`; 0 rows -> `ObjectOptimisticLockingFailureException` -> retry in a **new** transaction | Low contention: no one waits on locks |
| `PESSIMISTIC` | `@Lock(PESSIMISTIC_WRITE)`. On Postgres Hibernate emits **`FOR NO KEY UPDATE`** (verified in the SQL log). Others wait; `lock_timeout` 3s | Hot rows: no wasted work |
| `SKIP_LOCKED` | Native `... ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED` | "Any seat" flash sales and job queues: buyers fan out across free seats |

Endpoints: `POST /api/v1/{optimistic|pessimistic|skip-locked|naive}/shows/{showId}/holds`.

---

## Benchmark results (real runs)

Setup: 500 threads, 5 seats, 2 ms simulated "think time" between read and write, Hikari pool 10,
embedded PostgreSQL 17, Windows laptop, JDK 26. **The DB safety index was switched OFF during runs**
so each strategy has to earn zero-oversell on its own.

These are single runs on a laptop, so latencies vary by tens of percent between runs and the
latency ranking between the three safe strategies can change. What does **not** change from run
to run: NAIVE oversells, and the three safe strategies never do.

**ANY_SEAT** (500 buyers, any of the last 5 seats):

| Strategy | Sold | Turned away | Retries | Oversold seats | Phantom tickets | p50 ms | p95 ms | p99 ms | Wall ms |
|---|---|---|---|---|---|---|---|---|---|
| NAIVE | **22** | 478 | 0 | **5** | **17** | 167 | 321 | 329 | 376 |
| OPTIMISTIC | 5 | 495 | 30 | 0 | 0 | 590 | 697 | 703 | 708 |
| PESSIMISTIC | 5 | 495 | 0 | 0 | 0 | 229 | 381 | 394 | 412 |
| SKIP_LOCKED | 5 | 495 | 29 | 0 | 0 | 221 | 351 | 356 | 360 |

**SAME_SEAT** (500 buyers, one hot seat):

| Strategy | Sold | Turned away | Retries | Oversold seats | Phantom tickets | p50 ms | p95 ms | p99 ms | Wall ms |
|---|---|---|---|---|---|---|---|---|---|
| NAIVE | **10** | 490 | 0 | **1** | **9** | 70 | 112 | 120 | 139 |
| OPTIMISTIC | 1 | 499 | 9 | 0 | 0 | 261 | 390 | 435 | 439 |
| PESSIMISTIC | 1 | 499 | 0 | 0 | 0 | 220 | 399 | 417 | 455 |
| SKIP_LOCKED | 1 | 499 | 14 | 0 | 0 | 9 | 89 | 125 | 370 |

Zero lock timeouts, zero exhausted retries, and zero errors in every run.

### A bug the benchmark found

The first version of `PESSIMISTIC` did its "think" work *before* checking whether the locked seat
was still free. On the single hot seat, each of the 499 losers did that work while holding the row
lock, so the whole queue waited for nothing. Measured: **p50 3,681 ms, wall 7,316 ms.**
The fix is one line, `seat.requireHoldableAt(now)` right after locking, and it took the run
to **p50 220 ms, wall 455 ms** (about 16x faster). Lesson: *never do work under a lock that you
might throw away; fail fast first.*

### Reading the numbers

- **NAIVE oversells every time.** 22 buyers "got" 5 seats. This is the check-then-act race under READ COMMITTED.
- **OPTIMISTIC** is correct but pays in retries (30 here): every loser did a full read + think + write before finding out it lost.
- **PESSIMISTIC** has zero retries. Losers queue on the row, then see `HELD` and leave.
- **SKIP_LOCKED** on SAME_SEAT is the fastest because it never waits: if the row is locked it says "busy" at once (like NOWAIT).
- In PESSIMISTIC ANY_SEAT, Postgres has a subtle behaviour: a waiter blocked on `LIMIT 1 FOR UPDATE`
  re-checks the row after the winner commits, sees it no longer matches, and returns **zero rows**
  instead of moving on to the next free seat. The code detects this and retries.
- **500 threads != 500 concurrent transactions.** Only 10 connections exist, so most of each
  request's latency is waiting for a connection (the report includes `avgConnectionAcquireMs`).

---

## Hold expiry

- **TTL:** a hold lasts 5 minutes (`tatkal.hold.ttl`).
- **Lazy expiry:** a `HELD` seat whose TTL has passed counts as free right away. Correctness never depends on the sweeper.
- **Sweeper:** `@Scheduled(fixedDelay = 30s)`. `fixedDelay` waits for the previous run to *finish*, so runs never overlap.
  One SQL statement does the whole sweep:
  - picks expired seats with `FOR UPDATE SKIP LOCKED`, leaving alone any seat a confirm is working on right now
  - sets them `AVAILABLE` and **bumps `version`**, so a stale optimistic writer can't overwrite the release
  - marks their bookings `EXPIRED`
- **Multiple instances:** safe to run on several nodes; skipped rows plus idempotent updates mean no double work.
- **One clock:** every "now" comes from an injected `java.time.Clock`, so tests fast-forward 6 minutes instantly.

## Confirm and release

- **Idempotent:** `POST /holds/{token}/confirm` twice returns the same CONFIRMED booking (double-click on Pay, client retries).
- **Lock order is always seat row, then booking row**, the same as the sweeper, so they can't deadlock each other.
- **Expired holds:** confirming after the TTL returns `410 HOLD_EXPIRED`, even if the sweeper hasn't run yet.

---

## API

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/shows` | Shows (1 = a 40-seat train, 2 = the stampede arena) |
| GET | `/api/v1/shows/{id}/seats` | Seat map, counts, versions |
| POST | `/api/v1/{optimistic\|pessimistic\|skip-locked\|naive}/shows/{id}/holds` | `{"userId":"u1","seatId":12}`, omit `seatId` for any seat. `201`, `409 SEAT_UNAVAILABLE / SOLD_OUT / CONCURRENT_MODIFICATION`, `503 LOCK_TIMEOUT` |
| GET | `/api/v1/holds/{token}` | Booking status |
| POST | `/api/v1/holds/{token}/confirm` | Simulated payment success; idempotent |
| DELETE | `/api/v1/holds/{token}` | Cancel; seat goes back to the pool |
| POST | `/api/v1/stampede` | `{"strategy","mode","threads","seats","thinkTimeMs","dbGuard","virtualThreads"}` (all optional) |
| POST | `/api/v1/stampede/compare` | Same body, runs every strategy (or `"strategies":[...]`) |
| GET | `/api/v1/stampede/runs` | History stored in `stampede_run` |
| POST | `/api/v1/admin/shows/{id}/reset?seats=5` | Reset a show |
| POST | `/api/v1/admin/sweeper/run` | Run the expiry sweep now |

Errors are RFC 9457 ProblemDetail JSON with a stable `code` field.

---

## Tests (30, all on real Postgres except the unit tests)

- `SeatStateMachineTest`: every legal and illegal transition, with no Spring and no DB.
- `HoldLifecycleIT`:
  - every strategy can hold and confirm
  - confirm and release are idempotent
  - a second hold on the same seat is rejected
  - confirm after the TTL fails
  - the sweeper releases holds, bumps the version, and is idempotent
  - lazy takeover of an expired hold
  - sold-out behaviour
  - the HTTP contract (201 / 409 / 400)
- `StampedeIT`:
  - 3 safe strategies x 2 modes, 200 threads: zero oversell, exact success counts, DB guard restored afterwards
  - **control experiment:** NAIVE *does* oversell
  - with the guard ON, the DB stops NAIVE's oversell (`DB_GUARD_REJECTED > 0`)
  - 500 virtual threads

---

## Interview Q&A

**Why not `synchronized` or a `ReentrantLock`?**
They work inside one JVM only. Run two instances behind a load balancer and you oversell. The
database is the one place every instance shares, so the lock lives there.

**Optimistic vs pessimistic: which one, and when?**
Optimistic when conflicts are rare: nobody blocks, and the occasional loser retries. Pessimistic
when the row is hot: losers wait instead of doing work they'll throw away. See the retry column
above. For "any seat" flash sales, `SKIP LOCKED` beats both because buyers stop competing for one row.

**What does `@Version` do, exactly?**
Hibernate adds `AND version = ?` to the UPDATE and increments it. Zero rows updated means someone
else committed first. Hibernate throws `OptimisticLockException`, and Spring translates it to
`ObjectOptimisticLockingFailureException`.

**Why does the retry loop sit outside `@Transactional`?**
After a failure the transaction is rollback-only and the persistence context holds stale entities.
Each attempt must be a brand-new transaction. `HoldOrchestrator` is not transactional and calls
`strategy.tryOnce()` on a *different* bean, so the call goes through the Spring proxy every time.

**What's the self-invocation trap?**
`@Transactional` works through a proxy. `this.method()` skips the proxy, so no transaction starts.
That's why the sweeper's SQL lives in `ExpiredHoldReleaser`, separate from the `@Scheduled` bean.

**Why `seats.flush()` before inserting the booking?**
At flush, Hibernate runs INSERTs before UPDATEs. Without the explicit flush, an optimistic loser
would hit the booking unique index first and fail with the wrong error. Flushing first makes the
version check fire first, which means a clean, retryable conflict.

**Which isolation level?**
Postgres default, READ COMMITTED. Correctness comes from locks/version checks, not isolation.
SERIALIZABLE would also work, but you'd have to retry serialization failures, the same idea as optimistic.

**What does `PESSIMISTIC_WRITE` produce on Postgres?**
`FOR NO KEY UPDATE` (seen in the SQL log). It blocks other writers and lockers of the row but not
inserts of rows that reference it by foreign key, which `FOR UPDATE` would block.

**How do you know it's really zero oversell?**
After every run, a SQL query counts seats with more than one live booking. Java counters are not trusted. The run
also checks that `successes == live bookings <= seats`. The DB unique index is OFF during the run,
so the strategy has to earn the result. And NAIVE proves the test *can* detect overselling.

**Is it really 500 concurrent?**
500 threads start at the same instant (`CountDownLatch` start gate). But only 10 DB connections
exist, so at most 10 transactions run at once and the rest wait in Hikari's queue. That's realistic:
production has a pool too. Raise `spring.datasource.hikari.maximum-pool-size` to experiment.

**Why `CountDownLatch` and not just `submit()` in a loop?**
Without a gate, early threads finish before late ones start, and contention is fake. Three latches:
- `ready`: all threads are parked
- `start`: the pistol, so all go at once
- `done`: main waits for everyone

`CyclicBarrier` is the reusable alternative.

**Why one array slot per thread for latencies?**
No shared counter means no contention in the measuring code itself. `done.countDown()` /
`done.await()` gives a happens-before edge, so the main thread sees every write.

**Virtual threads?**
Supported (`"virtualThreads": true`). They make 500+ threads cheap, but the DB pool is still the
bottleneck, so throughput barely changes. That is the lesson.

**What if the sweeper and a payment race on the same seat?**
- The sweeper uses `SKIP LOCKED`, so it never waits on a seat that a confirm has locked.
- Confirm locks the seat and checks token + TTL.
- Whichever commits first wins, and the other sees the new state.

**The sweeper died for 10 minutes. Is anything broken?**
No. Lazy expiry treats expired holds as free during hold and confirm. The sweeper only tidies up.

**`fixedRate` vs `fixedDelay`?**
`fixedRate` can start a run while the previous one is still going. `fixedDelay` waits 30s after
the previous one *finishes*. Also, the default scheduler has one thread for all jobs (set to 2 here).

**Deadlocks?**
- A hold locks one seat, so it can't deadlock with another hold.
- Confirm, release and the sweeper all lock seat first, then booking: consistent ordering.
- Multi-seat holds would lock seats in id order.
- `lock_timeout` bounds the worst case.

**Double-click on Pay?**
Confirm is idempotent per hold token. It locks the booking row, sees CONFIRMED, and returns the
same result.

**How would you scale this to IRCTC level?**
- a waiting room / queue in front to smooth the burst
- Redis `SET NX PX` as a fast pre-filter (DB stays the source of truth)
- shard by train/show
- read replicas for seat maps
- per-user rate limits and hold caps

---

## Known limits (good to say out loud)

- **The NAIVE demo drops the DB safety index** for the duration of a run. That's fine in a demo arena, never in production.
- **Only one stampede runs at a time** (a JVM lock; it's an admin tool).
- **No per-user hold cap yet.** A correct cap needs a lock on the user, not a count-then-insert, which is the same race again.
- **One clock assumption.** The app clock is used everywhere, so multi-node deployments rely on NTP; the DB clock is the alternative.
- **Payment is simulated.**

## Project layout

```
com.tatkal
├── seat/       Seat (state machine, @Version), SeatRepository (all locking queries)
├── hold/       SeatHoldStrategy + 4 strategies, HoldWriter, HoldOrchestrator (retry), HoldController
├── booking/    Booking, HoldLifecycleService (confirm/release), controller
├── sweeper/    ExpiredHoldSweeper (@Scheduled), ExpiredHoldReleaser (set-based SQL)
├── stampede/   StampedeService (latches, invariant check), report, controller
├── show/       Show, seat map, admin reset
├── common/     ErrorCode, TatkalException, GlobalExceptionHandler (ProblemDetail)
└── config/     TatkalProperties, ClockConfig, EmbeddedPostgresLauncher

src/main/resources/static/index.html   live dashboard (plain HTML/JS, no build step, works offline)
loadtest/http-stampede.mjs             HTTP load test (Node, no dependencies)
```
