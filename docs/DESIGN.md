# Budget.io Watchdog: Design

Watchdog watches card transactions as they happen and flags two kinds of problems:

- **Money leaks:** duplicate charges, free trials that quietly turned into paid plans, subscription price hikes, and spending spikes.
- **Suspicious charges:** card testing (several tiny charges, then a big one) and charges from a country the card has never been used in.

It's the next version of Budget.io, my manual budget planner. This doc covers how Watchdog works, why I built it this way, and where it's going.

**Status:** Phase 1, the transaction pipeline, is built and tested. Everything after that is planned and marked that way below.

## Roadmap

| Phase | What | Status |
|---|---|---|
| 1 | Transaction pipeline: a simulator, Kafka, and a Spring Boot service that validates transactions and stores them in PostgreSQL | Done |
| 2 | Six detection rules, with unit tests | Planned |
| 3 | Alerts, detection metrics (precision and recall), a REST API, integration and API tests | Planned |
| 4 | Dashboard, with a Selenium end-to-end test | Planned |
| 5 | CI with GitHub Actions, and benchmark results in the README | Planned |
| 6 | Data science: compare the rules with an ML model | Planned |

I'd like to finish the dashboard and CI before Phase 6, but Phase 6 only needs the simulator, the labels, and the rules' alerts, so I could also pick it up right after Phase 3.

## Background: Budget.io

Budget.io is a budget planner I built with React, TypeScript, and Vite. You type in your income, expenses, and debts, the data stays in your browser's localStorage, and an OpenAI-backed assistant gives budgeting advice. There's no transaction data: the closest thing is spending you enter by hand.

Its categories are free text. The defaults are Rent/Mortgage, Utilities, Food, and Transportation, and the budget breakdown adds a TP Fund ($100 or $200 of fun money), one line per debt, Savings, and an Emergency Fund.

### How Watchdog connects to it

Watchdog has a fixed list of categories. It's more detailed than Budget.io's because the rules need to tell dining apart from groceries, but every category rolls up into exactly one Budget.io line:

| Watchdog `Category` | Budget.io line |
|---|---|
| `INCOME` | the income field |
| `RENT` | Rent/Mortgage |
| `UTILITIES` | Utilities |
| `GROCERIES`, `DINING` | Food |
| `TRANSPORTATION` | Transportation |
| `SHOPPING` | TP Fund |
| `SUBSCRIPTIONS` | Subscriptions (a custom line) |
| `TRAVEL` | Travel (a custom line) |

Budget.io already supports custom expense lines, so the two new ones need no changes there. The plan (Phase 3) is an endpoint, `GET /api/spending/summary?userId=…&month=YYYY-MM`, that returns income and spending grouped by Budget.io line, so the planner could eventually fill itself in instead of relying on manual entry.

Two other differences from Budget.io:

- Money is stored as whole cents in a `long` (`amountCents`). Floats can't represent amounts like $0.10 exactly, so totals drift, and a `long` holds far more cents than I'll ever need.
- Budget.io has one implicit user per browser. Watchdog adds a `userId` and a `cardId` to every transaction.

## Architecture

```mermaid
flowchart LR
    subgraph sources ["Transaction sources"]
        SIM["Simulator"]
        PLAID["Plaid adapter"]
    end

    subgraph kafka ["Kafka (KRaft mode)"]
        TX(["transactions"])
        LB(["transaction-labels"])
        DLT(["transactions.DLT"])
        AL(["alerts"])
    end

    subgraph svc ["watchdog-service (Spring Boot)"]
        ING["Ingest: validate, store once"]
        RULES["Detection rules"]
        EVAL["Evaluation: precision and recall"]
        API["REST API with live updates"]
    end

    PG[("PostgreSQL")]
    DASH["Dashboard"]
    NB["Notebook: rules vs. ML"]
    BIO["Budget.io planner"]

    SIM --> TX
    SIM --> LB
    PLAID -.-> TX
    TX --> ING
    ING -- invalid --> DLT
    ING --> PG
    ING -.-> RULES
    RULES -.-> PG
    RULES -.-> AL
    LB -.-> EVAL
    EVAL -.-> PG
    PG -.-> API
    API -.-> DASH
    API -.-> BIO
    PG -.-> NB

    classDef planned stroke-dasharray: 5 5
    class PLAID,AL,RULES,EVAL,API,DASH,NB,BIO planned
```

