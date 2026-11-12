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

## Contracts

- `app.campaign.model` defines `Workspace`, `Option`, `Wish`, `AnalysisRun`,
  `Offer`, `Campaign`, `Pledge`, and `Event` as JPA entities, plus their state,
  reason, source, and analysis-label enums. IDs and references are UUIDs;
  provider IDs are strings. Money uses `Long` minor units with a currency code,
  and timestamps use `Instant`. Creation timestamps are supplied by callers.
  Offer terms and analysis snapshots are immutable; collection fields persist
  as JSON text. `Money` and `ShippingAddress` carry payment values across ports.
- `app.campaign.port.PaymentGateway` declares authorize-intent order creation,
  authorization, capture, void, refund, and authorization/capture/refund reads.
  Resource-specific IDs and `PaymentResult` distinguish resource status from
  transport failure. Every operation takes an idempotency key. Its KDoc fixes
  full-payment behavior, retries, and reconciliation responsibilities.
- `app.campaign.port.WishAnalyzer` takes option/wish snapshots and returns
  validated `AnalysisOutput` or a typed `AnalysisResult.Failure`, including
  `NOT_CONFIGURED` for a missing provider key and `TIMEOUT` for an exceeded budget.
  Success exposes the model, shared input hash, completion instant, and validated
  output with flags for immutable run persistence. `AnalysisRun.resultJson` stores
  the JSON-mapper serialization of `AnalysisOutput`; its `optionIds` roster comes
  from the input snapshot, not the model.
  `AnalysisValidationResult`, `AnalysisValidationFlag`, and
  `AnalysisValidationRule` describe validation without implementing it.
- `app.campaign.rules` publishes the three count functions, `thresholdMet`,
  `funded`, campaign deadlines, transition data and rejection functions,
  `validateOffer`, `checkCapacity`, and `idempotencyKey`. Transition checks
  validate edges and reasons; services must apply the documented trigger
  guards and serialize capacity checks.
- `analysisInputHash` hashes option IDs and product fields plus wish IDs and exact
  text using canonical SHA-256 serialization independent of collection order.
  Callers select wishes first; workspace and wish metadata are excluded. Both
  analyzers and callers use this function. `optionWishCounts` derives Compatible,
  Depends (`CONDITIONAL`), Not served (`INCOMPATIBLE`), and Not a request totals
  for every option from validated output. Screens must use these counts, never
  model-supplied counts.
- Time-dependent callers inject `java.time.Clock` and pass explicit instants
  to rules. Tests share `app.testing.InMemoryPaymentGateway`,
  `InMemoryWishAnalyzer`, and `fixedClock`. The fakes record calls and consume
  typed FIFO scripts without making external calls.

## Workspaces and seed data

`app.campaign.workspace.WorkspaceService.create()` atomically persists a new
workspace and its options and wishes through `EntityManager`. Tokens contain
256 secure random bits encoded as unpadded URL-safe Base64. `findByToken`
returns null for an invalid or missing token; scoped operations reject it.
`listOptions`, `listWishes`, `addWish`, and `setWishHidden` always resolve the
workspace token before accessing its data. Intake creates ONSITE wishes,
trims text and optional names, limits text to 500 characters and names to
255, and locks the workspace to enforce 200 total wishes, including hidden
ones. `listWishes` includes hidden wishes by default; analysis callers use
`includeHidden = false`.

`SeedCatalog` loads and validates `seed/options.json` and `seed/wishes.json`
at startup. The maker, characters, and fan comments are synthetic. Costs and
production limits are demo-scale, and the default maximum pledge quantity is
three, available as `definition.defaultMaxQtyPerPledge`. Seed wishes are
IMPORTED stated interest. Stable option and wish keys map to workspace-local
UUIDs through `optionId` and `wishId`, so stored analysis can remap references
without sharing records. Workspace creation seeds only options and wishes;
analysis storage and payment reconciliation belong to their own services.
## PayPal client

