# Architecture

The application is one Spring Boot service written in Kotlin, with
server-rendered pages and a small amount of browser JavaScript for the PayPal
button. This guide fixes the module boundaries so that independent tasks can
proceed in parallel without editing each other's code.

Only the skeleton exists today. The boundaries below are rules for new code,
not a description of finished features.

## Modules

The base package is `app`.

| Package | Owns | Must not |
|---|---|---|
| `app.paypal` | Every call to the PayPal REST API, typed results, idempotency keys | Make domain decisions |
| `app.analysis` | Every call to the language model, and validation of its output | Touch money or campaign state |
| `app.campaign` | Entities, state machines, counts, settlement decisions, persistence | Depend on HTTP, templates, or a concrete API client |
| `app.web` | Controllers, templates, static assets | Decide anything about money or state |

Dependencies point one way: `web` uses `campaign`; `campaign` declares the
interfaces it needs; `paypal` and `analysis` implement them. Nothing depends on
`web`. A money or state decision made in a template or in browser JavaScript
is a defect.

## Vocabulary

Use these terms in code, pages, and documents. Do not introduce synonyms.

| Term | Meaning |
|---|---|
| Workspace | An isolated demo instance addressed by an unguessable token in the URL |
| Option | A product format the maker has approved as feasible |
| Wish | A fan's free-text request; stated interest, never an order |
| Analysis run | One immutable model result over a set of wishes and options |
| Proposal | The single batch an analysis run recommends, with quoted evidence |
| Offer | The exact maker-confirmed product, price, terms, minimum batch, and capacity |
| Campaign | One offer open for pledges within a fixed window |
| Pledge | One fan's payment authorization for a quantity of the offer |
| Settlement | The capture run and any refund or void that follows |

Stated interest, authorized holds, and completed payments are three different
counts. They are stored, computed, and shown separately.

## Configuration

Configuration comes from environment variables. [.env.example](../.env.example)
lists every key name; values live in a secret store, never in a tracked file.

| Key | Purpose | Default |
|---|---|---|
| `SERVER_PORT` | HTTP port | `8200` |
| `DATABASE_URL` | JDBC URL without credentials | In-memory database |
| `DB_USERNAME`, `DB_PASSWORD` | Database credentials, kept out of the URL | In-memory defaults |
| `DB_SCHEMA` | Database schema, so worktrees can share one server | `public` |
| `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET` | PayPal sandbox REST credentials | None |
| `PAYPAL_BASE_URL` | PayPal REST base URL | Sandbox |
| `PAYPAL_WEBHOOK_ID` | Webhook signature verification | None |
| `LLM_API_KEY`, `LLM_MODEL` | Model provider credentials and model name | None |
| `LLM_DAILY_RUN_CAP` | Upper bound on live model calls per day | None |
| `APP_BASE_URL` | Public base URL used in links | Local address |
| `PRODUCT_NAME` | Display name rendered in pages | None |

Only `SERVER_PORT`, `DATABASE_URL`, `DB_USERNAME`, and `DB_PASSWORD` are read
by the skeleton. Add the others with the code that needs them.

A credential is never embedded in a connection string, because a failed
connection can print the whole URL.

## Local runs and worktrees

Without `DATABASE_URL` the application starts on an in-memory database, so a
fresh clone or a new worktree builds, tests, and runs with no services. The
build forces the in-memory database for tests even when `DATABASE_URL` is set,
so tests in different worktrees never share data.
`tools/worktree.sh add` assigns each worktree its own port from 8201 to 8209;
pass it as `SERVER_PORT` when running the application there.
