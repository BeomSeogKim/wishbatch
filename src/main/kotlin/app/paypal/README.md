# PayPal webhooks

After deploying the webhook handler, register this URL on the sandbox REST
application that creates the orders:

```text
https://wishbatch.onrender.com/webhooks/paypal
```

For another deployment, use its public HTTPS origin and the exact path
`/webhooks/paypal`. Registration and deployment are separate operations; adding
the handler does not register a webhook or update a running deployment.

Subscribe to these [Payments v2 events](https://developer.paypal.com/api/rest/webhooks/event-names/):

| Event | Purpose |
| --- | --- |
| `PAYMENT.AUTHORIZATION.VOIDED` | Refresh an authorization hold |
| `PAYMENT.CAPTURE.COMPLETED` | Refresh capture completion |
| `PAYMENT.CAPTURE.DECLINED` | Refresh a declined capture |
| `PAYMENT.CAPTURE.REFUNDED` | Refresh a refunded capture |

The handler also accepts the legacy `PAYMENT.CAPTURE.DENIED` name as a capture
trigger. It does not require a separate subscription for this Payments v2 app.
Refund events may identify a refund resource; the handler resolves a known
refund ID or a related capture ID, including a sandbox capture `up` link.
It never follows webhook links or assigns payment states from event fields.

Set `PAYPAL_WEBHOOK_ID` to the registered webhook ID. The sandbox app's existing
`PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET` provide OAuth authentication.
Without a webhook ID, the endpoint returns HTTP 503 and does not create the
inbox table; normal checkout and polling remain available.

## Verification and processing

`POST /webhooks/paypal` accepts JSON and verifies the original body with
[`POST /v1/notifications/verify-webhook-signature`](https://developer.paypal.com/api/webhooks/v1/verify-webhook-signature-post).
The request contains the five PayPal transmission headers, the configured
webhook ID, and the complete original event. Only `SUCCESS` permits processing.
Missing or invalid signature metadata returns 401. Verification API unavailability
returns 503. Invalid event structure returns 400. Duplicate deliveries are
verified again before their stored receipt is consulted.

After verification, the event ID, type, resource references, receipt time, and
processing flag are persisted. Payloads, payer details, and signatures are not
stored in the inbox. A primary key deduplicates event IDs across processes;
an inbox row lock serializes processing of the same event. Interrupted or
rejected processing leaves the receipt available for redelivery. Payment
checkpoints remain durable, so redelivery uses existing idempotency rules.

An event is only a trigger for `CampaignService.reconcile(CampaignRef)`. That
service reads current PayPal resources and applies existing payment transitions
and provider-error backoff. Event status and creation order never set local
payment state. Unknown resources and unsupported types are acknowledged without
domain action. HTTP 200 means the trigger was handled, not that a payment or
refund completed; pending provider work continues through polling.

With webhooks enabled, startup additively creates `paypal_webhook_events` if it
does not exist. The database role needs permission to create this table. The
SQL supports the application's H2 and PostgreSQL databases. Webhook processing
holds an inbox connection while campaign reconciliation uses its independent
transactions; configure at least three database connections. Local webhook
processing is serialized to avoid exhausting the pool with waiting callbacks.

Only the exact `POST /webhooks/paypal` route needs a browser-CSRF exemption.
Security headers and the 512 KiB request-body limit still apply. Signature
verification happens before inbox persistence or campaign actions.

Offline tests use HTTP stubs and isolated in-memory databases. Live validation
must use actual sandbox app events: PayPal's simulator events cannot be verified
with this postback API, as explained in the
[webhook integration guide](https://developer.paypal.com/api/rest/webhooks/rest/).
