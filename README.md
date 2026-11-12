# Wishbatch

[Demo video (2 min 51 sec)](https://youtu.be/pqCL7ZykxYA) ·
[Try the sandbox](https://wishbatch.onrender.com/) ·
[Testing guide](https://wishbatch.onrender.com/guide)

Fans ask for different things; a maker needs one batch they can actually make.
Wishbatch groups free-text wishes into a feasible offer, then checks whether
enough fans complete payment before production can proceed.

```text
Maker options + fan wishes -> AI analysis -> maker-reviewed offer
                                                    |
                  sandbox authorization -> capture / refund / void
```

1. Review the maker's feasible product options.
2. Collect fan wishes, including conditions and preferences.
3. Review classifications, source quotes, and one proposed batch.
4. Confirm the exact specification, price, minimum, and delivery terms.
5. Fans authorize PayPal holds for that offer.
6. Confirm production when the authorized minimum is met; capture payments,
   then proceed only if enough complete. Otherwise request refunds and hold releases;
   PayPal must confirm their completion.

**AI supplies the synthesis; PayPal supplies the financial commitment.** Without
AI, the maker must sort every wish and the product becomes a preorder form.
Without PayPal, compatible wishes remain stated interest with no funded batch.

## Status

A working **sandbox prototype**, with synthetic wishes and precomputed model analysis,
maker/fan pages, offer publication, and payment settlement code. The fastest
way to try it is <https://wishbatch.onrender.com/>: choose **Start a demo** or
read the [testing guide](https://wishbatch.onrender.com/guide). Browsing needs
no account or credentials; the payment step needs a PayPal sandbox personal
account. The free instance sleeps after 15 minutes without traffic, and the
first request can then take about a minute. [Free-plan details and keep-warm
workflow](docs/deploying.md#free-plan-limits). To run locally, use
`./gradlew bootRun` and open <http://localhost:8200/>.
[Full setup and Compose](docs/running.md).

The product hypothesis is unvalidated: no customer demand, production, delivery,
or advantage over manual sorting has been established. Each workspace has one
maker and one active campaign, with a US/USD catalog. Workspace links also
grant access to maker actions; use disposable synthetic data.

## Getting started

Install JDK 21 and run from the repository root:

```sh
./gradlew bootRun
```

The default database is in memory and disappears on shutdown. A new workspace
offers distinct illustrator, ceramicist, and textile catalogs with synthetic
wishes and labeled precomputed model analyses. You can
inspect evidence, add or hide wishes, and publish an offer
without credentials. Reanalysis needs `LLM_API_KEY`; sandbox checkout needs
`PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET` from a sandbox REST application,
plus a sandbox buyer account for approval. Keep secret values outside Git.

For PostgreSQL, use the [Compose path](docs/running.md#docker-compose-and-postgresql).
See [startup schema upgrades](docs/schema-upgrades.md) and the
[configuration reference](docs/architecture.md#configuration) for defaults and
which advertised keys are currently unused. Do not source the blank example
file as configuration.

## How AI is used

AI has one job: interpret unstructured wishes against maker-approved options
and suggest one feasible batch for the maker to review. The default model is
`gpt-6.1-sol` with low reasoning effort.

First, batches of wishes are classified against every option as Compatible,
Depends, or Not served; praise and instructions are Not a request. Code computes
counts. Two readings are compared; disagreements need review, and an incomplete
second reading retains the validated first reading and marks it for attention.
Second, the model receives those counts and bounded evidence samples
and selects at most one proposal, with quotes, variants, unresolved questions,
and alternatives. It cannot revise the first stage's classifications or counts.

Validation requires complete, unique coverage; removes unknown references and
quotes absent from the source text; discards invented commercial terms and
unsupported variants; rejects multiple proposals; and rebuilds unserved groups
from the verdicts. Quote checks establish textual grounding, not correct
interpretation. Failed runs preserve the last valid result. The maker can review
wishes in a filterable table and record corrections without rewriting the
original analysis. Literal quotes that cannot be matched remain unavailable.
For supported conditions, the maker can approve follow-up questions and fans
can answer against their wishes; unresolved conditions remain Depends. Draft
replies appear beside wishes only after maker approval, which sends no messages.
Machine glosses are reading aids, not evidence or verdicts; reviewed offer copy
needs separate confirmation of its claims and terms. See
[data tools](docs/data-tools.md) and [follow-up questions](docs/architecture.md#follow-up-questions).

**No offer price, cost, payment amount, or funding threshold comes from the
model.** The maker sets commercial terms; server code computes money in integer
minor units and counts from records. Model-extracted fan budgets are quoted
analysis evidence only, never a price or charge. The interest count uses the
offered price: a price-only condition can resolve when its quoted limit covers
that price, while other unresolved conditions stay conditional. Decimal amounts
are converted exactly to minor units. Analysis has bounded retries,
a 120-second overall deadline with a 100-second classification cutoff and
20 seconds reserved for synthesis, a daily provider-call cap, and a ten-attempt
workspace quota. [Analysis implementation](docs/architecture.md#wish-analysis).

## How PayPal is used

Checkout creates an `AUTHORIZE` order and, after buyer approval, an authorization
hold. Reaching the authorized minimum permits maker confirmation; it does not
mean payment has completed. Confirmation starts full captures. Only completed
captures count toward the paid minimum and a `FUNDED` result.

| Campaign state | Trigger and next result |
|---|---|
| `DRAFT` | Publish immutable terms to enter `OPEN` |
| `OPEN` | Minimum holds + maker confirmation enter `SETTLING`; closing with enough holds enters `AWAITING_CONFIRMATION` |
| `AWAITING_CONFIRMATION` | Maker confirmation within 12 hours of close enters `SETTLING` |
| `SETTLING` | Enough completed capture units enter `FUNDED`; a shortfall enters `NOT_FUNDED` |
| `NOT_FUNDED` | Below-minimum close, cancellation, expired confirmation, or capture shortfall; request refunds and releases of remaining holds |
| `FUNDED` | The paid minimum was met at settlement; review current payments and attention flags before proceeding |

Pending captures, voids, and refunds remain visible and are reconciled on reads
and a minute tick. Verified sandbox webhooks can also trigger reconciliation;
registration and deployment are separate steps. A transport receipt alone is
not an accepted webhook or proof of payment. See the
[webhook contract](src/main/kotlin/app/paypal/README.md). Stable per-pledge,
per-operation idempotency keys are reused on retries and recovery. The client
rejects every API base URL except
`https://api-m.sandbox.paypal.com` at startup.

The client uses Orders v2 create/authorize and Payments v2 capture/void/refund
and resource reads; [architecture](docs/architecture.md#paypal-client) lists
the endpoints and resource-status mappings. A capture can fail. Hold release
and refund availability depend on PayPal and the bank or card. The product
never holds funds or guarantees production or delivery. The maker can run a
[books check](docs/reliability.md#books-check) to compare recorded pledges with
sandbox resources and inspect dated results. A fan money trail shows recorded
checkpoints; a wish is associated with a pledge only when explicitly linked.
Record verification checks snapshot integrity, not semantic truth or signatures;
the tamper drill changes only a copy.

## Evidence and limits

The offline suite covers domain transitions and money rules, persistence,
analysis validation and quotas, PayPal HTTP mappings/retries, reconciliation,
server-rendered pages, and checkout JavaScript. Tests use fakes or stubs and no
provider credentials. On 2026-11-12, a fresh anonymous public clone at
`24423a5` started
without credentials using JDK 21 and `./gradlew bootRun`; a new workspace
displayed the stored postcard proposal. A Compose run at that historical
baseline also created a PostgreSQL workspace with the stored proposal.

A hosted manual check of revision `e17d492` exercised all three populated demos,
one $48 sandbox authorization and completed capture, record verification, the
recorded books check with matching before and after observations, and a tamper
test on a copy. A signed `CAPTURE.COMPLETED` webhook was accepted by the hosted
handler. An explicitly linked wish added after analysis but before publication
had an unavailable analysis verdict. These observations do not establish that a
later revision is deployed or verified.

One hosted 40-wish live analysis completed in 57.41 seconds with a complete
second reading and 40/40 paired agreement. Usage was only partially recorded;
a Timeout category from an earlier request remained in the metrics. This is an
execution observation, not a quality score or a latency guarantee. Grid and Charts interactions were checked; the
browser fallback with the CDN unavailable was not verified in that check.

The offline deterministic settlement sequence test exercises 16 seeds with 64
actions each using a fake gateway; it is not a provider or hosted test. Earlier
missing evidence remains unavailable. See [reliability](docs/reliability.md)
and [provenance](docs/provenance.md).

The three current precomputed analyses took 42.58, 36.00, and 37.66 seconds;
their paired readings agreed on 40/40, 40/40, and 39/40 wishes, respectively.
These are execution and consistency observations on synthetic inputs, not
independent quality scores. [Historical evaluation](docs/evaluation.md) and
the frozen scenario references are separate synthetic, machine-authored
self-alignment evidence, not human adjudication or a held-out benchmark.
**The manual-sorting comparison is not measured.**

The strongest objection is that a maker could sort a few dozen wishes by hand
and publish a conventional preorder. These measurements do not show that AI
saves time or improves the maker's decision, and machine-authored labels can
share the model's biases. Whether the wish-to-offer step adds value, and whether
fans will commit to the resulting offer, still require human comparison and
customer testing.

## Development

```sh
git config core.hooksPath .githooks
tools/verify.sh
python3 -m unittest discover -s evaluation -p 'test_*.py'
node --test src/test/js/pledge.test.cjs
```

Repository checks require Bash, Perl, Python 3, Git, and JDK 21; the separate
checkout JavaScript tests need Node.js. `tools/verify.sh` runs repository guards,
guard tests, and Gradle checks; it does not run the separate evaluation or
JavaScript suites. Initial builds download dependencies; tests make no model
or payment API calls.

See the [documentation index](docs/README.md), [architecture](docs/architecture.md),
[running guide](docs/running.md), [reliability](docs/reliability.md), [provenance](docs/provenance.md),
and [design system](docs/design.md).

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) and the
[Git workflow](docs/contributing/git-workflow.md). Automated contributors also
follow [AGENTS.md](AGENTS.md).

## License

[MIT](LICENSE).
