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

A record does not infer a pledge's wish from an offer's supporting wishes. Its wish association and individual verdict are
explicitly unavailable. An intact record checks consistency of what was
recorded, not whether the analysis was correct, a fan made a particular wish,
or the provider completed a payment. The workspace token grants maker access;
use synthetic data and treat the exported record as sensitive.
