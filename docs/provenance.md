# Decision records

The decision-record service stores immutable analysis and offer source snapshots
at analysis persistence and publication, and appends payment checkpoints to a
pledge's hash chain. A snapshot includes the analyzed wish texts and verdicts or published offer terms;
canonical JSON and SHA-256 hashes support integrity checks. Repeating an
unchanged checkpoint adds no entry. A conflicting replacement of a source is
rejected. Reads compare recorded chains and available sources with current
records and report changes. These hashes are not signatures or independent
proof against an actor able to rewrite the database and hashes.

Payment truth is committed before its separate provenance checkpoint. A failed
checkpoint records a failure event rather than rolling back the payment state.
The read-only maker page at `/w/{token}/maker/decision-record` and
JSON download at `/w/{token}/maker/decision-record.json` do not create a
baseline; pledges without a recorded lifecycle are `UNAVAILABLE`. The JSON
response uses `no-store`. Existing data cannot acquire historical evidence
merely by being read.

A pledge can carry an explicit wish reference from the fan's wish page or the
last wish submitted in that workspace's browser session. The server rejects a
wish from another workspace before creating a payment order. The nullable
`pledges.wish_id` column is added by the startup schema upgrade. Existing
pledges remain unlinked; no association is inferred from the offer's evidence.

For a linked pledge, the chain records the exact wish text and its hash, the
offered option's stored verdict and analysis identifiers, any recorded second
reading and review, the immutable offer version and terms hash, and successive
payment references. A wish added after the offered analysis has no verdict in
that run; this remains unavailable. An unlinked pledge traces to the offer's
recorded evidence set. Neither payer identity nor shipping details are included.
Follow-up review snapshots preserve the override revision and the supporting
questions and answers without rewriting the original analysis.

The fan trail at `/w/{token}/pledges/{id}/trace` displays those stored snapshots.
Payment checkpoint times record observation by this app, not PayPal settlement
time; older checkpoints without a recorded time remain unavailable. The maker's
paid batch and payment ledgers link to the same trail.

The maker's **Verify this record** action recomputes each stored chain and
reports the first broken entry. The reliability drill changes only an in-memory
copy of an entry and uses the same verifier to demonstrate detection. These
actions do not alter the real record or call PayPal. An intact chain checks
consistency of what was recorded, not whether the interpretation was correct,
the link authenticates the person who wrote a wish, or delivery will occur.
Workspace links grant access without separate maker/fan authentication; use
synthetic data and treat the exported record and trace links as sensitive.
