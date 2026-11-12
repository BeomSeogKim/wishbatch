# Seed analysis evaluation

This evaluates classification agreement on forty synthetic wishes, not whether
AI saves a maker time. One small synthetic seed set proves little. The timed
manual-sorting comparison is **not measured yet**, so no productivity advantage,
human-level accuracy, or customer demand is established.

## Reference construction

[Reference labels](../evaluation/reference-labels.json) cover all forty wishes:
35 requests and five non-requests, with three verdicts for each request (105
request/option pairs). They were authored by a language-model evaluator from
the wish text, option catalog and label definitions before inspecting the stored
or fresh outputs. The same assistant implemented this evaluation; these are
neither human labels nor an independent human adjudication. Shared model biases
remain possible. No reference was changed after seeing output.

The frozen reference SHA-256 is
`65ed3c779e86124ee2d9e267a0ff9ac5b11abe602d2629f64bfbdb875725279e`.
Seed-file hashes are included in the reference, and the runner rejects changed
seed files. This is a recorded output-blind procedure, not externally witnessed
preregistration. The seed itself was designed for the product and its analysis
prompt already exists; this is not a held-out benchmark.

The reference treats a requested format as required unless alternatives are
stated. Soft preferences do not gate a purchase. A required price, unspecified
artwork, unclear size, or required variant choice is `CONDITIONAL` (Depends).
Production cost is not a sale price. Non-requests receive no option verdicts.
Quantity never multiplies the number of interested wishes.

Each label includes a short explanation. `reasonableDisagreement` flags
interpretation-sensitive wishes, including cross-format assumptions, ambiguous
size and artwork, and whether a required satin variant should already count as
served. These flags were also frozen before scoring. They do not remove hard
cases from the headline metrics.

## Measurements

The unit is a wish. Request agreement uses all forty wishes. Compatible precision
is true compatible positives divided by model compatible positives; recall is
true compatible positives divided by reference compatible positives. Both use
the proposed option and include request-detection errors. An undefined denominator
is reported as null, never 100%. Depends and Incompatible are both negative for
this binary metric but remain separate in the three-verdict confusion matrix.

Confusion tables use reference rows and model columns, restricted to wishes
both classify as requests; request confusion and missing-verdict coverage are
reported separately. All runs here agree on all request kinds, so the tables
cover all 35 requests. The JSON also reports every option, the number of distinct
wishes with any difference, and both readings with reasons for each difference.

Quote grounding checks every occurrence of a need quote and proposal rationale
quote in the validated stored output, including repeated occurrences. Quotes
must be nonempty, exact, case-sensitive substrings of the original wish, without
whitespace normalization. This is stricter than the application's whitespace
normalization, but it measures retained quotes only. It cannot recover dropped
provider quotes or establish semantic correctness or a raw-provider V2 pass rate.

## Results

