# Offline scenario evaluation

Run from the repository root, without credentials or network access:

```sh
python3 -m unittest discover -s evaluation -p 'test_*.py'
python3 evaluation/scenario_evaluate.py --output /tmp/scenario-results.json
python3 evaluation/export_scenario.py ceramicist /tmp/ceramicist-snapshot
```

The default report checks alignment of the three stored synthetic references,
forty wishes each. It is not a model run or an accuracy benchmark. Reference
labels are extracted from those same machine-written stored outputs, without
independent human adjudication. Unknown reasonable-disagreement annotations,
language labels, raw readings, maker decisions, latency, tokens and cost remain
null. No manual-sorting comparison has been measured.

`scenarios/manifest.json` freezes source and reference hashes, exact UUID-to-key
maps, input hashes, counts and reviewed subset keys. Wish UUIDs repeat between
scenarios; joins are always scoped by scenario. Source changes fail alignment
rather than silently updating the reference. The injection and non-English
example subsets are reported separately. Their membership uses reviewed exact
keys, not automatic script detection or inferred language metadata. Duplicate
texts retain their distinct wish IDs.

## Scoring supplied artifacts

```sh
python3 evaluation/scenario_evaluate.py --scenario ceramicist \
  --runs /tmp/ceramicist-run-1.json /tmp/ceramicist-run-2.json \
  --output /tmp/ceramicist-results.json
```

An artifact uses the existing `AnalysisResult.Success` JSON envelope:
`model`, `inputHash`, `completedAt`, and `validated: {output, flags}`. Its
input hash must match the selected scenario. Optional `wishKeys` and
`optionKeys` must exactly match the frozen maps when supplied. Explicit snapshot
exports from the existing generator omit those maps; the evaluator resolves
UUIDs against the selected manifest. The offline exporter emits `Option` and
`Wish` arrays for that generator's existing explicit snapshot interface. A
Kotlin integration test verifies deserialization and application input hashes.
The exporter refuses an existing destination. It never calls a model.

The layers are scored separately:

- `validatedModelOutput`: the stored, validated model output before maker review.
- `firstModelReadingLabels` and `secondModelReadingLabels`: reconstructed kinds
  and verdicts from `output.secondReading.comparisons`. These are retained
  normalized reading labels, not raw provider JSON or unvalidated completions.
  Raw provider payloads are unavailable. Summary counters, complete per-option
  comparisons and consistency with the merged output are checked. Disagreement
  requires a conditional merged verdict. Missing reading evidence stays null.
- `makerEffectiveOutput`: a separately exported `effectiveOutput`, only with
  explicit `makerOverrides`. Override rows contain `wishId`, `optionId`,
  `verdict`, `source`, `revision` and `reason`, scoped to this artifact's run.
  Only `MAKER` decisions are allowed. The evaluator checks effective kinds and
  verdicts against the highest revision per pair, including conversion of a
  non-request to a request with conditional unreviewed options. Empty history
  is an explicit observation; absent history means unknown. Original reading
  labels and model scores are never rewritten by maker decisions. Other
  effective-output fields are not validated by this label evaluator.

Every option reports a confusion matrix and precision/recall for Compatible,
Depends (`CONDITIONAL`), Not served (`INCOMPATIBLE`), and Not a request. Missing
predictions occupy a separate column and count against reference recall.
Reports include exact disagreements and coverage. Partial outputs retain the
full expected denominator. Between-reading and pairwise between-run agreement
show the compared denominator and missing keys; shared absence is never counted
as agreement. Byte-identical artifacts cannot represent separate runs. Agreement
is consistency, not correctness, independence or calibrated confidence.

An optional sibling `*.telemetry.json` must bind to the analysis file's SHA-256
in `analysisSha256`. Only supplied latency, tokens, cost and usage information
are copied. Missing fields stay null; model names, completion timestamps and
stored examples do not imply real telemetry. Cost is never inferred from seed
outputs. Synthetic stored references cannot carry live telemetry. Supplied
artifacts and sidecars still require independent provenance review before
publication as live measurements.

## Historical evaluation

```sh
python3 evaluation/evaluate.py evaluation/runs/live-1.json \
  evaluation/runs/live-2.json evaluation/runs/live-3.json \
  --output /tmp/historical-results.json
```

`historical/baseline.json` identifies the former illustrator snapshot and its
source revision. The exact original option and wish files are frozen alongside
it. The historical scorer checks those hashes instead of today's scenario seed.
Existing references, runs, telemetry and results are preserved unchanged. Their
measurements do not describe the current scenarios or two-reading analyzer.

No live run is part of this offline workflow. A later execution needs an exact
serialized request budget, verified official rates and a hard total-dollar
bound covering all inputs, output limits and retries before any call. A provider
call cap alone is not a dollar guarantee; exhausted budget permits no retry.
