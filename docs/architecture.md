# Architecture

Wishbatch is one Kotlin/Spring Boot service with server-rendered Thymeleaf
pages, JPA persistence, and a small amount of checkout JavaScript. It connects
wish interpretation to a maker-confirmed offer and sandbox payment settlement.
The default runtime uses H2; Compose uses PostgreSQL. Provider calls are isolated
behind ports, while campaign code owns every money and state decision.

A workspace starts with synthetic data and stored analysis, so the review and
offer-publication journey works without external keys. Live model analysis and
PayPal payments require their own credentials. This is a disposable prototype:
workspace tokens are access links, not separate maker/fan authentication.

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

## Public journey

`POST /demo` creates a workspace and redirects to `/w/{token}/maker`.
Maker routes under that prefix expose options, wish import/hiding, proposal
review/reanalysis, offer publication, campaign actions, and settlement results.
The fan routes are `/w/{token}` for wish intake, `/offer` for the current offer,
`/pledges/{id}` for payment status, and `/result` for the campaign outcome.
`/guide` explains the journey; `/styleguide` displays synthetic components.

A maker reviews evidence and resolves or dismisses each open question before
publishing an offer. Publication requires an explicit delivery confirmation.
The default unit price comes from catalog unit cost, not the model, and is
editable by the maker. Published terms are immutable. All monetary values used
for offers and payments come from maker/catalog input and server arithmetic,
never model output. Extracted fan budgets remain analysis evidence only.

Campaign progression is `DRAFT -> OPEN -> AWAITING_CONFIRMATION -> SETTLING`,
then `FUNDED` or `NOT_FUNDED`. An authorized minimum permits early confirmation
from `OPEN` directly to `SETTLING`. Windows are 15 minutes, 2 hours, 24 hours,
or 48 hours; confirmation expires 12 hours after closing. Below-minimum closing,
maker cancellation, or expired confirmation ends in `NOT_FUNDED` and cleanup.
Only enough completed captures produce `FUNDED`. A capture shortfall refunds
completed captures and voids remaining holds; pending compensation remains
visible. Neither interest nor an authorization is a completed payment.

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

`SeedCatalog` loads and validates the illustrator, ceramicist, and textile
scenario options and wishes at startup. The makers and fan comments are synthetic. Costs and
production limits are demo-scale, and the default maximum pledge quantity is
three, available as `definition.defaultMaxQtyPerPledge`. Seed wishes are
IMPORTED stated interest. Stable option and wish keys map to workspace-local
UUIDs through `optionId` and `wishId`, so stored analysis can remap references
without sharing records. Workspace creation also calls `AnalysisRunService.attachSeed` in the same transaction. `StoredSeedAnalysis`
checks the canonical input hash, remaps stored option/wish references to the new
workspace, and persists the precomputed model result as a labeled seed run. It makes no model
call at workspace creation. The inputs are synthetic; the outputs are model
analyses, separate from the frozen synthetic evaluation references.

The maker can paste wishes one per line. Import considers at most 100 lines,
skips blank or overlong text, and respects the workspace's 200-wish limit under
the same lock. Matching imported text is merged into an existing wish with an
additional occurrence count; it does not add fans or payment units. The result
reports added, merged, and skipped counts; new imported wishes are IMPORTED.
Each scenario describes one maker, US shipping, and USD amounts. Shipping
addresses are retained by payment handling, but their country is not validated
against the catalog region.

## Maker review workbench

The proposal and dedicated wish review pages use AG Grid Community 36.3.0 for
local filtering, sorting, and selection. Server-rendered forms remain available
when JavaScript or the CDN is unavailable. Bulk changes are validated on the
server and retain the original analysis. Displayed quotes must match the source
text literally; otherwise evidence is unavailable. The Charts Community 14.3.0
asset is available for page-specific use; the workbench does not require a chart.
See [data tools](data-tools.md) for exact assets, scope, and behavior.

## Follow-up questions

For the proposed option, the maker can draft and approve up to three
multiple-choice questions for visible wishes classified as Depends. Drafts
must map a recorded hard or unknown condition to a supported catalog variant;
choices must be exact allowed variants. The maker can edit the question text
and choices before approval, subject to the same validation. Only approved
questions are shown for the relevant wish. Answers are stored with the run,
option, question, wish, and choice.

