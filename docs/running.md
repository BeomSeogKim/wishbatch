# Running locally

Wishbatch serves maker and fan pages, creates isolated demo workspaces, shows
stored analysis, and publishes offers. It uses the PayPal sandbox only.
External keys are needed only for live analysis and payments. Use synthetic
data: workspace tokens grant access to both fan and maker routes.

To try the hosted build instead, open <https://wishbatch.onrender.com/> and
follow its [testing guide](https://wishbatch.onrender.com/guide). Browsing
needs no setup or credentials; payments need a PayPal sandbox personal account.
The free instance sleeps after 15 idle minutes, so the first request can take
about a minute. See [hosting and keep-warm behavior](deploying.md).

## Gradle and the in-memory database

Install JDK 21, then run from the repository root:

```sh
unset DATABASE_URL DB_USERNAME DB_PASSWORD
./gradlew bootRun
```

Open <http://localhost:8200/> and choose **Start a demo**. The home page returns
HTTP 200. Each demo creates three options, forty synthetic wishes, and a stored
analysis with references remapped to that workspace. Inspect Options, Wishes,
and Proposal, then review the offer terms, resolve or dismiss maker questions,
and confirm you can deliver before publishing. The offer's payment button is
unavailable until sandbox credentials are configured.

The stored result is labeled as such; opening a workspace does not call a model.
Add or hide wishes to try intake; these changes do not rewrite stored analysis.
Re-run analysis needs a model key. Without it, the page explains the missing
configuration and keeps the previous result. The maker dashboard and fan offer
show stated interest, authorized units, and completed payment units separately.

The `/guide` page explains the maker and fan paths. Finish or cancel sandbox
campaigns before stopping an in-memory run.

Stop with Ctrl+C. In-memory data is lost when the process stops. Set
`SERVER_PORT` to use a different port. Do not source `.env.example`: its empty
values are a key inventory, not working defaults. Gradle does not load `.env`
automatically. Inject any service credentials into the process environment.

## Docker Compose and PostgreSQL

Install Docker with Compose and start its engine:

```sh
docker compose up --build
```

This starts PostgreSQL, waits for its health check, builds the app, and serves
it on <http://localhost:8200/>. The application performs a compatibility upgrade
before JPA and defaults to Hibernate `update` on H2 and PostgreSQL. See
[startup schema upgrades](schema-upgrades.md) for supported changes, explicit
overrides, and mixed-version restrictions. Tests exercise disposable H2 and
isolated PostgreSQL schemas; they do not establish a hosted database upgrade.

In another terminal:

```sh
curl -i http://localhost:8200/
```

Choose **Start a demo** in the browser and follow the same journey as the Gradle
path. Stop the app and database with Ctrl+C or `docker compose stop`.
The named PostgreSQL volume survives stops and `docker compose down`. Run
`docker compose down --volumes` only when deliberately discarding that demo data.

The Compose database credentials are local-only development values. Set
`SERVER_PORT` before starting to change the exposed and application ports.
Compose passes the payment and analysis keys listed below from the environment;
keep their values outside Git. Unlike Gradle, Compose also reads a local `.env`
file by default. Do not set optional variables to empty values when a default
is required, especially `PAYPAL_BASE_URL`, `LLM_MODEL`, and `LLM_DAILY_RUN_CAP`.

## Optional service configuration

| Operation | Required environment | Optional settings |
|---|---|---|
| Pages, wishes, stored analysis, offer publication | None | `SERVER_PORT`, `PRODUCT_NAME` |
| Sandbox checkout and settlement | `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET` | `PAYPAL_BASE_URL` defaults to the only accepted sandbox URL |
| Fresh analysis | `LLM_API_KEY` | `LLM_MODEL` defaults to `gpt-6.1-sol`; `LLM_DAILY_RUN_CAP` defaults to 100 provider calls |
| Persistent database outside Compose | `DATABASE_URL`, appropriate `DB_USERNAME` and `DB_PASSWORD` | See [schema upgrades](schema-upgrades.md) for supported databases and settings |

Use sandbox REST application credentials and a sandbox buyer account. After
publishing an offer, open its fan link, choose a quantity, select **Update total**,
and approve the PayPal authorization. The maker can confirm production once
authorized units reach the minimum, even before closing. Confirmation captures
payments; enough completed units fund the batch. A shortfall refunds captures
and voids remaining holds. The [README's sandbox evidence](../README.md#evidence-and-limits)
records fresh-clone and hosted manual browser checks on 2026-11-12, including
the checks and their limits. These were not automated repository tests;
hosted cold start and persistence across a service restart remain unchecked.

To expose the labeled capture-failure simulation on the maker dashboard:

```sh
./gradlew bootRun --args=--app.campaign.demo-simulation-enabled=true
```

The simulation fails one capture locally without sending that capture request.
Other captures, refunds, and hold releases still use the PayPal sandbox.
It is disabled by default.

Live analysis uses a 60-second budget and preserves the last valid run on
failure. The workspace permits ten configured analysis attempts. The daily
provider-call cap includes retries and is process-local. For Gradle/JAR runs,
`LLM_REASONING_EFFORT`, `LLM_BATCH_SIZE`, and `LLM_CONCURRENCY` are additional
controls; Compose does not currently forward them. See
[configuration](architecture.md#configuration) for all defaults and unused keys.
The optional sandbox webhook requires `PAYPAL_WEBHOOK_ID` and sandbox REST
credentials. `POST /webhooks/paypal` verifies signatures before recording events
and triggering provider-read reconciliation. Without the ID it returns 503;
checkout and polling remain available. Enabled webhook processing requires at
least three database connections and permission to create its inbox table. See
the [webhook setup and contract](../src/main/kotlin/app/paypal/README.md).
Source support alone does not mean a webhook is deployed or registered.

Never commit credentials or put them in a JDBC URL. Do not put real buyer or
customer data into this demo.

## Tests

```sh
tools/verify.sh
python3 -m unittest discover -s evaluation -p 'test_*.py'
node --test src/test/js/pledge.test.cjs
```

The main checks run repository guards, their Python tests, and Gradle checks.
The evaluation and JavaScript commands are separate. Tests need no credentials,
network services, shared database, or fixed ports. The first Gradle build may
download dependencies. Tests always use an isolated in-memory database even if
`DATABASE_URL` points elsewhere. For only Kotlin/application tests, run
`./gradlew test`. See [evaluation](evaluation.md#reproduce) for offline scoring;
live model collection is a separate paid operation, never part of tests.
