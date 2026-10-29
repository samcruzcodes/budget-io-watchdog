# Budget.io Watchdog

Watchdog watches card transactions as they happen and flags money leaks (duplicate charges, free trials that turned into paid plans, subscription price hikes, spending spikes) and suspicious charges (card testing, charges from a country the card has never been used in). It's the next version of Budget.io, a manual budget planner I built earlier.

**Status:** Phases 1 and 2 are done, and Phase 3 is in progress. A simulator generates realistic card transactions and publishes them to Kafka, and a Spring Boot service validates them, stores them in PostgreSQL, and runs six detection rules on every new one. Each hit becomes an alert with a plain-English explanation, saved to Postgres and published to Kafka. Detection metrics, the REST API, the dashboard, and the ML comparison are planned. See the [roadmap](#roadmap).

## How it works

```
 simulator  ──►   Kafka   ──►  watchdog  ──►  PostgreSQL
 makes up         holds        reads each     keeps the
 purchases        messages     one, checks    transactions
                               it, saves it,  and alerts
                               runs the rules
                                   │
                                   └──►  Kafka `alerts`
```

- **Simulator** (plain Java): plays a small group of people with different spending habits, like a student, a family, and a frequent traveler, on a clock that runs a simulated month per real minute. It plants realistic problems, plus legitimate purchases that only look like problems, and publishes the truth about every transaction to a separate answer-key topic so detection can be graded later.
- **Kafka:** holds every transaction, in order per card, until the watchdog reads it. If the watchdog goes down, nothing is lost; it picks up where it left off.
- **Watchdog** (Spring Boot): checks that each message is valid, stores it exactly once even if Kafka delivers it twice, and sends anything it can't parse to a dead-letter topic. Then it runs six detection rules on each new transaction (duplicate charges, free trials that turned into paid plans, price hikes, spending spikes, card testing, and charges from a new country) and turns anything they flag into an alert, with an explanation like "Whole Foods Market charged $140.84 twice, 24 seconds apart." Alerts are saved to Postgres and published to an `alerts` topic. After a restart it replays recent history from Postgres, so the rules remember each card before it reads anything new.
- **PostgreSQL:** the stored transactions and alerts.

The design, the tradeoffs behind each choice, every rule's thresholds and where they came from, and what I learned building each phase are in [docs/DESIGN.md](docs/DESIGN.md).

## Roadmap

| Phase | What | Status |
|---|---|---|
| 1 | Transaction pipeline: simulator, Kafka, validation, and storage | Done |
| 2 | Six detection rules: duplicate charges, free trial conversions, price hikes, spending spikes, card testing, new-country charges. They log what they flag. | Done |
| 3 | Alerts (saved and published), detection metrics (precision and recall), and a REST API | In progress: alerts are done |
| 3.5 | Run the stack on a local Kubernetes cluster (kind) | Planned |
| 4 | Dashboard | Planned |
| 5 | CI and benchmark results | Planned |
| 6 | Compare the rules with an ML model trained on the simulator's labeled data | Planned |

Further out: real transactions through a Plaid adapter, letting Budget.io read real spending from Watchdog, and trying rules and ML together.

## Running it

You only need Docker.

```
docker compose up --build                   # Kafka, Postgres, the simulator, and the watchdog
docker compose --profile tools up --build   # the same, plus Kafka UI at http://localhost:8081
docker compose down                         # stop everything
```

- Every `up` starts a fresh, repeatable run. Kafka and Postgres keep their data in memory, so `down` throws it away.
- The demo simulates 3 people at one simulated month per real minute. Planted problems start after a 30-day warm-up, about one real minute in.
- In Kafka UI, Topics → `transactions` → Messages shows the live feed, `alerts` shows what the rules flagged, and `transactions.DLT` holds anything the watchdog rejected.
- To watch the rules: `docker compose logs -f watchdog | grep "Rule hit"`. Hits start once the simulator's 30-day warm-up ends, about a minute in.
- Health checks: `curl localhost:8082/actuator/health` (also `/actuator/health/liveness` and `/actuator/health/readiness`).
- To look at what's stored:

  ```
  docker compose exec postgres psql -U watchdog -d watchdog \
    -c "select merchant, amount_cents, occurred_at from transactions order by occurred_at desc limit 10"
  docker compose exec postgres psql -U watchdog -d watchdog \
    -c "select detector, explanation from alerts order by occurred_at desc limit 10"
  ```

## Development

You need Java 21, plus Docker for the full stack. `./mvnw` downloads the right Maven version on first use and checks its checksum, so there's nothing else to install.

```
./mvnw verify                    # build everything and run all the tests (integration tests need Docker running)
./mvnw -pl simulator -am test    # one module, plus the modules it depends on
```

`./mvnw verify` needs Docker running for the integration tests; `./mvnw test` runs just the unit tests, without Docker.

To run the watchdog from an IDE, start the stack, stop its watchdog container with `docker compose stop watchdog`, and run `WatchdogApplication`. Its defaults point at Kafka on `localhost:9094` and Postgres on `localhost:5432`, and it listens on port 8082.

### Project layout

```
transaction-schema/   message formats shared by every program: records, JSON Schemas, topic names
simulator/            the plain Java transaction generator
watchdog-service/     the Spring Boot service: Kafka in, PostgreSQL out, detection rules, alerts
config/               simulator presets
docs/DESIGN.md        design, tradeoffs, and the decision log
```

### Conventions

A few rules I follow in this codebase:

- Money is whole cents in a `long`, never a `double`.
- The simulator and the rules only use event time (`occurredAt`). The real clock is only for measuring latency.
- Kafka messages are keyed by `cardId`, so each card's transactions stay in order.
- Kafka can deliver a message more than once, so every database write has to be safe to repeat. Alerts can be published more than once too (rarely), so anything reading the `alerts` topic should drop duplicates by `alertId`.
- Messages that can't be parsed go to `transactions.DLT` right away. Anything else, like the database being down, gets retried until it works.
- Messages are read and written through `MessageJson`, which is stricter than Jackson's defaults.
- Database access is plain SQL with `JdbcClient`, no JPA. Schema changes go in a new Flyway migration; a committed one never changes.
- The simulator stays deterministic: the same seed gives the same data.
- Each detection rule is one class with its thresholds in `application.yml` under `watchdog.rules`, and rules never read the answer key; a test checks that.
- Unit tests end in `Test`. Integration tests end in `IT` and run against real Kafka and Postgres in Docker (Testcontainers).

### Gotchas

- Restarting the simulator replays from day one, and the watchdog skips everything it already has, so the demo goes quiet for a bit. That's expected.
- A restarted watchdog replays the last 90 simulated days from Postgres before it reads Kafka, so `/actuator/health/readiness` says `OUT_OF_SERVICE` for a moment. Countries a card only used before those 90 days are forgotten.
- Restarting just the Postgres container wipes the demo data. Restart the whole stack instead.
- Kafka has two addresses: `kafka:9092` inside Docker and `localhost:9094` from your machine.
- Maven's version is pinned in two places, `.mvn/wrapper/maven-wrapper.properties` and the build image in both Dockerfiles. Change them together.

### Saving and publishing alerts

Saving an alert to Postgres and publishing it to Kafka can't happen in one step, so a crash in between could leave them out of sync. The standard fix is the transactional outbox pattern: write outgoing messages to a table in the same database transaction as the data, and have a separate relay publish them. Watchdog uses a lighter version: the `alerts` table records which alerts Kafka has confirmed, and if publishing fails or the app dies first, Kafka redelivers the message and the watchdog publishes what's still unconfirmed. Nothing is lost, but an alert can occasionally be published twice with the same ID, and a Kafka outage pauses ingest. The details are in [docs/DESIGN.md](docs/DESIGN.md).

## How I built this

I used Claude Code as a development and learning tool while building Watchdog, particularly to help me understand Java, Spring Boot, Kafka, and the technical decisions behind the implementation.
