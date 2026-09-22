# candidate-retrieval

This directory is deliberately (almost) empty.

Per the design doc's Section 12 pipeline — `Normalize input → Candidate
retrieval (deterministic matching first) → Scoring (deterministic +
ML/semantic signals as enhancement)` — candidate retrieval is a
**deterministic** step, and it already lives in exactly one place:

`service/src/main/java/com/quickbooks/biznetwork/resolution/service/IdentityResolutionService.java`

It retrieves candidates by deterministic name-similarity against
`ACTIVE` businesses and keeps the bounded top-K (see invariant 25:
"resolution decisions plus bounded top-K candidates are retained").

Duplicating that logic here — e.g. a second candidate-retrieval pass
inside the AI service — would violate the doc's correctness framing:

> "Verified deterministic identifiers... establish identity directly.
> AI/ML assists in finding and ranking *ambiguous* candidates; it does
> not unilaterally establish financial-graph truth in V1."

If retrieval quality ever needs improving (e.g. adding a phonetic or
tax-ID index), that change belongs in `IdentityResolutionService`, not
here — `ai-resolution/ranking` should keep receiving a candidate list
it did not choose, and its job stays strictly "**score/re-rank what
you were handed**."

See `../ranking/app.py` for the actual AI/ML re-ranking step, and
`../evaluation/evaluate.py` for how ranking quality is checked.
