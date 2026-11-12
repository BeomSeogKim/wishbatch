# Wishbatch

Fans ask for different things; a maker needs one batch they can actually make.
Wishbatch groups free-text wishes into a feasible offer, then checks whether
enough fans complete payment before production can proceed.

1. Review the maker's feasible product options.
2. Collect fan wishes, including conditions and preferences.
3. Review AI classifications, source quotes, and one proposed batch.
4. Confirm the exact specification, price, minimum, and delivery terms.
5. Fans authorize PayPal holds for that offer.
6. Confirm production when the authorized minimum is met; capture payments,
   then proceed only if enough complete. Otherwise refund payments and void holds.

**AI supplies the synthesis; PayPal supplies the financial commitment.** Without
AI, the maker must sort every wish and the product becomes a preorder form.
Without PayPal, compatible wishes remain stated interest with no funded batch.

## Status

A working **sandbox prototype**, with synthetic seed data, stored analysis,
maker/fan pages, offer publication, and payment settlement code. No hosted build
is documented. Try it locally with `./gradlew bootRun`, then open
<http://localhost:8200/> and choose **Start a demo**. No key is needed except for
live analysis and PayPal payments. [Full setup and Compose](docs/running.md).

The product hypothesis is unvalidated: no customer demand, production, delivery,
or advantage over manual sorting has been established. The demo has one maker,
one active campaign per workspace, and a US/USD catalog. Workspace links also
grant access to maker actions; use disposable synthetic data.

## Getting started

Install JDK 21 and run from the repository root:

```sh
./gradlew bootRun
```

The default database is in memory and disappears on shutdown. A new workspace
contains three feasible options, forty synthetic wishes, and a labeled stored
analysis. You can inspect evidence, add or hide wishes, and publish an offer
without credentials. Reanalysis needs `LLM_API_KEY`; sandbox checkout needs
`PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET` from a sandbox REST application,
plus a sandbox buyer account for approval. Keep secret values outside Git.

For PostgreSQL, use the [Compose path](docs/running.md#docker-compose-and-postgresql),
including its explicit schema initialization setting. See the
[configuration reference](docs/architecture.md#configuration) for defaults and
which advertised keys are currently unused. Do not source the blank example
file as configuration.

## How AI is used

AI has one job: interpret unstructured wishes against maker-approved options
and suggest one feasible batch for the maker to review. The default model is
`gpt-6.1-sol` with low reasoning effort.

First, batches of wishes are classified against every option as Compatible,
Depends, or Not served; praise and instructions are Not a request. Code computes
counts. Second, the model receives those counts and bounded evidence samples
and selects at most one proposal, with quotes, variants, unresolved questions,
and alternatives. It cannot revise the first stage's classifications or counts.

Validation requires complete, unique coverage; removes unknown references and
quotes absent from the source text; discards invented commercial terms and
unsupported variants; rejects multiple proposals; and rebuilds unserved groups
from the verdicts. Quote checks establish textual grounding, not correct
interpretation. Failed runs preserve the last valid result.

**No offer price, cost, payment amount, or funding threshold comes from the
model.** The maker sets commercial terms; server code computes money in integer
minor units and counts from records. Model-extracted fan budgets are quoted
analysis evidence only, never a price or charge. Analysis has bounded retries,
a 60-second deadline, a daily provider-call cap, and a ten-attempt workspace
quota. [Analysis implementation](docs/architecture.md#wish-analysis).

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
| `NOT_FUNDED` | Below-minimum close, cancellation, expired confirmation, or capture shortfall; refund completed captures and void remaining holds |
| `FUNDED` | Paid minimum met; production may proceed under the maker's terms |

Pending captures, voids, and refunds remain visible and are reconciled on reads
and a minute tick. Stable per-pledge, per-operation idempotency keys are reused
on retries and recovery. The client rejects every API base URL except
`https://api-m.sandbox.paypal.com` at startup.

The client uses Orders v2 create/authorize and Payments v2 capture/void/refund
and resource reads; [architecture](docs/architecture.md#paypal-client) lists
the endpoints and resource-status mappings. A capture can fail. Hold release
and refund availability depend on PayPal and the bank or card. The product
never holds funds or guarantees production or delivery.

## Evidence and limits

The offline suite covers domain transitions and money rules, persistence,
analysis validation and quotas, PayPal HTTP mappings/retries, reconciliation,
server-rendered pages, and checkout JavaScript. Tests use fakes or stubs and no
provider credentials. Local HTTP checks cover workspace creation, stored
proposal review, offer publication, and missing-key behavior.

On 2026-11-12, six browser paths were run by hand against the real PayPal sandbox
on a local build, and all passed: the golden path, two buyers, cancel with void,
the simulated capture failure with refund, a live re-analysis, and the edge
states. This exercised sandbox authorization, capture, void, and refund. The
golden path took about three and a half minutes. The last golden run's provider
IDs were order `3Y176650HC8669634`, authorization `6YR87310UT316712E`, and capture
`3M26718937950980C`.

This was a manual run, not an automated test in the repository. It was not
repeated on a hosted deployment. Webhooks are not implemented.

[Evaluation](docs/evaluation.md) scores forty synthetic wishes (35 requests,
three options, 105 correlated request/option pairs), using one stored run and
three fresh runs. All choose postcards. Proposed-option Compatible precision
is 16/16, 16/16, 15/15, and 15/15; recall is 16/16, 16/16, 15/16, and 15/16
(93.75-100%). Retained exact quotes are 77/77, 79/79, 79/79, and 78/78.
The reference labels were written by a language model, not human adjudicators;
this is a small product-designed set, not a held-out benchmark. Fresh analysis
times were 23.213, 25.172, and 27.212 seconds, excluding startup and maker review.
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
[running guide](docs/running.md), and [design system](docs/design.md).

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) and the
[Git workflow](docs/contributing/git-workflow.md). Automated contributors also
follow [AGENTS.md](AGENTS.md).

## License

[MIT](LICENSE).