When approved answers cover all recorded non-preference conditions and their
source evidence is grounded, the review service can record a Compatible
revision with source `FAN_ANSWER` and an answer reference. It does not rewrite
the original analysis. Unsupported, unrelated, or uncovered conditions remain
Depends; a maker's existing decision is not silently replaced. This association
is between an answer and a wish, not a pledge-to-wish link.

Drafting uses at most one model request per attempt, no retry, a 15-second
budget, and at most 40 candidate wishes. It has a process-local daily cap of
the smaller of 20 calls and `LLM_DAILY_RUN_CAP`. A completed or failed attempt
is retained per analysis run; missing configuration and cap rejection are not
recorded as attempts. No model call is made when no supported candidates exist.
Tests use fakes; these limits do not establish live model quality or performance.

## Wish analysis

`DefaultWishAnalyzer` implements `WishAnalyzer` using two OpenAI Responses stages.
Classification divides the snapshot into batches of four wishes, each compared
against every option. Up to ten calls run concurrently across the singleton
analyzer, including simultaneous workspace runs. Two readings are compared;
disagreements require review. If the second reading is incomplete, the validated
first reading remains available and affected wishes are flagged for review.
Each batch uses a strict schema
and passes the existing validator before aggregation. Code merges the normalized
results and computes counts with `optionWishCounts`.

The proposal stage receives the options, complete computed counts, and at most
nine evidence samples per option (three per verdict). Sampling favors distinct
need attributes and hard conditions. Each sample carries at most three validated
needs, with values capped at 120 characters and quotes at 200. The model chooses
at most one proposal and supplies rationale, allowed variants, maker questions,
not-served groups, and up to two alternatives. It cannot change classifications
or counts. The combined output passes the same validator again. A null proposal
is valid. Wishes and option strings are untrusted data, never instructions.

### Validation

Validation applies these rules:

- V1 drops unknown wish and option references and records a flag.
- V2 drops ungrounded needs and rationale; quotes must be literal substrings
  after Unicode whitespace normalization.
- V3 discards model commercial terms. Quoted fan price limits are evidence in
  exact minor units, never offer prices or production decisions.
- V4 drops variants outside the selected option's allowed values.
- V5 rejects multiple proposals.
- V6 rebuilds not-served groups from all incompatible verdicts, including wishes
  absent from the bounded proposal sample. A V6 flag can therefore be expected.
- `MODEL_WRITTEN_NUMBER` drops a rationale item containing digits or English
  number words. Exact numeric product phrases from the selected option's name
  or attributes remain allowed, including `A6` and `5 designs`; a bare numeric
  attribute never permits an audience count. Source quotes remain unchanged.
  The proposal prompt also forbids counts, totals, percentages, prices, and dates
  in rationale and reason sentences. Displayed counts come from code.

The validator requires a complete, unique wish roster within each successful
batch and one verdict for every analyzed request/option pair. Quote validation
proves textual grounding, not semantic correctness. Classifications and normalized
limits still need review. The shared
`analysisInputHash` identifies the full input snapshot. Success exposes the
provider model, completion time, validated output and flags for immutable
persistence. Analysis never changes campaign or payment state.

### Budgets and configuration

`LLM_API_KEY` is optional at startup; an absent key returns `NOT_CONFIGURED`.
Measured defaults are `LLM_MODEL=gpt-6.1-sol`, `LLM_REASONING_EFFORT=low`,
`LLM_BATCH_SIZE=4`, and `LLM_CONCURRENCY=10`. Batch size accepts 1-10 and concurrency
accepts 1-16. Requests use low text verbosity and output limits of 4,096 tokens
for classification and 2,048 for proposal, including reasoning tokens. Model and
effort compatibility must be checked when overriding configuration.

With batches of four and ten concurrent calls, two complete 40-wish,
three-option seed runs took 28.704 and 22.117 seconds. An additional attempt
returned partial analysis and was rejected by the stored-seed completeness gate.
These are live sample measurements, not a latency guarantee. Token counts and per-call latency
are available with `--logging.level.app.analysis=DEBUG`; logs contain no prompts,
model output, or credentials.

