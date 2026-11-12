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
captures count toward the paid minimum. A shortfall causes completed captures
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

The offline [sequence test](../src/test/kotlin/app/campaign/InvariantSequenceTest.kt)
uses a fake provider and clock for 16 deterministic seeds of 64 service actions
each. It checks counts, duplicate provider effects, idempotency, terminal and
unresolved states, including interruption and recovery scenarios. This is
bounded test coverage, not proof for arbitrary sequences or actual provider
behavior. Manual sandbox checks and hosted checks are separate evidence; see
the [README](../README.md#evidence-and-limits).