Solid boxes and arrows exist today. Dashed ones are planned.

1. A source publishes each transaction to the `transactions` topic in Kafka, keyed by card. Right now the only source is the simulator, which also publishes a ground-truth label for every transaction to `transaction-labels`.
2. The watchdog reads `transactions`, validates each message, and stores it once in PostgreSQL. Messages it can't parse go to `transactions.DLT`.
3. Planned: the rules check each new transaction, and every hit becomes an alert that's saved to Postgres and published to `alerts`.
4. Planned: an evaluation step compares the alerts with the labels to measure precision and recall. The rules never see the labels.
5. Planned: a REST API with live updates feeds the dashboard, and later the Budget.io planner.

**Why Kafka sits in the middle.** The simulator never talks to the watchdog or the database directly. Kafka holds every message until the watchdog reads it, so the watchdog can go down, come back, and pick up where it left off. Stopping the watchdog for 20 seconds while the simulator kept running shows this: 130 transactions piled up in Kafka, and within seconds of the restart they were all stored, none lost. It also means other programs can read the same stream later without the simulator knowing about them. The tradeoff is that Kafka is one more system to run and understand.

## Phase 1: what's built

### The transaction message

Every source publishes this JSON to `transactions`, keyed by `cardId`:

```json
{
  "schemaVersion": 1,
  "transactionId": "5278a1ec-0766-3f2e-ba13-845077395928",
  "source": "SIMULATOR",
  "userId": "user-0001",
  "cardId": "card-0001",
  "merchant": "Blue Bottle Coffee",
  "category": "DINING",
  "amountCents": 545,
  "currency": "USD",
  "country": "US",
  "occurredAt": "2026-01-01T09:45:10Z",
  "publishedAt": "2026-10-27T23:40:46.732514085Z"
}
```

| Field | Type | Meaning |
|---|---|---|
| `schemaVersion` | int | Always 1 for this version. |
| `transactionId` | UUID | Unique per transaction. It's the idempotency key. |
| `source` | string | `SIMULATOR` today, `PLAID` later. |
| `userId` | string | Who owns the card. |
| `cardId` | string | The card (or account) the transaction posted to. Also the Kafka key. |
| `merchant` | string | Merchant name. |
| `category` | enum | One of the categories above. |
| `amountCents` | long, never negative | Money out, except `INCOME`, which is money in. |
| `currency` | ISO 4217 code | `USD` only for now. |
| `country` | ISO 3166-1 alpha-2 code | The merchant's country. |
| `occurredAt` | ISO-8601 instant | When the purchase happened (event time). For simulated data, this is the simulator's clock. |
| `publishedAt` | ISO-8601 instant | Real time when the source published it. End-to-end latency starts here. |

The decisions behind it:

- **Keyed by card.** Kafka only keeps messages in order within a partition, and messages with the same key always land in the same partition. Keying by card keeps each card's transactions in order, which the rules will depend on.
- **Two timestamps.** Rules will use `occurredAt` only, so they give the same answer however fast the simulator runs. Latency uses `publishedAt` and the real clock.
- **One contract, enforced.** The `Transaction` record in `transaction-schema` is shared by every Java producer and consumer, and its constructor rejects invalid values, so an invalid transaction can't exist in the code. All JSON reading and writing goes through one shared `MessageJson` configuration that's stricter than Jackson's defaults, because by default Jackson turns `15.49` into `15` and accepts `"1549"` as a number. A JSON Schema file (`transaction.v1.schema.json`) describes the same contract for sources written in other languages, and contract tests check that the record and the schema accept and reject the same messages.
- **JSON, not Avro.** JSON is readable in Kafka UI and in logs, and there's no Schema Registry to run. Avro or Protobuf with a registry would be the upgrade if the schema starts changing a lot.
- **Changing it later.** Consumers ignore fields they don't know, so a producer can add a field without breaking anyone. A breaking change would bump `schemaVersion`, and a consumer sends versions it doesn't understand to the DLT.

### Ground-truth labels

The simulator makes up every transaction, so it already knows which ones are problems. It publishes one label per transaction to `transaction-labels`, keyed by `transactionId`:

```json
{
  "schemaVersion": 1,
  "transactionId": "5278a1ec-0766-3f2e-ba13-845077395928",
  "persona": "STUDENT",
  "issueType": "NONE",
  "scenarioId": null,
  "edgeCase": null
}
```