`app.paypal.PayPalClient` implements `PaymentGateway` with the
[Orders](https://developer.paypal.com/api/orders/v2) and
[Payments](https://developer.paypal.com/api/payments/v2) APIs. `PayPalProperties`
reads `PAYPAL_BASE_URL`, `PAYPAL_CLIENT_ID`, and `PAYPAL_CLIENT_SECRET`. Only the
exact default URL `https://api-m.sandbox.paypal.com` permits startup. Resource
IDs are encoded and redirects are disabled. Missing credentials permit startup
but calls return `PaymentResult.Failed` with `errorCode = "NOT_CONFIGURED"`.

Tokens refresh 30 seconds before expiry. Connection/read timeouts are 10/20
seconds; transport failures and 5xx allow three retries with the original
mutation key. A 4xx is never retried. Every response's debug ID is logged,
including OAuth and retries; results retain the final response's ID or the
last received ID after a timeout. Credentials and payer data are never logged.

Orders request shipping and authorization; captures are full and final, and
refunds are full. Money conversion is exact. Authorization results carry the
nested resource's status and available payer, shipping, and expiry fields.
Callers reconcile ambiguous failures before changing domain state. HTTP-layer
stub tests require neither network nor credentials.

Response statuses follow the [Orders v2 schema](https://developer.paypal.com/api/orders/v2/schema.json)
and [Payments v2 schema](https://developer.paypal.com/api/payments/v2/schema.json):

| Operation | Accepted provider resource statuses | Port mapping when names differ |
|---|---|---|
| Create order | `CREATED`, `SAVED`, `APPROVED`, `VOIDED`, `COMPLETED`, `PAYER_ACTION_REQUIRED` | `PAYER_ACTION_REQUIRED` to `CREATED`; `SAVED` to `PENDING` |
| Authorize order; read authorization | `CREATED`, `CAPTURED`, `DENIED`, `PARTIALLY_CAPTURED`, `VOIDED`, `PENDING` | `DENIED` to `DECLINED`; `PARTIALLY_CAPTURED` to `CAPTURED` |
| Capture authorization; read capture | `COMPLETED`, `DECLINED`, `PARTIALLY_REFUNDED`, `PENDING`, `REFUNDED`, `FAILED` | `PARTIALLY_REFUNDED` to `PENDING` |
| Refund capture; read refund | `CANCELLED`, `FAILED`, `PENDING`, `COMPLETED` | `CANCELLED` to `FAILED` |
| Void authorization | Empty 204, or 200 authorization representation | Empty response to `VOIDED`; representation uses authorization mappings |

Other status names return `INVALID_RESPONSE`, including names valid only for
another resource. Documented resource states return `PaymentResult.Received`;
that does not imply the requested operation completed. In particular, a created
order requiring payer action succeeds before buyer approval. The existing port
has no partial-payment states: `CAPTURED` on an authorization indicates capture
evidence, never completed full payment; a partially refunded capture remains
unresolved as `PENDING`, never a full refund or fully available payment. Callers
must reconcile the capture/refund resources before counting money. Authorization
expiry remains available through the expiry field and `AUTHORIZATION_EXPIRED`
error mapping; `EXPIRED` is not a documented authorization response status.
## Wish analysis

`DefaultWishAnalyzer` implements `WishAnalyzer` using the OpenAI Responses API.
It sends only the snapshot's product fields and wish IDs/text, separates data
from instructions, and requests a strict JSON schema. Instructions distinguish
hard purchase conditions, preferences, unknowns, and non-requests before comparing
each request with every option. A null proposal is a valid outcome.

Validation applies before returning any result:

- V1 drops unknown wish and option references and records a flag.
- V2 drops ungrounded needs and rationale, flagging quotes that are not literal
  substrings after Unicode whitespace normalization.
- V3 discards model commercial terms. A quoted fan price limit is evidence in
  exact minor units, never an offer price or production decision.
- V4 drops variant suggestions outside the selected option's allowed values.
- V5 rejects multiple proposals rather than choosing one silently.
- V6 rebuilds not-served groups from the selected option's incompatible verdicts.

The validator also requires a complete, unique wish roster and one verdict for
each request/option pair. Quote validation proves textual grounding, not semantic
correctness; classifications and normalized fan limits still require review.
The shared `analysisInputHash` identifies the exact input; `optionWishCounts`
computes display counts from validated output. Success returns the provider's
model ID, injected-clock completion time, hash, output, and validation flags for
the caller to persist as an immutable run. No campaign or payment state changes.

`LLM_API_KEY` is optional at startup; attempts without it return `NOT_CONFIGURED`.
`LLM_MODEL` defaults to `gpt-6.1-sol`; `LLM_DAILY_RUN_CAP` defaults to 10 provider
calls per UTC day (zero disables calls). The synchronized cap is shared by the
singleton analyzer across workspaces, includes failures/retries, and resets on
process restart. Multiple server processes need a shared cap before scaling.
Invalid JSON or invalid output gets at most one retry within a total 60-second
monotonic budget. Transport failures and refusals are typed failures without
automatic retries. Calls have a 10-second connect timeout and the remaining run
budget as their request deadline. Inputs are limited to 200 wishes of at most
500 characters and a 160,000-character data envelope; output is capped at 16,000
tokens. Workspace run quotas and retaining the previous valid run belong to the
caller, which has the required persistence context.

For a one-shot seed run, build the executable JAR, inject credentials, set
`LLM_SEED_RUN=true`, and provide `LLM_SEED_OPTIONS_FILE`, `LLM_SEED_WISHES_FILE`,
and `LLM_SEED_OUTPUT_FILE`. Input files are JSON arrays of `Option` and `Wish`
using their Kotlin property names, UUIDs, and ISO timestamps. Use stable IDs for
fixtures. Run with `--spring.main.web-application-type=none`. The runner writes
the serialized `AnalysisResult.Success` only after validation, refuses to
overwrite an existing file, reports model/latency/flags and shared counts, then
closes the application. It is disabled by default; tests use fake providers only.

API contracts: [Responses](https://developers.openai.com/api/reference/resources/responses/methods/create),
[structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs),
and [error codes](https://developers.openai.com/api/docs/guides/error-codes).
## Campaign services

`CampaignService` is the web-facing application boundary. `createDraft` takes a
workspace ID and `DraftOffer`; `publish`, `cancel`, `startPledge`,
`confirmProduction`, `readCampaign`, `reconcile`, and
`simulateNextCaptureFailure` take `CampaignRef`. `completePledge` takes
`PledgeRef`. All return `ServiceResult` with immutable views or typed rejections.
Published terms have no edit operation. Unpublished drafts do not reserve the
workspace's single active campaign slot.

`CampaignView` includes the three separate counts, minimum progress, remaining
capacity and time, confirmation totals, masked pledge rows, outcome, unresolved
payments, and the provider timeline. Reads reconcile deadlines and pending
payments; `CampaignReconciliationTick` also runs every minute. Only completed
captures release production. A late completed capture on an unsuccessful
campaign is refunded; unresolved captures are never treated as released holds.

`CampaignQueries` resolves workspace tokens for screen reads. `currentCampaign`
returns the active campaign (OPEN, AWAITING_CONFIRMATION, or SETTLING), otherwise
the latest terminal campaign, or null for an empty or draft-only workspace.
It reuses `readCampaign` reconciliation and the existing offer fields and
window timestamps in `CampaignView`. `lookupPledge` verifies workspace ownership
before reconciliation and returns the pledge view, its creation timestamp, and
its campaign view; authorization expiry is available in the pledge view.
Unknown tokens and foreign pledge IDs return `NOT_FOUND`.

`isOpenForPledges` checks only OPEN state and an unexpired window, without payment
calls or capacity guarantees. `quote` also avoids reconciliation and mutations:
it returns an exact minor-unit amount and `CapacityDecision` using the same
quantity and authorized-capacity rules as pledge creation. A closed or missing
open campaign returns `INVALID_STATE`; arithmetic overflow returns
`AMOUNT_OVERFLOW`. Quotes do not reserve capacity.

`makerOffers` returns the newest draft, latest published campaign, and terminal
history with results. Published campaigns sort by opening time descending,
then ID; drafts sort by their creation event time, then ID (missing creation
events sort oldest). Campaign views reuse lazy reconciliation, so newly expired
campaigns appear in history. Multiple drafts remain permitted. These queries
introduce no new state transitions.

Workspace database locks serialize publication and capacity decisions. Local
coordinators run serially to prevent connection-pool starvation. The lock and
its independent durable checkpoints require at least two database connections.
Payment intent, responses, state changes, and compensation survive rollback of
the coordinating transaction. Retries use the pledge's original operation key;
provider evidence controls recovery, including after process interruption.
Ambiguous authorizations remain uncounted and visible for attention. Recovery
does not initiate a new capture after the confirmation deadline or a terminal
outcome; it may replay a provider-confirmed capture to recover its resource ID.

Rejected approvals may move from `CREATED` directly to `VOIDED` or `VOID_FAILED`.
Provider capture evidence may move `CAPTURE_FAILED` or `VOID_FAILED` to
`CAPTURED` or `CAPTURE_PENDING`. These six additional edges do not relax the
campaign's terminal states or count pending money as paid.

The optional `app.campaign.demo-simulation-enabled` property defaults to false.
When enabled, `simulateNextCaptureFailure` arms one campaign-local, explicitly
recorded simulation. It fails the next unattempted capture without calling the
provider. It does not alter a real provider response.

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