All four runs use the same input hash and select the postcard set. Results and
complete disagreements are in [results.json](../evaluation/results.json).
The stored artifact is [the application's seed run](../src/main/resources/seed/analysis-run.json).
Fresh validated outputs and numeric telemetry are in [runs](../evaluation/runs).

| Run | Requests | Compatible precision | Compatible recall | Exact quotes | Different wishes: proposed / any option | Analysis seconds | Input / output tokens | Reasoning tokens |
|---|---|---|---|---|---|---|---|---|
| Stored seed | 40/40 | 16/16 (100%) | 16/16 (100%) | 77/77 (100%) | 0 / 1 | Unavailable | Unavailable | Unavailable |
| Live 1 | 40/40 | 16/16 (100%) | 16/16 (100%) | 79/79 (100%) | 0 / 1 | 23.213 | 18,396 / 12,679 | 0 |
| Live 2 | 40/40 | 15/15 (100%) | 15/16 (93.75%) | 79/79 (100%) | 1 / 2 | 25.172 | 18,239 / 13,142 | 59 |
| Live 3 | 40/40 | 15/15 (100%) | 15/16 (93.75%) | 78/78 (100%) | 1 / 2 | 27.212 | 18,216 / 13,113 | 77 |

Latency covers analysis and artifact serialization/write, excluding application
startup. Process wall time and individual decoded-call latencies are available
in telemetry. All fresh attempts are reported, without selecting the best one.
They use `gpt-6.1-sol`, low reasoning effort, four wishes per batch, ten concurrent
classification calls and one proposal stage, with the application's bounded
retries and deadlines. Input and output token totals include every logged
provider response, including any retried response. Reasoning tokens are a subset
of output tokens and must not be added again. HTTP failures/timeouts can have
unreported usage. Provider logs default missing usage fields to zero, so this is
logged response usage, not a billing receipt.

The stored JSON has no latency or token fields and no companion telemetry;
those measurements are unavailable, not zero. Documentation timings from other
experiments are not assigned to it.

Proposed-option verdict confusion (reference rows, model columns):

| Runs | Reference | Compatible | Depends | Incompatible |
|---|---|---|---|---|
| Stored, Live 1 | Compatible | 16 | 0 | 0 |
| Stored, Live 1 | Depends | 0 | 10 | 0 |
| Stored, Live 1 | Incompatible | 0 | 0 | 9 |
| Live 2, Live 3 | Compatible | 15 | 1 | 0 |
| Live 2, Live 3 | Depends | 0 | 10 | 0 |
| Live 2, Live 3 | Incompatible | 0 | 0 | 9 |

Each fresh run logged eleven successful responses: ten classification calls and
one proposal call. Across the 105 request/option pairs, Stored and Live 1 agree
with the reference on 104/105; Live 2 and Live 3 agree on 102/105. These pairs
are correlated judgments from the same wishes, not 105 independent samples.

## Disagreements worth reviewing

- **w15, artwork as a condition.** The wish accepts postcards or stickers and
  says it wants a little piece of the lantern market. The reference reads the
  named scene as a preference: both formats are Compatible, while A5 is
  Incompatible. Stored and Live 1 agree. Live 2 and Live 3 instead require
  confirmation of lantern-market artwork, so postcards and stickers become
  Depends. This accounts for the postcard recall change from 16/16 to 15/16.
  Both readings are plausible; the reference flagged this wish in advance.
- **w23, collection versus single design.** The wish asks for a pocket-size
  collection to tuck into a journal without known dimensions. The reference
  leaves all three options as Depends because fit and collection meaning need
  clarification. Every run makes the A5 single-design print Incompatible:
  one design cannot supply a collection. Postcards and stickers remain Depends.
  This changes an alternative-option reading, not the proposal. This wish was
  also flagged in advance.

All other wishes match the reference for all options in these four runs.
Explicit character conditions (w24-w26) remain Depends, foreign shipping
conditions (w27-w29) remain Incompatible, and praise/instruction-only wishes
(w36-w40) remain non-requests. This is limited evidence on these particular
synthetic examples, not a general prompt-injection robustness claim.

The full wish text, reference explanation and model reasons for every differing
wish are preserved in the results. A proposed-option-only score can conceal a
disagreement about an alternative, which is why both counts are published.
These examples expose interpretation choices; they do not establish an
application validation defect. No application code was changed.

## What this shows and leaves open

The model can reproduce most of this machine-authored reference and provide
textually grounded evidence on a small, deliberately structured example. The
fresh runs show whether classifications vary at fixed settings. Agreement with
a reference produced by a related kind of system is weak evidence of correctness.
The sample includes clear budgets, shipping exclusions, non-requests and hostile
instructions, but is not representative of real audience language.

This does not test human agreement, decision quality, willingness to pay,
payment success, production feasibility, large-input behavior, or time saved.
The few dozen wishes might be faster to sort by hand. The following comparison
must be measured before making a speed claim. Even a manual comparison on this
set would remain small and vulnerable to familiarity effects.

## Stopwatch manual-sorting protocol

1. Use the unchanged forty seed wishes and the option catalog, in seed order.
   Fix the proposed option to `postcard-set` before starting. Do not inspect
   the reference, model results, or this document's disagreements before sorting.
2. Start a stopwatch immediately before reading the catalog and wishes. For each
   wish record `serves: true` only when it can be served without an unresolved
   purchase condition; otherwise record `false`. Thus Depends, incompatible
   requests and non-requests are all "does not serve yet" for this binary task.
   Keep uncertainty and reasons in the optional reason field. Include reading,
   decisions and recording in elapsed time; stop after the fortieth decision.
3. Fill the empty fields in [manual-sorting.json](../evaluation/manual-sorting.json):
   sorter, start time, elapsed seconds and all forty binary choices. Record any
   prior exposure to the seed or results in the sorter field. No manual result
   or time has been measured; every result field is currently null.
4. Only after stopping, reveal the reference. Compare each choice against
   `verdicts["postcard-set"] == "COMPATIBLE"`; a non-request is false. Mark
   `disagreesWithReference`, list disagreement keys and count them. Preserve the
   original choices before discussion or adjudication. Compare the manual
   mismatch count with the model's binary false positives plus false negatives,
   not the three-verdict disagreement count.
5. Report the measured time and disagreements next to the fresh analysis times.
   Include setup/interaction time separately if comparing end-to-end workflows;
   the current AI latency excludes application startup and maker review. Do not
   infer speedup until this step is measured. If the results tie or favor manual
   sorting at this scale, report that result.

## Reproduce

Offline scoring uses Python 3's standard library and needs no credentials:

```sh
python3 -m unittest discover -s evaluation -p 'test_*.py'
python3 evaluation/evaluate.py \
  src/main/resources/seed/analysis-run.json \
  evaluation/runs/live-1.json evaluation/runs/live-2.json \
  evaluation/runs/live-3.json --output evaluation/results.json
```

Live collection is an explicit paid operation, separate from tests. Build the
JAR and inject `LLM_API_KEY` into the environment without printing or tracking
its value. Use a new output filename for every attempt:

```sh
./gradlew bootJar
LLM_SEED_RUN=true LLM_SEED_OUTPUT_FILE=evaluation/runs/new-run.json \
  java -jar build/libs/wishbatch-0.0.1-SNAPSHOT.jar \
  --spring.main.web-application-type=none
```

With no database override, this uses an isolated in-memory database. The
generator refuses an existing output path and writes only validated synthetic
output. This direct command does not collect the companion numeric telemetry
used for the three reported fresh runs; do not assign those earlier timings or
token counts to a new output. Keep failed attempts visible when reporting new
measurements. Do not commit raw logs, credentials, or account records.

The collected results are public synthetic evaluation artifacts, not customer
or payment evidence.