- `issueType` is `NONE` for normal transactions, including legitimate ones that just look suspicious.
- It describes the transaction itself. In a duplicated charge, the original is `NONE` and the copy is `DUPLICATE_CHARGE`. For a free trial, the $0 or $1 signup is `NONE` and the first full charge is `TRIAL_CONVERSION`. On a binge day, every purchase in that category is `SPENDING_SPIKE`, because the spike is about the day's total. In card testing, the tiny probes and the big charge are all `CARD_TESTING`.
- `scenarioId` groups the transactions that belong to one injected issue or look-alike, like a duplicated pair, a trial and its conversion, or a whole trip. It's `null` for ordinary transactions.
- `edgeCase` names the legitimate look-alike a transaction belongs to, for error analysis.
- `persona` is there for analysis. It's never an input to detection.

Labels get their own topic on purpose. It's like keeping an exam's answer key in a separate folder from the questions: if the answers traveled with the transactions, detection could see them and the scores would mean nothing (in data science this is called label leakage). Real transactions, from Plaid for example, won't come with labels at all, so detection can't depend on them. Only the evaluation code will ever read this topic.

### The simulator

The simulator is a plain Java program, no Spring, that pretends to be a group of people spending money.

**A simulated clock.** Simulated time = start date + real time elapsed × speed. The demo runs one simulated month per real minute, so a day takes about 2 seconds, and free trials and subscription cycles that take weeks play out in under a minute. Setting `SIM_DAYS_PER_MINUTE=max` drops the waiting, which is what benchmarks and dataset generation need.

**Repeatable runs.** For each simulated day, every user's spending behaviors plan that day's transactions. The day is sorted by time, and each transaction is published when the clock reaches it. Simulated people live in UTC, which keeps time zones out of the picture. All randomness comes from one seed: a root generator hands each user their own stream with `SplittableRandom.split()`, in user order, so adding users never changes existing users' histories. Transaction IDs are name-based UUIDs built from the seed, the user, and a counter. The same settings always produce the same transactions, labels, and IDs, which makes results reproducible and makes a simulator restart harmless, because the watchdog skips IDs it already has.

#### Personas

Each user has one card, a home country of US, and a persona that sets their income, bills, habits, and how often they start free trials or travel:

| Persona | What it looks like |
|---|---|
| `STUDENT` | small biweekly paychecks, shared rent, coffee most mornings, lots of dining and delivery, 6 subscriptions, about one free trial a month, travels the least |
| `YOUNG_PROFESSIONAL` | a salary, rent, dining out, rideshare, 5 subscriptions, average travel |
| `FAMILY` | two paychecks, a mortgage, big grocery runs, higher utility bills, a car, the most shopping |
| `FREQUENT_TRAVELER` | a high salary, lots of dining out, rideshare, trips about five times as often as average |
| `RETIREE` | one monthly payment, steady bills, lower spending, a car, 3 subscriptions, rarely tries free trials |

The exact numbers are in `Persona.java`. Personas are assigned in exact proportion (`SIM_PERSONA_MIX`) with weighted round-robin, not by random draw, so 1,000 users at 25% students means exactly 250 students. The live demo runs 3 users (a student, a family, and a frequent traveler). The benchmark and the Phase 6 dataset will use about 1,000.

