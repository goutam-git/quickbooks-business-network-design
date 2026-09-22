# QuickBooks Business Network — V1

A runnable implementation of the V1 design in this repo's
[`README.md`](README.md), structured per that doc's
reference-implementation shape. No UI — everything is driven through
the REST API and tested with Postman (or `curl`).

```
quickbooks-business-network/
├── docs/                architecture.md, data-model.md, failure-modes.md
├── service/              the Spring Boot app (identity, relationships, network-query, merge)
│   └── src/test/java/    focused unit tests (see "Tests" below) -- mocked, no DB/Docker needed
├── db/                   migrations/, views/, test-data/ -- single source of truth for schema
├── ai-resolution/        candidate-retrieval/ (pointer, not code), ranking/, evaluation/
└── postman/              business-network.postman_collection.json
```

See `docs/architecture.md` for exactly how this maps to the design
doc's target production architecture and what Section 17 permits this
slice to simplify.

## What's new in this iteration: AI-assisted resolution

`POST /businesses/resolve` (FR4) now runs the Section 12 two-step
pipeline for real:

1. **Deterministic** candidate retrieval + scoring (`service/.../resolution`)
   — always runs, never depends on anything external.
2. **AI/ML re-ranking as an enhancement** (`ai-resolution/ranking`) — a
   standalone HTTP service that re-scores the bounded top-K candidates
   the deterministic step already found. The Java service blends the
   two scores (`business-network.ai-resolution.blend-weight`, default
   0.5) and makes the MATCH/CONFIRM_REQUIRED/NO_MATCH decision on the
   blend. If this service is down, times out, or returns garbage, the
   caller falls back to deterministic-only automatically — see
   `docs/failure-modes.md`.

`ai-resolution/ranking` has two interchangeable backends behind the
same contract, so the demo works with zero setup:

- **No `OPENAI_API_KEY` set (default):** a local, dependency-free
  character-trigram cosine-similarity model. No network calls, instant,
  deterministic. On the included evaluation harness (5 representative
  ambiguity cases, not a statistically meaningful benchmark): it gets
  4 right and deliberately exposes 1 failure case — see below.
- **`OPENAI_API_KEY` set:** real OpenAI embeddings
  (`text-embedding-3-small` by default), same `/rank` contract, no
  other config changes anywhere.

**Known, honest limitation worth demoing on purpose:** the local
fallback is lexical, not semantic. Given the query `"ABC Consulting"`
against candidates `"ABC Consultancy Services LLP"` (the real match)
and `"ABD Consulting"` (a near-identical misspelling), it ranks the
misspelling first — character overlap beats word meaning. This is
exactly the class of error real embeddings exist to fix, and it's the
best live before/after moment: run Postman request 14 with the default
fallback, then set `OPENAI_API_KEY` and re-run it. `ai-resolution/evaluation/evaluate.py`
makes this a repeatable check rather than an anecdote.

## Run it

```bash
docker compose up --build
```

