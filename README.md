# Budget.io Watchdog

Watchdog watches card transactions as they happen and flags money leaks (duplicate charges, free trials that turned into paid plans, subscription price hikes, spending spikes) and suspicious charges (card testing, charges from a country the card has never been used in). It's the next version of Budget.io, a manual budget planner I built earlier.

**Status:** Phases 1 and 2 are done. A simulator generates realistic card transactions and publishes them to Kafka, and a Spring Boot service validates them, stores them in PostgreSQL, and runs six detection rules on every new one. For now the rules log each hit with a plain-English explanation. Alerts, detection metrics, the dashboard, and the ML comparison are planned. See the [roadmap](#roadmap).

## How it works

```
 simulator  ──►   Kafka   ──►  watchdog  ──►  PostgreSQL
 makes up         holds        reads each     keeps the
 purchases        messages     one, checks    stored
                               it, saves it,  transactions
                               runs the rules
```

- **Simulator** (plain Java): plays a small group of people with different spending habits, like a student, a family, and a frequent traveler, on a clock that runs a simulated month per real minute. It plants realistic problems, plus legitimate purchases that only look like problems, and publishes the truth about every transaction to a separate answer-key topic so detection can be graded later.
- **Kafka:** holds every transaction, in order per card, until the watchdog reads it. If the watchdog goes down, nothing is lost; it picks up where it left off.
- **Watchdog** (Spring Boot): checks that each message is valid, stores it exactly once even if Kafka delivers it twice, and sends anything it can't parse to a dead-letter topic. Then it runs six detection rules on each new transaction (duplicate charges, free trials that turned into paid plans, price hikes, spending spikes, card testing, and charges from a new country) and logs anything they flag, with an explanation like "Whole Foods Market charged $140.84 twice, 24 seconds apart."
- **PostgreSQL:** the stored transactions.

The design, the tradeoffs behind each choice, every rule's thresholds and where they came from, and what I learned building each phase are in [docs/DESIGN.md](docs/DESIGN.md).

## Roadmap

| Phase | What | Status |
|---|---|---|
| 1 | Transaction pipeline: simulator, Kafka, validation, and storage | Done |
| 2 | Six detection rules: duplicate charges, free trial conversions, price hikes, spending spikes, card testing, new-country charges. They log what they flag. | Done |
| 3 | Alerts (saved and published), detection metrics (precision and recall), and a REST API | Planned |
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
- In Kafka UI, Topics → `transactions` → Messages shows the live feed, and `transactions.DLT` holds anything the watchdog rejected.
- To watch the rules: `docker compose logs -f watchdog | grep "Rule hit"`. Hits start once the warm-up ends, about a minute in.
- To look at what's stored:

  ```
  docker compose exec postgres psql -U watchdog -d watchdog \
    -c "select merchant, amount_cents, occurred_at from transactions order by occurred_at desc limit 10"
  ```

## Development

You need Java 21, plus Docker for the full stack. `./mvnw` downloads the right Maven version on first use and checks its checksum, so there's nothing else to install.

```
./mvnw verify                    # build everything and run all the tests
./mvnw -pl simulator -am test    # one module, plus the modules it depends on
```

To run the watchdog from an IDE, start the stack, stop its watchdog container with `docker compose stop watchdog`, and run `WatchdogApplication`. Its defaults point at Kafka on `localhost:9094` and Postgres on `localhost:5432`.

### Project layout

```
transaction-schema/   message formats shared by every program: records, JSON Schemas, topic names
simulator/            the plain Java transaction generator
watchdog-service/     the Spring Boot service: Kafka in, PostgreSQL out, detection rules
config/               simulator presets
docs/DESIGN.md        design, tradeoffs, and the decision log
```

### Conventions

A few rules I follow in this codebase:

- Money is whole cents in a `long`, never a `double`.
- The simulator and the rules only use event time (`occurredAt`). The real clock is only for measuring latency.
- Kafka messages are keyed by `cardId`, so each card's transactions stay in order.
- Kafka can deliver a message more than once, so every database write has to be safe to repeat.
- Messages that can't be parsed go to `transactions.DLT` right away. Anything else, like the database being down, gets retried until it works.
- Messages are read and written through `MessageJson`, which is stricter than Jackson's defaults.
- Database access is plain SQL with `JdbcClient`, no JPA. Schema changes go in a new Flyway migration; a committed one never changes.
- The simulator stays deterministic: the same seed gives the same data.
- Each detection rule is one class with its thresholds in `application.yml` under `watchdog.rules`, and rules never read the answer key; a test checks that.
- Unit tests end in `Test`. Integration tests, starting in Phase 3, will end in `IT`.

### Gotchas

- Restarting the simulator replays from day one, and the watchdog skips everything it already has, so the demo goes quiet for a bit. That's expected.
- Restarting the watchdog empties the rules' memory until Phase 3 adds a warm-up, so some rules stay quiet for a while (new-country for 20 transactions, price-hike for about two months of simulated time).
- Restarting just the Postgres container wipes the demo data. Restart the whole stack instead.
- Kafka has two addresses: `kafka:9092` inside Docker and `localhost:9094` from your machine.
- Maven's version is pinned in two places, `.mvn/wrapper/maven-wrapper.properties` and the build image in both Dockerfiles. Change them together.

## How I built this

I used Claude Code as a development and learning tool while building Watchdog, particularly to help me understand Java, Spring Boot, Kafka, and the technical decisions behind the implementation.
