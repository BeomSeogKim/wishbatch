# Payment reliability

Wishbatch uses the PayPal sandbox. Stated wishes, authorized units, and
completed paid units are separate counts. An authorization is a hold, not a
completed payment. Server code computes amounts in integer minor units and
serializes capacity checks.

```text
OPEN -> SETTLING -> FUNDED       enough completed captures
  |         \----> NOT_FUNDED   capture shortfall; refund and void
  -> AWAITING_CONFIRMATION -> SETTLING or NOT_FUNDED
  -> NOT_FUNDED                 close below minimum or cancellation
```

A maker may confirm after the authorized minimum is met. Only completed
captures count toward the paid minimum at settlement. `FUNDED` remains a
historical outcome if later refunds reduce paid units; review current payments
and attention flags before proceeding. A shortfall causes completed captures
to be refunded and remaining holds to be voided; pending or failed compensation
remains visible and needs reconciliation. The application cannot guarantee
provider or bank processing, production, or delivery.

Payment intents, responses, and checkpoints are persisted independently of the
coordinating transaction. Retries reuse stable per-pledge, per-operation
idempotency keys. Provider resource reads resolve ambiguous results; pending
captures are not counted as paid. A partial refund remains a distinct unresolved
state requiring attention, not a completed full refund. Reads and a minute
tick reconcile outstanding work. A verified sandbox webhook can trigger the same reconciliation, but its
payload does not set payment state. See the [webhook contract](../src/main/kotlin/app/paypal/README.md).

## Books check

The maker can run a books check at `/w/{token}/maker/books` for a campaign.
It reads available sandbox order, authorization, capture, and refund resources,
runs normal campaign reconciliation, and records dated before-and-after
observations. Reconciliation can request refunds or release holds on an
unsuccessful campaign. History reads the stored observations without provider
calls. The observations are sequential, not an atomic provider snapshot.

Each pledge is classified separately:

- `MATCH`: available evidence and statuses agree with the local state.
- `MISMATCH`: evidence or statuses disagree with that state.
- `UNKNOWN`: required resources or their status, amount, or currency are missing,
  including provider read failures.

A match can describe a pending payment or partial refund; it does not establish
that the batch is fully paid. Unknown balances are not automatically refunded.
The ledger uses AG Grid Community for filtering, sorting, pagination, and CSV
export when its script loads. A server-rendered table remains available without
it; CSV export requires the grid. See [data tools](data-tools.md) for the asset
and [architecture](architecture.md#paypal-client) for the sandbox contract.

The offline [sequence test](../src/test/kotlin/app/campaign/InvariantSequenceTest.kt)
uses a fake provider and clock for 16 deterministic seeds of 64 service actions
each. It checks counts, duplicate provider effects, idempotency, terminal and
unresolved states, including interruption and recovery scenarios. This is
bounded test coverage, not proof for arbitrary sequences or actual provider
behavior. Manual sandbox checks and hosted checks are separate evidence; see
the [README](../README.md#evidence-and-limits).
