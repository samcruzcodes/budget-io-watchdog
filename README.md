# Budget.io Watchdog

Watchdog watches card transactions as they happen and flags money leaks (duplicate charges, free trials that turned into paid plans, subscription price hikes, spending spikes) and suspicious charges (card testing, charges from a country the card has never been used in). It's the next version of Budget.io, a manual budget planner I built earlier.

**Status:** Phases 1 to 3 are done. A simulator generates realistic card transactions and publishes them to Kafka, and a Spring Boot service validates them, stores them in PostgreSQL, and runs six detection rules on every new one. Each hit becomes an alert with a plain-English explanation, saved to Postgres and published to Kafka. The service measures its own precision and recall against the simulator's answer key, and serves everything through a REST API with a live stream. The same stack also runs on a local Kubernetes cluster (kind). The dashboard, the benchmark, and the ML comparison are planned. See the [roadmap](#roadmap).

## How it works

```
 simulator  ──►   Kafka   ──►  watchdog  ──►  PostgreSQL
 makes up         holds        reads each     keeps the
 purchases        messages     one, checks    transactions
                               it, saves it,  and alerts
                               runs the rules
                                   │
                                   ├──►  Kafka `alerts`
                                   └──►  REST API and live stream on :8082
```

- **Simulator** (plain Java): plays a small group of people with different spending habits, like a student, a family, and a frequent traveler, on a clock that runs a simulated month per real minute. It plants realistic problems, plus legitimate purchases that only look like problems, and publishes the truth about every transaction to a separate answer-key topic so detection can be graded later.
- **Kafka:** holds every transaction, in order per card, until the watchdog reads it. If the watchdog goes down, nothing is lost; it picks up where it left off.
- **Watchdog** (Spring Boot): checks that each message is valid, stores it exactly once even if Kafka delivers it twice, and sends anything it can't parse to a dead-letter topic. Then it runs six detection rules on each new transaction (duplicate charges, free trials that turned into paid plans, price hikes, spending spikes, card testing, and charges from a new country) and turns anything they flag into an alert, with an explanation like "Whole Foods Market charged $140.84 twice, 24 seconds apart." Alerts are saved to Postgres and published to an `alerts` topic. After a restart it replays recent history from Postgres, so the rules remember each card before it reads anything new.
- **PostgreSQL:** the stored transactions and alerts, plus the answer key, which only the scoring code reads.
- **REST API** (in the watchdog): recent transactions and alerts, each user's recurring charges (subscriptions, rent, and bills that charge monthly at a steady price), a monthly spending summary grouped the way Budget.io groups a budget, detection metrics, and a live stream of new transactions and alerts.

The design, the tradeoffs behind each choice, every rule's thresholds and where they came from, and what I learned building each phase are in [docs/DESIGN.md](docs/DESIGN.md).

## Roadmap

| Phase | What | Status |
|---|---|---|
| 1 | Transaction pipeline: simulator, Kafka, validation, and storage | Done |
| 2 | Six detection rules: duplicate charges, free trial conversions, price hikes, spending spikes, card testing, new-country charges. They log what they flag. | Done |
| 3 | Alerts (saved and published), detection metrics (precision and recall), and a REST API | Done |
| 3.5 | Run the stack on a local Kubernetes cluster (kind) | In progress: the cluster and its demos are done; keeping rule memory when a partition moves to another pod is next |
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
- How well the rules are doing: `curl localhost:8082/api/stats` gives each rule's precision and recall against the simulator's answer key, plus throughput and latency. The demo plants problems far more often than real life, so these aren't real-world numbers; the ones worth quoting will come from the Phase 5 benchmark.
- The API, on port 8082 (a few examples; every endpoint is in [docs/DESIGN.md](docs/DESIGN.md)):

  ```
  curl "localhost:8082/api/alerts?userId=user-0001&limit=5"
  curl "localhost:8082/api/recurring?userId=user-0002"
  curl "localhost:8082/api/spending/summary?userId=user-0002&month=2026-03"
  curl -N localhost:8082/api/stream            # live events; Ctrl+C to stop
  ```

  A bad parameter gets a 400 that says what was wrong. The demo's users are `user-0001` to `user-0003`, and its months start at 2026-01.
- To look at what's stored:

  ```
  docker compose exec postgres psql -U watchdog -d watchdog \
    -c "select merchant, amount_cents, occurred_at from transactions order by occurred_at desc limit 10"
  docker compose exec postgres psql -U watchdog -d watchdog \
    -c "select detector, explanation from alerts order by occurred_at desc limit 10"
  ```

## Running it on Kubernetes (kind)

The same stack on a local, single-node Kubernetes cluster, built with kind. It's a practice cluster on one laptop, not production. You need Docker; the scripts download their own pinned kind and kubectl.

```
scripts/install-tools.sh        # once: kind v0.33.0 and kubectl v1.37.1 into .tools/bin, checksums checked
scripts/k8s-up.sh               # cluster, images, Kafka, Postgres, the simulator, the watchdog; waits until all Ready
scripts/k8s-up.sh --with-ui     # the same, plus Kafka UI
scripts/k8s-down.sh             # delete the cluster and everything in it
```

- Use the pinned kubectl, with the cluster's context: `.tools/bin/kubectl --context kind-watchdog -n watchdog get pods`.
- The API: `.tools/bin/kubectl --context kind-watchdog -n watchdog port-forward svc/watchdog 8082:8082`, then the same `curl` commands as above. Kafka UI: `port-forward svc/kafka-ui 8081:8080`.
- Things to try: delete the watchdog pod and watch a new one warm up and take over (`kubectl delete pod -l app=watchdog`), scale it (`kubectl scale deployment watchdog --replicas=3`), or roll it (`kubectl rollout restart deployment/watchdog`). Then scale the simulator to 0 and run `scripts/k8s-check-no-loss.sh`, which checks that every transaction and alert ID on the topics is in Postgres, and nothing else is.
- With several watchdog pods, the rules' memory of a card stays in the pod that had it when the card's partition moves, and each pod's live stream only shows its own partitions. Both are written up in [docs/DESIGN.md](docs/DESIGN.md), with the measured demo results.

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
k8s/                  Kubernetes manifests for the kind cluster
scripts/              kind setup, up, down, and the no-loss check
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
