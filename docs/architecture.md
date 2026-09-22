# Architecture — V1 Executable Vertical Slice

This describes how the repository maps to the target production
architecture in `quickbooks-business-network-design-v2.md`, and where
this slice deliberately simplifies (Section 17 of that doc explicitly
permits this).

## Repository layout

```
quickbooks-business-network/
├── docs/            this folder
├── service/         the Spring Boot app (single deployable, see below)
├── db/               single source of truth for schema/views/test data
└── ai-resolution/    the one AI/ML-touching component (Section 12)
```

## `service/` — logical boundaries inside one deployable

The design doc's target architecture separates **identity**,
**relationships**, **network-query**, and **merge** as distinct
production components. This slice keeps them as one Spring Boot
deployable (Section 17), but the logical boundaries are still real
Java packages with a single, narrow crossing point between them
(`CanonicalizationService`), not a ball of mud:

| Logical service | Java package | Responsibility |
|---|---|---|
| identity | `identity`, `resolution`, `vendor` | `NetworkBusiness`, source refs, access control, FR4 resolution, FR3/FR4a Add-Vendor orchestration |
| relationships | `relationship` (assertion + direction classes, `RelationshipService`) | FR3/FR5 — relationship assertions, directional evidence |
| network-query | `relationship` (`GraphTraversalService`, traversal DTOs) | FR1/FR2 — bounded network view + shortest-path search |
| merge | `merge` | Identity merge, inline consolidation (no outbox — see below), bounded reversal (A20) |

`relationships` and `network-query` share one Java package today
because they share the same JPA entities and the read-only
`business_relationship_view`; if this ever splits into separate
deployables, network-query becomes a read-only consumer of that view
(already true today) and the split is mechanical.

## The one deliberately-unbuilt piece: the `outbox_event` pattern

The design doc specifies a PostgreSQL transactional outbox for merge
consolidation (Section 11/"V1 asynchronous-work model"): an
`outbox_event` table, a merge writing a `MERGE_REQUESTED` row in the
same transaction as the canonical-identity change, and a separate Merge
Consolidator worker claiming pending rows via
`SELECT ... FOR UPDATE SKIP LOCKED` before doing the actual consolidation
work outside that short claim transaction.

**None of that exists in this codebase.** `MergeService.confirmMerge()`
does the consolidation work (`consolidateAssertions`,
`consolidateDirection`) inline, synchronously, in the same transaction
as the merge confirmation itself — there is no `outbox_event` table, no
queue, no separate worker process, and consequently no
`PENDING`→`PROCESSING`→`COMPLETED` state machine or claim-based
concurrency to reason about.

This is licensed by Section 17 ("collapses distributed components into
a Spring Boot application"), and the consolidation logic itself is
correct and tested (`MergeServiceTest`) — what's missing is purely the
asynchronous delivery mechanism around it. The design doc is explicit
that this distinction matters: it says the CDC/event-stream path
"should not be presented as already implemented in the V1 reference
code unless the corresponding producer/consumer exists." Same standard
applies here, so it's named directly rather than left to be inferred
from "synchronous consolidation" alone.

## `db/` — single source of truth

- `db/migrations/` — schema (tables, constraints, indexes)
- `db/views/` — the derived `business_relationship_view` (Section 8.3),
  kept separate from the schema because it's *derived*, never written
  to directly, and conceptually a different kind of artifact
- `db/test-data/` — seed data for local/demo use only

`service/pom.xml` pulls all three onto the Flyway classpath at build
time (see its `<resources>` block) so there is exactly one copy of
each migration file, not a service-local duplicate that can drift.

## `ai-resolution/` — Section 12's AI/ML enhancement, isolated

```
ai-resolution/
├── candidate-retrieval/   pointer, not code (see its README) --
│                          deterministic retrieval stays in service/resolution
├── ranking/               the actual AI/ML component: a standalone
│                          HTTP service (FastAPI) the Java app calls
└── evaluation/            a small labeled-case harness for ranking quality
```

This is intentionally its own deployable, not a Java-side call to an
LLM SDK: it makes the "AI assists ranking, doesn't gatekeep identity"
boundary a network boundary, not just a code convention, and it's what
lets the failure mode below actually be tested (kill the container,
watch resolution keep working).

Wire-up: `service`'s `IdentityResolutionService` always runs the
deterministic scorer first; if `business-network.ai-resolution.enabled`
is true, it calls `ai-resolution/ranking` with the bounded top-K
candidates the deterministic step already found, blends the two scores
(`business-network.ai-resolution.blend-weight`, default 0.5), and makes
the MATCH/CONFIRM_REQUIRED/NO_MATCH decision on the blended score. See
`docs/failure-modes.md` for what happens when the AI call fails.
