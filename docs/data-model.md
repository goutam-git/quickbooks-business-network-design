# Data Model

Full DDL lives in `db/migrations/V1__init_schema.sql` (tables) and
`db/views/V2__business_relationship_view.sql` (the derived view). This
is a navigation guide, not a duplicate of the SQL.

## Identity

- **`network_business`** — the canonical, network-level identity
  (`NetworkBusinessId`). `status` is one of `ACTIVE`, `PENDING_SOURCE`,
  `SOURCE_CREATION_FAILED`, `SUPERSEDED`. A `SUPERSEDED` row points at
  its `canonical_business_id`; resolving through that chain is
  `CanonicalizationService`'s one job, and every write path uses it
  first (invariant 19).
- **`source_business_ref`** — typed pointer from a QBO Vendor/Customer
  record to a `network_business`. **Not rewritten during a merge**
  (see `MergeService`'s class javadoc): all lookups canonicalize, so a
  stale ref still resolves correctly, and merge reversal of identity
  mappings is a no-op instead of needing a third snapshot table.
- **`network_business_access`** — `(principal_id, network_business_id) -> permission`.
  `principal_id` is deliberately not assumed to be an individual user.

## Resolution (FR4, Section 12)

- **`identity_resolution`** — one row per `POST /businesses/resolve`
  call. `method` records which scoring path ran, e.g.
  `NAME_SIMILARITY_V1` (deterministic only) or
  `NAME_SIMILARITY_V1+AI_RANKING(EMBEDDING)` /
  `...+AI_RANKING(NGRAM_FALLBACK)` when the AI enhancement participated.
- **`identity_resolution_candidate`** — bounded top-K, with an
  `evidence` JSONB blob holding `deterministicScore`, `aiScore` (if the
  AI service responded), `aiBackend`, `aiModel`, and the final
  `blendedScore` used for the decision. This is the audit trail for
  "why did resolution decide what it decided."

## Relationships (FR3/FR5, Section 8.3)

- **`relationship_assertion`** — WHO/WHAT asserts a relationship exists.
  Multiple assertions (USER/TRANSACTION/IMPORT) can back one logical
  edge; retraction is `status = RETRACTED`, never a delete.
- **`relationship_direction`** — directional, transaction-derived
  evidence; authoritative for `transaction_count`/`transaction_amount`/
  `last_transaction_at`.
- **`business_relationship_view`** — the derived, undirected serving
  view. Gated on at least one `ACTIVE` assertion; no persisted `weight`
  formula (A11) — raw aggregates only.

## Merge (Section 8.4)

- **`identity_merge_event`** — append-only audit log
  (`MERGE_CONFIRMED` -> `CONSOLIDATION_STARTED` -> `CONSOLIDATION_COMPLETED`,
  or the reversal equivalents).
- **`merge_direction_snapshot`** / **`merge_assertion_snapshot`** —
  pre-merge state captured for bounded reversal (A20, invariant 27).

## Vendor add orchestration (FR4a)

- **`business_add_operation`** — the idempotent state machine backing
  `POST /businesses/{id}/vendors`. This repo's schema adds one
  convenience column beyond the original design doc:
  `relationship_id` (nullable), so a repeated `Idempotency-Key` can
  replay the exact prior result without recomputation — called out as
  a demo-only addition in the migration's own comment.