This starts three containers from the repo root: `postgres`,
`ai-resolution` (defaults to the local fallback backend — no API key
needed), and `app` (the Spring Boot service on **http://localhost:8080**).
Flyway runs `db/migrations/`, `db/views/`, then `db/test-data/`
automatically on startup.

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8090/health   # ai-resolution
```

**To demo real embeddings instead of the fallback:**

```bash
OPENAI_API_KEY=sk-... docker compose up --build
# or, to only restart the AI service without rebuilding everything:
OPENAI_API_KEY=sk-... docker compose up -d --build ai-resolution
```

To stop: `Ctrl+C`, then `docker compose down` (add `-v` to also wipe
the Postgres volume).

## Test it with Postman

Import `postman/business-network.postman_collection.json`. Variables
and seeded business IDs are pre-filled; requests are numbered in the
order to run them. **Requests 13–15 are the AI-resolution demo** —
13 checks which backend is live, 14 is the "ABC Consulting" before/after
case above, 15 shows the same signal flowing through the full
`/businesses/resolve` endpoint (look at each candidate's `evidence`
field: `deterministicScore`, `aiScore`, `aiBackend`, `aiModel`,
`blendedScore`, side by side).

### Seeded data (`db/test-data/V3__seed_test_data.sql`)

| Business | NetworkBusinessId |
|---|---|
| Acme Supplies Pvt Ltd | `11111111-1111-1111-1111-111111111111` |
| Bharat Traders | `22222222-2222-2222-2222-222222222222` |
| Chennai Textiles | `33333333-3333-3333-3333-333333333333` |
| Delta Logistics | `44444444-4444-4444-4444-444444444444` |
| Everest Hardware | `55555555-5555-5555-5555-555555555555` |

Seeded edges: Acme↔Bharat, Bharat↔Chennai, Chennai↔Delta. Principal
`demo-user` has `MANAGE` on all five.

## Endpoint reference

Every `service` request needs an `X-Principal-Id` header (looked up
against `network_business_access`; `demo-user` is pre-seeded with
access to everything).

| Method & path | Purpose |
|---|---|
| `POST /businesses/resolve` | FR4, read-only resolution (now AI-enhanced, see above) |
| `POST /businesses/{ownerId}/vendors` | FR3/FR4a, idempotent Add Vendor (`Idempotency-Key` header) |
| `POST /businesses/{ownerId}/vendors/confirm` | Resumes an `AWAITING_CONFIRMATION` Add Vendor operation |
| `POST /relationships` | Lower-level relationship-assertion command |
| `GET /businesses/{id}/network?depth=N&cursor=N` | FR1, bounded network view |
| `GET /relationships/path?from=A&to=B&maxDepth=N` | FR2, shortest path by hop count |
| `POST /business-identity/merges` | Confirm an identity merge (synchronous consolidation) |
| `POST /business-identity/merges/{id}/reverse` | Bounded merge reversal (A20) |
| `GET /health`, `POST /rank` | `ai-resolution/ranking`, standalone (port 8090) |

Error responses are `{code, message, timestamp}`. Key codes:
`INVALID_DEPTH` (400), `NOT_FOUND` (404 — also covers
unauthorized-but-indistinguishable-from-absent, see
`docs/failure-modes.md`), `NOT_FOUND_WITHIN_DEPTH` (404),
`IDENTITY_NOT_ACTIVE` (409), `INVALID_OPERATION_STATE` (409),
`MERGE_ALREADY_SUPERSEDED` (409), `MERGE_NOT_REVERSIBLE` (409).

## What's implemented vs. simplified

| Area | Status |
|---|---|
| Identity, resolution, relationships, network-query, merge | Full schema and logic — see `docs/architecture.md` for the service-boundary mapping inside the single `service/` deployable |
| AI-assisted resolution ranking | Real, running, two-backend service (see above) — genuinely wired in, not mocked |
| Add Vendor orchestration (FR4a) | Full state machine; the QBO source-record association step runs synchronously and is assumed to succeed (Section 17 simplification) |
| Identity merge + consolidation | Runs **synchronously**, inline, in the same transaction as merge confirmation. The design doc specifies a PostgreSQL transactional-outbox pattern for this (an `outbox_event` table, `MERGE_REQUESTED` rows claimed via `FOR UPDATE SKIP LOCKED`, a separate Merge Consolidator worker) — **that table and worker do not exist in this codebase.** Consolidation logic itself (canonicalize edges, combine evidence, collapse duplicates) is implemented and correct; it's just invoked inline instead of behind a queue. Audit trail shape (`MERGE_CONFIRMED` → `CONSOLIDATION_STARTED` → `CONSOLIDATION_COMPLETED`) is preserved regardless. |
| `source_business_ref` rewrite during merge | Deliberately not rewritten — see `docs/data-model.md` |
| Async consolidation worker, `outbox_event` table, orphan-retry background job | **Not implemented.** Out of scope for this vertical slice (Section 17 permits collapsing distributed components); named explicitly here rather than left implicit, per the design doc's own instruction not to present this as implemented unless it actually is |
| `TRAVERSE_ONLY` authorization outcome (Section 16 of the design doc: a node usable as a path intermediary but never directly disclosed) | **Not implemented as a distinct state.** V1 only has `REVEAL`/visible and `DENY`/hidden (surfaced as 404) — a hidden node is never used as a pass-through either, which is the conservative collapse of the three-state model, not the permissive one, but it is a real simplification worth naming |
| Authentication | None — `X-Principal-Id` stands in for an authenticated caller. Do not deploy as-is. |

## Running the pieces individually (optional)

```bash
# just Postgres + ai-resolution
docker compose up -d postgres ai-resolution

# the Java app locally (needs JDK 21 + Maven; run from service/)
cd service
export DB_HOST=localhost AI_RESOLUTION_URL=http://localhost:8090/rank
mvn spring-boot:run

# the ranking service locally (needs Python 3.12)
cd ai-resolution/ranking
pip install -r requirements.txt
uvicorn app:app --reload --port 8090

# the evaluation harness against a running ranking service
cd ai-resolution/evaluation
pip install requests
python evaluate.py --url http://localhost:8090/rank
```

## Tests

Postman exercises the system end-to-end but isn't validation evidence on
its own. `service/src/test/java` has a small, deliberately narrow suite
targeting the logic most worth getting wrong quietly -- not exhaustive
coverage, ~23 focused tests, all pure unit tests (Mockito-mocked
repositories/clients, no Spring context, no database, no Docker):

| Test class | What it proves |
|---|---|
| `NameSimilarityTest` | Exact match, legal-suffix normalization, weakly-related names score low |
| `IdentityResolutionServiceTest` | MATCH / CONFIRM_REQUIRED / NO_MATCH thresholds; AI-unavailable falls back to deterministic-only; AI can re-rank candidates but cannot push a weak deterministic score past MATCH_THRESHOLD on its own |
| `GraphTraversalServiceTest` | Depth 1/2/3 traversal, a 3-node cycle doesn't loop or duplicate, `INVALID_DEPTH` rejection, node-budget exhaustion is reported as `truncated`, shortest path by hop count, `NOT_FOUND_WITHIN_DEPTH` beyond the search budget |
| `AddVendorServiceTest` | A repeated `Idempotency-Key` replays the stored result without re-invoking resolution or relationship creation; an ambiguous resolution correctly parks the operation in `AWAITING_CONFIRMATION` |
| `MergeServiceTest` | Merge supersedes source and points it at target with a full 3-event audit trail; merging an identity into itself is rejected; reversal restores `ACTIVE` status; reversal is blocked (A20) when the target has itself since been superseded |

```bash
cd service
mvn test
```

**Why these and not more:** this is meant to be credible validation
evidence for a craft review, not a production coverage target. Each
test targets a specific threshold, budget, or state-machine transition
called out in the design doc rather than re-testing getters/setters or
framework wiring.

One of these tests earned its keep immediately: writing
`GraphTraversalServiceTest` against `shortestPath()` surfaced a real
type bug in `GraphTraversalService` (it was built against the wrong
repository row shape -- `neighborsOf()` returns a differently-oriented
row than `neighborsOfAny()`, and the two had gotten conflated). Fixed
before the first real build ever ran — see "Build & runtime
verification" below for the full list of what was actually run and
what it caught.

## Build & runtime verification

This isn't just "should compile" — every layer was actually exercised, not just reasoned about:

- **`mvn clean package`**: real build, on JDK 21, from a clean checkout. `BUILD SUCCESS`, all 23 unit tests passed (`Tests run: 23, Failures: 0, Errors: 0`) — `NameSimilarityTest` (4), `IdentityResolutionServiceTest` (5), `GraphTraversalServiceTest` (8), `AddVendorServiceTest` (2), `MergeServiceTest` (4).
- **`docker compose up --build`**: full three-container stack (`postgres`, `ai-resolution`, `app`) built and started from scratch, Flyway migrated all three files cleanly (`V1__init_schema.sql` → `V2__business_relationship_view.sql` → `V3__seed_test_data.sql`), app reached `Started BusinessNetworkApplication`.
- **The full Postman collection**: all 20 requests run against the live stack, with actual response *bodies* inspected — not just status codes — including tracing an apparent 409 failure back to a stale screenshot from an earlier run rather than assuming it was a bug.
- **Real bugs found this way, fixed before merge**, none of them catchable by static analysis alone:
  1. `identity_resolution_candidate.score` — SQL `NUMERIC(6,4)` vs. Java `double` (Hibernate expects `DOUBLE PRECISION`); only surfaces at container startup via `ddl-auto: validate`.
  2. `SourceBusinessRefId.sourceEntityType` — missing `@Enumerated(EnumType.STRING)`, defaulted to ORDINAL against a `VARCHAR` column.
  3. `GlobalExceptionHandler` — malformed UUIDs (path or body) were falling through to a raw `500`; added proper handling for `400`.

The Python `ai-resolution/ranking` service was run and smoke-tested directly too, including the `/rank` endpoint and the `evaluate.py` harness (4/5 on the local fallback backend, with the one deliberate failure case documented above).