Every classification batch gets at most one retry, including provider failures.
The proposal stage retries invalid JSON or rejected output once; transport failures
return immediately. An exhausted classification batch contributes no wishes or
verdicts. Each omitted wish gets an `UNANALYZED_WISH` flag with path
`wishes[UUID]`; screens can count these flags and identify the missing wishes.
Successful batches alone supply the proposal stage's evidence and computed
counts, and its output is validated against that analyzed subset. The input hash
still covers the full original snapshot. If every batch fails, no proposal is
attempted and the run fails. Proposal failure and deadline expiry still fail the
run; the caller retains its previous valid run on failure.
A shared monotonic deadline covers queueing, both stages,
retries and validation: 60 seconds total, at most 20 seconds per provider call,
and a 10-second connection timeout. Deadline expiry cancels outstanding calls.
Inputs allow at most 200 wishes of 500 characters each; both stage envelopes are
limited to 160,000 characters. The 200-wish limit does not guarantee completion
within the budget under all provider conditions.

`LLM_DAILY_RUN_CAP` counts provider calls, including retries, across workspaces
and defaults to 100 (zero disables calls). A 40-wish run normally uses 11 calls;
200 wishes use 51. The synchronized cap resets at UTC midnight and on process
restart. Multiple processes need shared quota persistence before scaling.
`AnalysisRunService` separately persists a ten-attempt quota per workspace.
Configured failures consume it; missing-key and provider-cap rejections do not.
Only a complete matching input snapshot is persisted on success. Reads prefer
a live run over the seed, retain validation flags, and report new visible wishes
absent from that snapshot. A failure does not replace the previous valid run.

### Stored artifact generation

For a stored seed run, build the executable JAR, inject credentials, set
`LLM_SEED_RUN=true` and `LLM_SEED_OUTPUT_FILE`, and run with
`--spring.main.web-application-type=none`. By default the generator reads the
canonical classpath seed, using the zero UUID as its stable workspace namespace.
It stores `AnalysisResult.Success` plus `seedWorkspaceId`, `optionKeys`, and
`wishKeys` maps from UUID strings to stable seed keys, so consumers can remap the
fixture into a new workspace. Model name and call completion time accompany the
output. The generator refuses an existing output path or an incomplete wish
roster and writes only validated results. To analyze explicit snapshots instead,
provide both `LLM_SEED_OPTIONS_FILE` and `LLM_SEED_WISHES_FILE` as JSON arrays of
`Option` and `Wish`; this mode stores the original success shape. Tests use fake
providers only and never call the live API.