**Everyday spending:** biweekly or monthly paychecks, rent or mortgage on the 1st, monthly utilities (electricity and water vary, internet and phone don't), groceries, dining, coffee, gas or rideshare, shopping, 3 to 6 subscriptions depending on the persona, occasional free trials, and occasional trips. Amounts come from log-normal distributions, because real spending is skewed: lots of small purchases and a few big ones. Times of day follow the activity, like coffee between 7 and 10 am and paychecks at 6 am.

#### Injected issues and look-alikes

| Issue (`issueType`) | How it's injected | Legitimate look-alikes (`edgeCase`) | What a good rule should do |
|---|---|---|---|
| `DUPLICATE_CHARGE` | a card purchase posts again 5 to 90 seconds later | `DOUBLE_COFFEE`: the same coffee at the same price, 1 to 5 minutes apart (buying for a friend) | catch the duplicate; the double coffee is an expected false positive, and the same coffee hours apart must not fire |
| `TRIAL_CONVERSION` | a $0 or $1 trial converts to full price 7 to 30 days later | `CANCELLED_TRIAL` (no conversion); `APP_STORE_PURCHASES`: $0.99, then $9.99 at the same merchant 5 to 20 days later | catch the conversion; the App Store pair is an expected false positive |
| `PRICE_HIKE` | a subscription costs 10 to 25% more at renewal | utility bills that vary every month (ordinary behavior, not labeled) | catch the hike, never fire on utilities |
| `SPENDING_SPIKE` | a binge day: 3 to 5 extra purchases in dining or shopping | `LEGIT_BIG_PURCHASE` (like a laptop); spending during `LEGIT_TRAVEL` | catch the binge; the look-alikes are expected false positives |
| `CARD_TESTING` | 3 to 5 charges of $0.50 to $2.00 at different merchants, then one of $150 to $900, all within an hour | `TOLL_ROAD_TRIP`: several tolls, then a hotel, within an hour | catch the attack; the road trip is an expected false positive |
| `NEW_COUNTRY` | a charge from a country the card has never been used in | `LEGIT_TRAVEL`: a flight booked at home, then charges abroad; `FOREIGN_ONLINE_ORDER`: one order from a foreign shop with no trip | catch the fraud; stay quiet when a trip was booked first; the online order is an expected false positive |

The look-alikes exist so the metrics can't be trivially perfect. No issues or look-alikes are injected during a warm-up period (30 simulated days by default), because several rules need history first. Trips and injected fraud draw countries from the same neutral list, so no country is treated as inherently risky. Fraud uses fictional merchant names, so no real company is shown as a fraudster.

#### Configuration

The simulator reads environment variables, with defaults that work for a local run. `config/simulator.demo.env` is the preset that `docker compose up` uses.

| Variable | Demo value | Meaning |
|---|---|---|
| `SIM_SEED` | `42` | The seed for all randomness. |
| `SIM_USERS` | `3` | How many simulated people. |
| `SIM_PERSONA_MIX` | `STUDENT:1,FAMILY:1,FREQUENT_TRAVELER:1` | Persona weights, assigned in exact proportion. |
| `SIM_START_DATE` | `2026-01-01` | The first simulated day. |
| `SIM_DAYS_PER_MINUTE` | `30` | Simulated days per real minute, or `max`. |
| `SIM_DURATION_DAYS` | `0` | Stop after this many simulated days (0 runs until stopped). |
| `SIM_ISSUES_START_AFTER_DAYS` | `30` | The warm-up before anything is injected. |
| `SIM_RATE_*` | turned up | One rate per issue and look-alike. The unit is per card purchase, per renewal, per morning coffee, a share of free trials, or per user per day, depending on the rate. |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | Where to publish. |

The demo turns the rates up so problems show up every few seconds. A benchmark preset (Phase 5) will keep problems rare, well under 1% of transactions, while still producing a few hundred of each type.

### Ingesting transactions

The watchdog is a Spring Boot app. For each message on `transactions`:

1. A `@KafkaListener` receives it. Three consumer threads share the topic's three partitions, so each card's transactions are handled in order by one thread.
2. It parses the JSON into a `Transaction`. A message that fails goes straight to `transactions.DLT`, with the reason in its headers and a warning in the log, so one bad message can't block a partition.
3. It stores the transaction with `INSERT ... ON CONFLICT (transaction_id) DO NOTHING`. If the row already exists, Kafka delivered the same message twice and the watchdog skips it. A duplicate message must never look like a duplicate charge.
4. Kafka's offset, its bookmark for where the watchdog is in the topic, gets committed after processing.

Running the rules and saving alerts come in Phases 2 and 3.

**When things go wrong:**

- **At-least-once delivery.** Offsets are committed after processing, so after a crash a message might be delivered again, but it's never lost. That's why every write has to be safe to repeat.
- **Bad data vs. outages.** Invalid messages go to the DLT right away, because retrying won't fix them. Anything else, like Postgres being down, gets retried with growing waits (1 second, doubling up to 30 seconds) without giving up. Skipping would lose a valid transaction and break per-card ordering, so the partition just waits. The catch is that a bug that throws on a valid message would also be retried forever and stall its partition, so the logs need watching.
- **Why not exactly-once?** Kafka transactions would add a lot of machinery. At-least-once delivery plus idempotent writes gives the same visible result here.

### Storage

PostgreSQL 18, with one table so far:

| Table | Columns |
|---|---|
| `transactions` | every message field plus `processed_at` (when the watchdog stored it). Primary key `transaction_id`; indexes on `(card_id, occurred_at)` and `processed_at`. |

Flyway applies numbered SQL migrations at startup. A committed migration never changes; schema changes go in a new file. I went with Spring's `JdbcClient` and plain SQL instead of JPA: every query is visible, the planned stats need SQL features like `percentile_cont`, and there's no object graph for an ORM to help with.

### Running it locally

`docker compose up --build` runs everything:

| Service | Image | What it does | Port on your machine |
|---|---|---|---|
| `kafka` | `apache/kafka:4.2.2` | a single node acting as both broker and KRaft controller | 9094 |
| `kafka-init` | `apache/kafka:4.2.2` | creates the topics, then exits | none |
| `postgres` | `postgres:18.6-alpine` | storage | 5432 |
| `simulator` | built from `simulator/Dockerfile` | the transaction source | none |
| `watchdog` | built from `watchdog-service/Dockerfile` | consumer and storage (the API comes in Phase 3) | none yet |
| `kafka-ui` | `kafbat/kafka-ui:v1.5.0`, profile `tools` | browse topics and messages | 8081 |

| Topic | Partitions | Key |
|---|---|---|
| `transactions` | 3 | `cardId` |
| `transaction-labels` | 3 | `transactionId` |
| `transactions.DLT` | 3 | the original key. It needs at least as many partitions as `transactions`, because a failed message keeps its partition number. |
| `alerts` (Phase 3) | 3 | `cardId` |

A few details:

- **Topics are created explicitly.** Kafka's automatic topic creation is off, so a typo can't silently create a one-partition topic. The `kafka-init` container creates them, and the apps wait for it to finish.
- **Two Kafka listeners.** Containers connect to `kafka:9092`. Programs on your machine, like the watchdog run from an IDE, connect to `localhost:9094`.
- **Local only.** Every port is bound to 127.0.0.1, and the credentials are for local development only.
- **A throwaway demo.** Kafka and Postgres keep their data in memory (tmpfs), so every `docker compose up` starts a fresh, repeatable run. Without that, a restarted simulator would replay transactions the watchdog already has, and the demo would go quiet until it caught up. Kafka runs as a non-root user, so its tmpfs needs `mode=1777`. Restarting just the Postgres container wipes its data.
- **Images.** Each Dockerfile builds in the official `maven:3.9.16-eclipse-temurin-21-noble` image (the same Maven and Java as `./mvnw`) and runs on a smaller `eclipse-temurin:21` Java runtime image as a non-root user. Tests run in CI, not inside image builds.

### Testing so far

`./mvnw verify` runs 78 unit tests:

- **`transaction-schema` (35):** contract tests that give the `Transaction` record and the JSON Schema the same valid and invalid messages and check that they agree, the label rules, and the Budget.io category mapping.
- **`simulator` (38):** the same seed gives identical data; adding users doesn't change existing ones; every transaction makes a valid message with exactly one label; nothing is injected during the warm-up; scenario counts land within 15% of their configured rates over 300 users and a simulated year; each scenario has the shape the rules will look for; plus the persona assignment and the clock math.
- **`watchdog-service` (5):** parsing, including that bad input becomes an `InvalidTransactionException`.

Manual checks on the running stack:

| Check | Result |
|---|---|
| Transactions reach Postgres | 88 to 126 rows in 10 seconds |
| Messages have the right keys | transactions keyed by card ID, labels by transaction ID |
| A malformed message | lands in `transactions.DLT` with the reason in its headers; the watchdog keeps running and logs a warning |
| The same message sent twice | still one row, logged as "already stored" |
| Simulator restarted | the 79 replayed messages were skipped, no duplicate rows |
| Postgres paused for 30 seconds | the backlog grew to 128 messages, then drained to 0 within 10 seconds, with nothing sent to the DLT |
| Watchdog stopped for 20 seconds | 130 transactions waited in Kafka and were all stored after the restart; the oldest was 30 seconds late |

With the simulator paused, the topic held 1,032 distinct transaction IDs and Postgres held exactly those 1,032 rows. On my laptop, steady-state latency from publish to stored was 9.6 ms at the median and 14.2 ms at the 95th percentile.

Integration tests with real Kafka and Postgres (Testcontainers), API tests, a browser end-to-end test, and CI come in Phases 3 to 5.

### What Phase 1 taught me

A few real bugs and surprises came up while building Phase 1. I'm keeping them here because each one changed the design.

- **Correlated random numbers.** The first version gave each user a seed of `seed ^ (n * 0x9E3779B97F4A7C15)`. That constant turns out to be `SplittableRandom`'s own internal step, so user 65 replayed user 1's numbers shifted by 64 draws, and 300 users behaved like about 64. The rate tests caught it: five scenario counts came out 20 to 25% too high. Switching to `split()` fixed it.
- **Lenient JSON.** By default, Jackson truncates `15.49` to `15` and accepts the string `"1549"` as a number. That's why all message parsing goes through `MessageJson`, and why the contract tests cover both cases.
- **A silent dead-letter topic.** The first malformed-message check worked, but the watchdog logged nothing, so bad data could pile up unnoticed. It now logs a warning with the reason for every message it sends to the DLT.
- **Kafka couldn't write its data.** The first `docker compose up` failed because Kafka runs as a non-root user and the in-memory folder wasn't writable. The fix was `mode=1777` on the tmpfs mount.
- **One checksum, two downloads.** The Maven wrapper downloads a `.zip` when `unzip` is installed and a `.tar.gz` when it isn't, and a pinned checksum can only match one of them. That's why the Docker builds use the official Maven image instead of `./mvnw`, which means Maven's version is pinned in two places.
- **Checking the check.** The first "did every transaction make it to Postgres?" comparison read the topic while the simulator was still writing to it and reported 12 extra rows, which looked like a bug. Pausing the simulator gave a clean comparison, and the counts matched exactly.

## Planned work

### Phase 2: detection rules

Each rule is its own class behind one interface, so adding a rule means adding a class:

```java
public interface DetectionRule {
    String id();
    Optional<RuleHit> evaluate(Transaction tx); // reads and updates this rule's per-card state
}
```

Each rule will also say which issue type it detects, so Phase 3 can score it against the labels, and an ML model could later be scored the same way. A hit carries a plain-English explanation, like "Netflix charged you $15.49 twice, 40 seconds apart," plus the numbers behind it.

| Rule | Kind | Fires when |
|---|---|---|
| `duplicate-charge` | leak | the same merchant and amount on the same card within 10 simulated minutes |
| `trial-conversion` | leak | a charge of $1 or less, then the same merchant charges at least 5 times that amount 5 to 45 days later |
| `price-hike` | leak | a merchant has charged about monthly (26 to 35 days apart) at a stable price (the last two charges within 1%), and the new charge is higher |
| `spending-spike` | leak | today's spending in a category is at least 3 standard deviations above this card's usual day; needs 14+ days of history and a minimum dollar amount; at most once per category per day |
| `card-testing` | suspicious | 3+ charges of $2 or less within 60 minutes, then a charge of $100 or more |
| `new-country` | suspicious | the first charge from a country this card hasn't used, unless there was a travel charge in the last 30 days; needs 20+ prior transactions; flagged countries don't count as known, so fraud can't teach the rule it's normal |

"Suspicious charge" is split into two rules so each class does one thing and gets its own precision and recall. Rules ignore income, and they only use event time. The thresholds are starting points I'll tune against the simulator.

**Where each card's history lives.** The rules need history: recent charges, running averages, countries seen. The options I looked at:

| Option | Pros | Cons |
|---|---|---|
| **In memory inside each rule, keyed by card, rebuilt from Postgres at startup (my pick)** | fast, simple, and the rules can be unit tested with no infrastructure | lives in one process; running several watchdogs would mean rebuilding state whenever partitions move |
| Query Postgres for every transaction | always durable, nothing to rebuild | a database round trip per rule per transaction, and rule tests need a database |
| Kafka Streams state stores | the standard streaming answer; durable, and handles partitions moving | a lot of new concepts for this project |
| Redis | shared and fast | another service to run and explain |

In-memory state works here because each card belongs to one thread at a time, so there's no per-card locking. It also stays small: time windows get pruned, the spending-spike rule keeps a running count, mean, and variance per category (Welford's algorithm), and price history keeps only the last three charges per merchant. At startup, the watchdog would replay about 90 simulated days from Postgres through the rules, with alerts switched off. Scaling out to several watchdogs would mean moving to Kafka Streams.