API contracts: [Responses](https://developers.openai.com/api/reference/resources/responses/methods/create),
[reasoning controls](https://developers.openai.com/api/docs/guides/reasoning),
[model effort support](https://developers.openai.com/api/docs/models/gpt-6.1-sol),
and [structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs).

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
payments; `CampaignReconciliationTick` also runs every minute. Settlement
requires enough completed captures; later refunds or attention flags can require
review before production even though the `FUNDED` outcome remains historical. A late completed capture on an unsuccessful
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

## PayPal client

The client uses these sandbox endpoints:

| Purpose | Endpoint |
|---|---|
| OAuth token | `POST /v1/oauth2/token` |
| Create authorization-intent order | `POST /v2/checkout/orders` |
| Authorize approved order | `POST /v2/checkout/orders/{id}/authorize` |
| Capture authorization | `POST /v2/payments/authorizations/{id}/capture` |
| Void authorization | `POST /v2/payments/authorizations/{id}/void` |
| Refund capture | `POST /v2/payments/captures/{id}/refund` |
| Read authorization | `GET /v2/payments/authorizations/{id}` |
| Read capture | `GET /v2/payments/captures/{id}` |
| Read refund | `GET /v2/payments/refunds/{id}` |

`POST /webhooks/paypal` verifies the original event with the sandbox signature
verification API before persisting or acting on it. A configured
`PAYPAL_WEBHOOK_ID` and sandbox REST credentials are required. Verified event IDs
are durably deduplicated; supported events trigger reconciliation against current
provider resources rather than assigning state from the event. Polling on reads
and the minute tick remains in place. See the [webhook contract](../src/main/kotlin/app/paypal/README.md)
for events, responses, database requirements, and registration. Source support
does not establish deployment or registration.

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
| Capture authorization; read capture | `COMPLETED`, `DECLINED`, `PARTIALLY_REFUNDED`, `PENDING`, `REFUNDED`, `FAILED` | None |
| Refund capture; read refund | `CANCELLED`, `FAILED`, `PENDING`, `COMPLETED` | `CANCELLED` to `FAILED` |
| Void authorization | Empty 204, or 200 authorization representation | Empty response to `VOIDED`; representation uses authorization mappings |

Other status names return `INVALID_RESPONSE`, including names valid only for
another resource. Documented resource states return `PaymentResult.Received`;
that does not imply the requested operation completed. In particular, a created
order requiring payer action succeeds before buyer approval. The existing port
does not represent partial captures: `CAPTURED` on an authorization indicates
capture evidence, never completed full payment. A partially refunded capture
retains `PARTIALLY_REFUNDED` and remains unresolved, not a full refund or fully
available payment. Callers must reconcile the capture/refund resources before counting money. Authorization
expiry remains available through the expiry field and `AUTHORIZATION_EXPIRED`
error mapping; `EXPIRED` is not a documented authorization response status.

## Configuration

Inject settings through the process environment. [.env.example](../.env.example)
is a key inventory, not a defaults file to source. Blank overrides can be invalid.
Secrets must never be tracked or embedded in JDBC URLs: a connection failure can
print the URL. See [running locally](running.md) for Gradle and Compose commands.

| Key | Purpose | Default |
|---|---|---|
| `SERVER_PORT` | HTTP port | `8200` |
| `DATABASE_URL` | JDBC URL without credentials | In-memory H2 |
| `DB_USERNAME`, `DB_PASSWORD` | Database credentials | `sa` / empty for H2 |
| `PRODUCT_NAME` | Rendered display name | `Wishbatch` |
| `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET` | Sandbox REST credentials | None; calls fail with `NOT_CONFIGURED` |
| `PAYPAL_BASE_URL` | Only accepted API base URL | `https://api-m.sandbox.paypal.com` |
| `PAYPAL_WEBHOOK_ID` | Registered sandbox webhook ID for signature verification | None; webhook endpoint returns 503 |
| `LLM_API_KEY` | Live analysis credential | None; live runs return `NOT_CONFIGURED` |
| `LLM_MODEL` | Analysis model | `gpt-6.1-sol` |
| `LLM_REASONING_EFFORT` | Provider reasoning effort | `low` |
| `LLM_BATCH_SIZE` | Wishes per classification batch, 1-10 | `4` |
| `LLM_CONCURRENCY` | Shared classification concurrency, 1-16 | `10` |
| `LLM_DAILY_RUN_CAP` | Process-local daily provider-call limit, including retries | `100`; zero disables calls |
| `LLM_SEED_RUN` | Enable artifact generation with the live provider | Disabled |
| `LLM_SEED_OUTPUT_FILE` | New output path for the generator | Required when generating |
| `LLM_SEED_OPTIONS_FILE`, `LLM_SEED_WISHES_FILE` | Explicit snapshot inputs, supplied together | Canonical seed if both absent |

`APP_BASE_URL` and `DB_SCHEMA` appear in the example and Compose
configuration but are not consumed by the application. Current web
links are relative. Compose forwards the main payment/model settings, but not
the reasoning, batch, concurrency, or seed-generator settings.

`app.campaign.demo-simulation-enabled` is an optional Spring property, false by
default. It enables the explicitly labeled capture-failure simulation described
above; it never overrides a received provider response.

## Local runs and worktrees

Without `DATABASE_URL` the application starts on an in-memory database, so a
fresh clone or a new worktree builds, tests, and runs with no services. The
build defaults tests to isolated in-memory H2 even when `DATABASE_URL` is set.
Restart tests use temporary file-backed H2, and schema tests may use a disposable
local PostgreSQL cluster; tests do not share application data.
`tools/worktree.sh add` assigns each worktree its own port from 8201 to 8209;
pass it as `SERVER_PORT` when running the application there.

H2 and PostgreSQL run the compatibility upgrade before JPA initialization;
Hibernate defaults to `update` and halts on schema errors. See
[startup schema upgrades](schema-upgrades.md) for ordering, supported changes,
and limitations. Database locks and independent payment checkpoints require at
least two connections; enabled webhook processing requires at least three.

For settlement invariants and decision records, see [reliability](reliability.md)
and [provenance](provenance.md). For evidence and limitations, see [evaluation](evaluation.md) and the
[README](../README.md#evidence-and-limits). Stubbed payment tests do not establish
live sandbox transaction success.