The subscriptions list for the dashboard will be computed on request from the `transactions` table, using the same recurring-charge check as the price-hike rule, so there's no second copy of subscription data to keep in sync.

### Phase 3: alerts, API, and metrics

- **Alerts** get saved to an `alerts` table (unique on transaction and rule, so a redelivered message can't create a second alert) and published to the `alerts` topic. Each alert records which detector raised it as plain text, so a future ML detector could add alerts without a schema change. Saving to Postgres and publishing to Kafka aren't atomic: a crash in between would leave an alert that was saved but never published. The usual fix is the transactional outbox pattern; for now I'll document the gap instead of building it.
- **Labels** get consumed into a `ground_truth_labels` table. There's no foreign key to `transactions`, because the two topics aren't ordered relative to each other.
- **The API:**

| Endpoint | Returns |
|---|---|
| `GET /api/transactions?userId=&limit=` | the newest transactions |
| `GET /api/alerts?userId=&ruleId=&limit=` | the newest alerts, with explanations |
| `GET /api/subscriptions?userId=` | detected subscriptions: price, previous price, status, next expected charge, yearly cost |
| `GET /api/stats` | per rule: precision, recall, true and false positives, and false negatives; plus throughput and latency |
| `GET /api/spending/summary?userId=&month=YYYY-MM` | income and spending grouped by Budget.io line |
| `GET /api/stream` | live transactions and alerts as Server-Sent Events |
| `GET /actuator/health` | health checks for Docker |

Live updates use Server-Sent Events instead of WebSockets because the data only flows one way, from the server to the browser, and browsers reconnect automatically.

- **Metrics:**
  - **Precision** for a rule is the share of its alerts that landed on transactions labeled with its issue type.
  - **Recall** is the share of injected scenarios of that type that got at least one alert. Counting by scenario means a caught card-testing attack counts once, whichever of its transactions the alert fired on.
  - **Throughput** is transactions stored per second over the last minute.
  - **Latency** is `processed_at - published_at`, reported as the median, 95th, and 99th percentile over the last 5 minutes. It assumes the simulator and the watchdog share a clock, which is true on one machine.
- **Tests:** Testcontainers integration tests with real Kafka and Postgres, and REST Assured tests for the API. This is also when I'll turn on Maven Failsafe, so `verify` runs integration tests (`*IT`) too.

### Phase 4: dashboard

A React, Vite, and TypeScript dashboard, served by nginx. It keeps Budget.io's identity (the name, Poppins headings, the `#4CAF50` green, the `#F4F4F9` background) but with hand-written CSS instead of Bootstrap, and a darker green (`#2E7D32`) for text, because `#4CAF50` on white fails WCAG AA contrast. The plan is a live transaction feed, alerts with plain-English explanations, the subscriptions list, and a detection-quality table. A Selenium test will run it headless against the full stack.

### Phase 5: CI and benchmark

GitHub Actions will run the backend build and tests, the dashboard build, and the end-to-end test on every push. A benchmark with about 1,000 simulated users at max speed will measure maximum throughput, latency at a steady load below that maximum, and precision and recall over a full simulated year. Those numbers go in the README, and the same run produces the Phase 6 dataset.

### Phase 6: rules vs. ML

The goal isn't to replace the rules. I want to see whether a model trained on labeled history catches patterns the rules miss, where it does worse, and whether the two work better together. I'll refine this plan before starting Phase 6.

The rough plan:

- Export transactions, alerts, and labels to Parquet and explore them in a notebook with pandas, DuckDB, and scikit-learn.
- Build features for each card in time order, using only what was known before each transaction.
- Split by time (train on earlier months, test on later ones), and possibly test on a differently seeded group of people too.
- Compare a supervised model (gradient boosting) and an unsupervised one (Isolation Forest) against the rules on the same test period: precision and recall per issue type, precision-recall curves and threshold choices, and a look at which mistakes each approach makes.
- Never use the persona as a feature, because real transactions don't come with one.

What the results can and can't show:

- The simulator has giveaways a model could memorize. Fraud uses its own fictional merchants, card-testing probes come from a fixed list, and big purchases have fixed prices. Phase 6 has to deal with these, either by leaving those columns out of the model's inputs or by making the simulator less predictable. Otherwise a high score just means the model learned the simulator.
- The simulator's labels are perfect and instant. Real labels show up late and noisy.
- The rates of each issue are ones I chose, not measured from real data, and there's no fraudster adapting to the detector.

So the comparison shows the method, not real-world performance.

### Further out

- **Plaid adapter:** a module that converts Plaid sandbox transactions into the same message format and publishes them to `transactions`. The watchdog wouldn't change. Plaid data has no labels, so it's left out of precision and recall.
- **Budget.io reads real spending:** the planner calls `/api/spending/summary` and fills in income and expenses itself.
- **Kubernetes (kind):** every service is already configured through environment variables and the watchdog rebuilds its state at startup, so it should move over cleanly.
- **Rules and ML together (stretch goal):** let Watchdog use both rule hits and a model's score. The simplest fit for this architecture is a separate scoring service that reads `transactions` from Kafka and publishes its own alerts, so the rules, the simulator, and Plaid don't change.
- **Labels from real outcomes:** real transactions don't come with an answer key. Labels show up later, when a customer confirms a flagged charge was theirs or disputes one. That feedback is delayed and mostly covers transactions I flagged, so fraud I missed only shows up if someone disputes it. Collecting and evaluating those outcomes would be its own project.

## Decision log

Some of these cover parts that aren't built yet.

| Decision | What I chose | Alternatives | Why |
|---|---|---|---|
| Build tool | Maven with the wrapper | Gradle | easy to read, the unit and integration test split is built in, and Gradle's speed doesn't matter at this size |
| Framework | Spring Boot 4.1 on Java 21 | Spring Boot 3.5 | it's the current release line |
| Messaging | Kafka 4.2 in KRaft mode | RabbitMQ; Kafka with ZooKeeper | per-card ordering and replay; Kafka 4 dropped ZooKeeper |
| Message format | JSON with a `schemaVersion`, plus a JSON Schema | Avro or Protobuf with a Schema Registry | readable, with no extra service to run |
| Message key | `cardId` | `userId`; no key | keeps each card's transactions in order for the rules |
| Delivery | at-least-once with idempotent writes | exactly-once with Kafka transactions | the same visible result with less machinery |
| Rule state | in memory, keyed by card, rebuilt from Postgres | Postgres per transaction; Kafka Streams; Redis | fast and easy to unit test; Kafka Streams is the path if it needs to scale |
| Data access | `JdbcClient` with plain SQL, plus Flyway | JPA/Hibernate; Spring Data JDBC | every query is visible, the stats need SQL features, and there's no object graph |
| Money | `long` cents | `BigDecimal`; `double` | exact and simple with one currency |
| Time | event time for rules, the real clock for latency | the real clock everywhere | rules give the same answer at any simulator speed |
| Ground truth | a separate topic, one label per transaction, grouped by scenario | Kafka headers; fields in the message | detection can't see the labels, recall counts scenarios, and ML gets normal examples too |
| Simulator | plain Java with the Kafka client, seeded, planned day by day | a Spring Boot app | shows the raw producer API, and runs are reproducible |
| Simulated users | personas in exact proportion; 3 for the demo, about 1,000 for the benchmark | a single user | realistic variety for the benchmark and the dataset |
| Live updates | Server-Sent Events | WebSockets; polling | one-way push over plain HTTP, with automatic reconnects |
| Dashboard styling | hand-written CSS | Bootstrap; Tailwind; component kits | its own look, and every style is something I can explain |
| Demo state | in memory, fresh on every `up` | named volumes | a repeatable demo with no stale data |
| End-to-end test | Selenium with headless Chrome | Playwright; Cypress | it's the browser-testing tool I picked for this project's stack |
| ML comparison | gradient boosting and Isolation Forest vs. the rules, with a time-based split | a random split; judging by accuracy | a random split leaks the future, and accuracy is meaningless when problems are rare |

## Known limitations

- There's one watchdog instance. Once the rules exist, their state lives in its memory.
- The demo's Kafka is a single node that keeps data in memory. If Kafka itself goes down, the demo loses whatever it was holding. Real setups write to disk and keep copies on several servers.
- Retrying forever protects valid transactions during an outage, but a bug that throws on a valid message would stall its partition until someone fixes it.
- There's no outbox yet, so in Phase 3 an alert could be saved but never published if the process crashes between the two steps.
- The spending-spike z-score assumes daily totals are roughly normal. Real spending is skewed, which will cause extra false positives. Phase 6 should measure how many.
- New cards have no history. The simulator waits 30 simulated days before injecting anything, but a real system would need a fallback, like population-wide baselines.
- Thresholds and simulator rates are starting values.
