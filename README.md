# QuickBooks Business Network — Interview-Focused System Design

**Scope:** V1 design and executable reference implementation.

> This version intentionally keeps only the decisions, flows, assumptions, and trade-offs that are useful to explain in an interview. Detailed speculative mechanisms and low-value open questions have been removed.

---

## 1. Problem Statement

Design a system within QuickBooks that maps a business's vendor/client network so a business can:

1. **View its network** — direct and bounded multi-hop vendors/clients.
2. **Search a relationship** — find a bounded path between two businesses.
3. **Grow the network** — add a vendor/client, including when that business may already exist under another descriptor.
4. **Remain responsive and highly available.**

Given constraints:

- ~1M businesses.
- ≤100 direct relationships per business.
- ~10M relationship searches/month.
- Relationships are **undirected**.
- Relationship strength is based on **transaction volume**.
- Traffic is skewed; some businesses may be much hotter than others.

---

## 2. Clarifications / Working Assumptions

Only assumptions that materially affect the V1 architecture are retained.

| # | Area | V1 assumption | Why it matters |
|---|---|---|---|
| A1 | Business identity | Use a stable `NetworkBusinessId` that can map multiple QBO Vendor/Customer records to one network business. | Keeps graph identity independent of source-system role records. |
| A2 | Network exploration | Network view is bounded; default maximum depth is 3 hops. | Prevents traversal explosion. |
| A3 | Relationship search | V1 path search uses **shortest hop count** with bounded BFS. | Clear, explainable path semantics; transaction volume is not treated as path cost. |
| A4 | Authorization | Authorization is checked while expanding the graph, not after traversal. | Prevents topology leakage through hidden businesses. |
| A5 | Identity ambiguity | Ambiguous identity matches require user confirmation. | Avoids unsafe automatic merges. |
| A6 | Consistency | PostgreSQL is authoritative in V1, allowing read-your-writes where required. | Avoids projection lag and dual-datastore complexity. |
| A7 | Relationship strength | Higher transaction volume means a stronger direct relationship. Network-view ranking may use volume, but V1 does not invent a multi-hop path-strength formula. | Separates edge strength from path-selection semantics. |
| A8 | Source ownership | QBO remains authoritative for Vendor/Customer source records and transactions. | Business Network stores canonical mappings and relationship aggregates, not raw transaction history. |

### If an assumption is wrong

The most important structural assumption is A1. If QuickBooks already provides a canonical cross-role Business ID, the identity-unification layer becomes much simpler. The relationship, traversal, authorization, and API boundaries can still operate on the stable business ID.

---

## 3. Functional Requirements

### FR1 — View Network

```http
GET /businesses/{id}/network?depth=N&cursor=...
```

Returns:

- direct and bounded multi-hop businesses;
- authorized nodes/edges only;
- hop distance;
- relationship metrics;
- ranked/paginated results;
- truncation/budget metadata.

### FR2 — Search Relationship

```http
GET /relationships/path?from=A&to=B&maxDepth=N
```

V1 returns an **authorized shortest-hop path** within the configured search budget.

Example:

```text
A ---- B ---- D       = 2 hops

A ---- C ---- E ---- D = 3 hops
```

BFS returns:

```text
A -> B -> D
```

Transaction volume does not change hop distance.

### FR3 — Add Vendor / Client

Add a vendor/client after resolving whether it already maps to an existing `NetworkBusiness`.

Possible identity outcomes:

```text
MATCH
NO_MATCH
CONFIRM_REQUIRED
```

### FR4 — Identity Resolution

```http
POST /businesses/resolve
```

This operation is read-only. It returns identity-resolution results but does not create a new business.

### FR5 — Relationship Strength

Preserve:

```text
transactionCount
transactionAmount
lastTransactionAt
```

For the V1 network view:

- hop distance determines graph distance;
- within the same hop, transaction volume can be used as a presentation-ranking signal;
- higher volume means a stronger direct relationship.

V1 does **not** define a composite formula such as:

```text
weight = amount × count × recency
```

and does not use transaction volume as BFS traversal cost.

---

## 4. Scale and the Main Scaling Risk

Given:

```text
Businesses                  ≈ 1,000,000
Max degree                  ≤ 100
Relationship searches/month ≈ 10,000,000
Average QPS                 ≈ 3.86
```

Raw QPS is not the hardest problem.

The main risk is **multi-hop expansion**.

At maximum degree 100:

```text
Depth 1 ≈ 100
Depth 2 ≈ 10,000
Depth 3 ≈ 1,000,000
```

Real graphs contain overlap and cycles, but the system cannot rely on that.

Therefore:

> **3 hops does not mean return or even explore everything reachable within 3 hops.**

### Compute budget

```text
maxDepth
maxExploredNodes
maxExploredEdges
timeout
```

### Response budget

```text
maxReturnedNodes
maxReturnedEdges
pageSize
```

These are separate because limiting the response does not protect the datastore if the system still explores the entire graph first.

### Current reference-implementation configuration

```text
Network view:
maxDepth             = 3
maxExploredNodes     = 5,000
maxExploredEdges     = 20,000
maxReturnedNodes     = 200
maxReturnedEdges     = 500
defaultPageSize      = 50

Path search:
maxDepth             = 4
maxExploredNodes     = 5,000
```

The executable slice currently enforces depth/node/edge-style budgets. A wall-clock traversal timeout is an architecture goal rather than a completed implementation detail.

---

## 5. Business Identity Model

QuickBooks source records may represent the same real business in different roles.

Example:

```text
QBO Vendor V-17 "ABC Ltd"   ─┐
                              ├──> NetworkBusiness NB-42
QBO Customer C-91 "ABC Ltd" ─┘
```

### Ownership

```text
QBO
- Vendor/Customer records
- source attributes
- transactions

Business Network
- NetworkBusiness identity
- source-to-network mappings
- relationship graph
- identity resolution decisions
```

### Source mapping

```text
source_business_ref
```

maps:

```text
(QBO, VENDOR, V-17)   -> NB-42
(QBO, CUSTOMER, C-91) -> NB-42
```

This lets later source events resolve directly to the canonical network identity.

---

## 6. Core Data Model

### `network_business`

Canonical network identity.

```text
network_business_id
display_name
status
canonical_business_id
created_at
updated_at
version
```

Important statuses:

```text
PENDING_SOURCE
ACTIVE
SUPERSEDED
SOURCE_CREATION_FAILED
```

---

### `source_business_ref`

Maps a QBO source record to a network business.

```text
source_system
source_entity_type
source_entity_id
network_business_id
source_display_name
```

Primary key:

```text
(source_system, source_entity_type, source_entity_id)
```

---

### `network_business_access`

Authorization projection.

```text
principal_id
network_business_id
permission
```

Permissions:

```text
VIEW
MANAGE
ADMIN
```

---

### `business_add_operation`

Durable orchestration/idempotency record for Add Vendor.

```text
operation_id
idempotency_key
owner_business_id
network_business_id
resolution_id
state
last_error_code
```

Typical states:

```text
RESOLVING
AWAITING_CONFIRMATION
SOURCE_PENDING
SOURCE_CREATED
RELATIONSHIP_CREATED
FAILED
```

---

### `identity_resolution`

Stores the resolution decision.

```text
resolution_id
input_descriptor
decision
selected_business_id
method
actor_id
created_at
```

Decision:

```text
MATCH
NO_MATCH
CONFIRM_REQUIRED
```

---

### `identity_resolution_candidate`

Stores bounded top candidates.

```text
resolution_id
candidate_business_id
rank
score
evidence
selected
```

---

### `relationship_assertion`

Represents the fact that a logical relationship exists.

```text
relationship_id
business_low_id
business_high_id
source_type
source_reference
status
created_by
created_at
```

Endpoints are normalized:

```text
low = min(A, B)
high = max(A, B)
```

so:

```text
A -- B
```

and:

```text
B -- A
```

represent the same logical undirected edge.

---

### `relationship_direction`

Stores directional transaction evidence.

```text
seller_business_id
buyer_business_id
transaction_count
transaction_amount
last_transaction_at
version
```

Example:

```text
A -> B : ₹3M, 120 transactions
B -> A : ₹1M,  40 transactions
```

Directional evidence is retained even though the product network is undirected.

---

### `business_relationship_view`

Derived read model:

```text
relationship_assertion
        +
relationship_direction
        |
        v
business_relationship_view
```

It exposes:

```text
business_low_id
business_high_id
volume_amount
transaction_count
last_transaction_at
```

The application reads this view for graph traversal.

It is **derived**, not independently written.

---

## 7. Relationship Weight — Interview-Safe Semantics

The requirement says:

> Relationships are undirected and weighted by transaction volume.

V1 interprets this as:

```text
Higher transaction volume
        ↓
Stronger direct relationship
```

Example:

```text
A ---- ₹10M ---- B   stronger

A ---- ₹1M ----- C   weaker
```

However, this does not automatically define how multiple edge strengths should be combined into a multi-hop path score.

Example:

```text
         ₹1M         ₹3M
A -------- B -------- C

 \------- D --------/
     ₹5M       ₹2M
```

Both paths have two hops:

```text
A -> B -> C
A -> D -> C
```

Possible path-strength rules could include sum, minimum edge, average, recency, or a product-defined combination.

V1 does not invent one.

Therefore the design separates:

```text
Hop count
    =
graph distance / path search

Transaction volume
    =
direct relationship strength / presentation ranking
```

### Current network-view ranking

For network presentation:

```text
1. Lower hop distance first
2. Within the same hop, higher transaction volume first
```

Example:

```text
A ---- ₹1M ---- B
|
| ₹5M
|
D
```

Both B and D are hop 1.

Presentation order:

```text
D   hop=1   ₹5M
B   hop=1   ₹1M
```

### Current path search

Path search uses BFS and does **not** use volume to choose among equal-hop paths.

---

## 8. Read Flow — View Network

API:

```http
GET /businesses/NB-100/network?depth=2
X-Principal-Id: P-100
```

Flow:

```text
QuickBooks UI
      |
      v
BusinessController
      |
      v
GraphTraversalService.networkView()
      |
      +--> validate depth
      |
      +--> authorize root
      |
      v
BFS frontier
      |
      v
BusinessRelationshipViewRepository
      |
      v
business_relationship_view
      |
      v
neighboring relationships
      |
      v
authorization check during expansion
      |
      v
visited + distance
      |
      v
compute budget
      |
      v
rank + response cap + pagination
      |
      v
NetworkViewResponse
```

### Hop placement

If:

```text
A ---- B ---- D
|
+---- C ---- E
```

then:

```text
A = hop 0

B, C = hop 1

D, E = hop 2
```

A node's hop is determined by the minimum number of relationship edges from the starting business.

Transaction volume does not determine hop placement.

---

## 9. Read Flow — Search Relationship

API:

```http
GET /relationships/path?from=A&to=D&maxDepth=3
X-Principal-Id: P-100
```

Current implementation:

```text
RelationshipController
        |
        v
GraphTraversalService.shortestPath()
        |
        v
BFS Queue
        |
        v
neighborsOf(current)
        |
        v
authorization
        |
        +--> visited set
        |
        +--> predecessor map
        |
        +--> depth
        |
        v
target found?
   /         \
 yes          no
 |             |
 v             v
reconstruct   NOT_FOUND_WITHIN_DEPTH
path
```

Why BFS?

BFS explores:

```text
hop 0
then hop 1
then hop 2
then hop 3
```

Therefore the first discovery of the target gives the minimum hop distance in the authorized graph.

Example:

```text
A -> B -> D       2 hops
A -> C -> E -> D  3 hops
```

returns:

```text
A -> B -> D
```

The predecessor map reconstructs the path.

---

## 10. Write Flow — Add Vendor

API:

```http
POST /businesses/{ownerBusinessId}/vendors
Idempotency-Key: ADD-001
X-Principal-Id: P-100
```

Conceptual request:

```json
{
  "sourceSystem": "QBO",
  "sourceEntityType": "VENDOR",
  "sourceEntityId": "V-17",
  "displayName": "Fresh Flour Ltd",
  "email": "accounts@freshflour.example"
}
```

Flow:

```text
VendorController
      |
      v
AddVendorService
      |
      +--> require MANAGE permission
      |
      +--> check Idempotency-Key
      |
      +--> create/resume business_add_operation
      |
      v
IdentityResolutionService
      |
      +----------+-----------+
      |          |           |
    MATCH     NO_MATCH   CONFIRM_REQUIRED
      |          |           |
      v          v           v
 use existing  create     wait for
 ACTIVE NB     new NB     user choice
      |          |
      +-----+----+
            |
            v
RelationshipService
            |
            v
relationship_assertion
            |
            v
RELATIONSHIP_CREATED
```

### MATCH

Use the existing canonical `NetworkBusiness`.

No new `network_business` is created.

### NO_MATCH

Conceptually:

```text
Create NetworkBusiness(PENDING_SOURCE)
        |
        v
associate/create QBO source record
        |
        v
source_business_ref
        |
        v
NetworkBusiness(ACTIVE)
        |
        v
create relationship
```

The executable reference slice models this lifecycle but simplifies the real external QBO source-association/retry infrastructure.

### CONFIRM_REQUIRED

The operation pauses in:

```text
AWAITING_CONFIRMATION
```

The user chooses:

```text
USE_EXISTING
```

or:

```text
CREATE_NEW
```

and the same operation resumes.

---

## 11. Identity Resolution

V1 resolution pipeline:

```text
Input descriptor
      |
      v
candidate retrieval
      |
      v
deterministic similarity
      |
      v
bounded top candidates
      |
      v
optional AI ranking
      |
      v
decision
```

Possible decisions:

```text
MATCH
NO_MATCH
CONFIRM_REQUIRED
```

Safety rule:

> Ambiguous candidates require confirmation; the AI ranker does not independently mutate canonical identity.

The current executable slice uses a simplified candidate-retrieval path. A production implementation should use indexed/blocking retrieval rather than scanning all businesses.

---

## 12. Transaction Evidence

QBO remains the transaction-level source of truth.

Business Network should not scan raw QBO transaction history during graph reads.

Production concept:

```text
QBO transaction changes
        |
        v
transaction evidence processing
        |
        v
aggregate by directional business pair
        |
        v
relationship_direction
        |
        v
business_relationship_view
```

This converts many raw transactions into compact relationship aggregates.

Example:

```text
100,000 raw transactions
        |
        v
one directional aggregate

A -> B
transaction_count  = 100,000
transaction_amount = ...
last_transaction_at = ...
```

The executable reference implementation does not contain the full production CDC/evidence-ingestion pipeline; it primarily demonstrates the relationship model and serving path.

---

## 13. Datastore Decision

### V1: PostgreSQL

PostgreSQL is the authoritative V1 datastore.

Why:

- ~1M businesses is manageable.
- Average search QPS is modest.
- Identity/source mappings are relational.
- Add Vendor needs transactional/idempotent state.
- Relationship assertions and audit/provenance fit relational storage.
- Direct and bounded graph queries can be implemented and benchmarked.
- One authoritative datastore keeps consistency and operations simpler.

### Why not Neo4j immediately?

Because:

> The domain being a graph does not by itself justify a graph database.

Neo4j becomes attractive if measured production usage requires:

- frequent deeper traversal;
- graph-centric path semantics;
- traversal latency PostgreSQL cannot meet within bounded work limits.

### Why not PostgreSQL + Neo4j immediately?

A hybrid architecture introduces:

```text
projection lag
rebuild logic
two-datastore operations
failure handling
consistency complexity
```

without evidence that V1 needs it.

### Redis

Redis is also not required in V1.

It becomes useful if skewed traffic shows repeated hot-neighborhood reads that benefit materially from caching.

---

## 14. High-Level Architecture

### Interview-facing ASCII HLD

```text
                                      QUICKBOOKS UI
                                           |
                                           v
                              +---------------------------+
                              |        API GATEWAY        |
                              | Authentication            |
                              | Rate Limiting             |
                              | Trusted Principal Context |
                              +-------------+-------------+
                                            |
                         +------------------+------------------+
                         |                                     |
                       READ                                  WRITE
                         |                                     |
                         v                                     v
              +----------------------+              +----------------------+
              |    NETWORK QUERY     |              | RELATIONSHIP COMMAND |
              |----------------------|              |----------------------|
              | View Network         |              | Add Vendor / Client  |
              | Search Path          |              | Add / Retract Edge   |
              | Bounded BFS          |              | Idempotency          |
              | Rank / Paginate      |              | MANAGE AuthZ         |
              +----------+-----------+              +----------+-----------+
                         |                                     |
                         |                              identity unknown
                         |                                     |
                         |                                     v
                         |                         +------------------------+
                         |                         |  IDENTITY RESOLUTION   |
                         |                         |------------------------|
                         |                         | Normalize descriptor   |
                         |                         | Candidate retrieval    |
                         |                         | Deterministic signals  |
                         |                         +-----------+------------+
                         |                                     |
                         |                                     v
                         |                         +------------------------+
                         |                         |  CANDIDATE MATCHING    |
                         |                         | Ambiguous candidates   |
                         |                         +-----------+------------+
                         |                                     |
                         |                                     v
                         |                         +------------------------+
                         |                         | AI / ML CANDIDATE      |
                         |                         | RANKER                  |
                         |                         |------------------------|
                         |                         | Semantic similarity    |
                         |                         | Candidate ranking      |
                         |                         | Confidence / evidence  |
                         |                         +-----------+------------+
                         |                                     |
                         |                  +------------------+------------------+
                         |                  |                  |                  |
                         |                  v                  v                  v
                         |               MATCH             NO_MATCH       CONFIRM_REQUIRED
                         |                  |                  |                  |
                         |                  |                  |                  v
                         |                  |                  |          +----------------+
                         |                  |                  |          | HUMAN DECISION |
                         |                  |                  |          +-------+--------+
                         |                  |                  |             /         \
                         |                  |                  |      Use Existing    Create New
                         |                  |                  |          |              |
                         |                  v                  v          v              v
                         |             +-------------+       +---------------------------+
                         |             | Reuse       |       | Create NetworkBusiness    |
                         |             | Existing NB |       | PENDING_SOURCE            |
                         |             +------+------+       +-------------+-------------+
                         |                    |                              |
                         |                    |                              v
                         |                    |                 +-------------------------+
                         |                    |                 | QBO VENDOR / CUSTOMER   |
                         |                    |                 | SOURCE OF TRUTH         |
                         |                    |                 +-----------+-------------+
                         |                    |                             |
                         |                    |                    +--------+--------+
                         |                    |                    |                 |
                         |                    |                 SUCCESS            FAILURE
                         |                    |                    |                 |
                         |                    +----------+---------+                 v
                         |                               |                 +-------------------+
                         |                               |                 | Durable Add       |
                         |                               |                 | Operation         |
                         |                               |                 | SOURCE_PENDING    |
                         |                               |                 +---------+---------+
                         |                               |                           |
                         |                               |                           v
                         |                               |                 +-------------------+
                         |                               |                 | Source Association|
                         |                               |                 | Retry Worker      |
                         |                               |                 +---------+---------+
                         |                               |                           |
                         |                               |                           +----> QBO
                         |                               v
                         |                    +----------------------+
                         |                    | Relationship Command |
                         |                    | continues            |
                         |                    |----------------------|
                         |                    | Canonicalize IDs     |
                         |                    | Validate AuthZ       |
                         |                    | Create Assertion     |
                         |                    +----------+-----------+
                         |                               |
                         +-------------------------------+
                                                         |
                                                         v
       +--------------------------------------------------------------------------------+
       |                         POSTGRESQL — AUTHORITATIVE                             |
       |--------------------------------------------------------------------------------|
       | NetworkBusiness             source_business_ref                               |
       | network_business_access     identity_resolution                               |
       | resolution_candidates       business_add_operation                            |
       | relationship_assertion      relationship_direction                            |
       | business_relationship_view  identity_merge_event                              |
       | outbox_event                                                                   |
       +------------------------------+-------------------------------------------------+


                            DUPLICATE / MERGE PATH
                            ======================

                         Identity Resolution
                                 |
                                 | two existing identities may be duplicates
                                 v
                    +----------------------------+
                    |   DUPLICATE EVALUATION     |
                    |----------------------------|
                    | Existing identities        |
                    | Evidence / policy          |
                    | Human confirmation         |
                    +-------------+--------------+
                                  |
                                  | duplicate confirmed
                                  v
                    +----------------------------+
                    |      MERGE WORKFLOW        |
                    |----------------------------|
                    | Validate + lock identities |
                    | Canonical identity change  |
                    | Record audit decision      |
                    +-------------+--------------+
                                  |
                                  | SYNCHRONOUS DB transaction
                                  v
                    +----------------------------+
                    |         PostgreSQL         |
                    |----------------------------|
                    | Source = SUPERSEDED        |
                    | canonical_id = target      |
                    | merge audit                |
                    | MERGE_REQUESTED outbox     |
                    +-------------+--------------+
                                  |
                                COMMIT
                                  |
                         logical merge complete
                                  |
                                  | Transactional Outbox
                                  | ASYNCHRONOUS
                                  v
                    +----------------------------+
                    |    MERGE CONSOLIDATOR      |
                    |----------------------------|
                    | Move source mappings       |
                    | Canonicalize edges         |
                    | Consolidate evidence       |
                    +-------------+--------------+
                                  |
                                  v
                             PostgreSQL


                         TRANSACTION EVIDENCE PATH
                         =========================

                           QBO RAW TRANSACTIONS
                                  |
                     +------------+-------------+
                     |                          |
                     v                          v
             +------------------+       +---------------------+
             | HISTORICAL       |       | TRANSACTION CHANGE  |
             | BOOTSTRAP        |       | FEED                |
             | Batch aggregate  |       | Mechanism TBD       |
             +--------+---------+       +----------+----------+
                      |                            |
                      |                            v
                      |                 +------------------------+
                      |                 | TRANSACTION EVIDENCE   |
                      |                 | PROCESSOR              |
                      |                 |------------------------|
                      |                 | Resolve canonical IDs  |
                      |                 | Validate               |
                      |                 | Deduplicate / replay   |
                      |                 | Pre-aggregate          |
                      |                 | Idempotent processing  |
                      |                 +-----------+------------+
                      |                             |
                      +--------------+--------------+
                                     |
                              aggregate UPSERT
                                     |
                                     v
                         +-------------------------+
                         | relationship_direction  |
                         |       PostgreSQL        |
                         +-------------------------+


                           AUTHORIZATION SYNC
                           ==================

                     QBO IDENTITY / ENTITLEMENTS
                           AUTHORITATIVE
                                 |
                     +-----------+-----------+
                     |           |           |
                     v           v           v
                  Snapshot     Events     Reconcile
                     |           |           |
                     +-----------+-----------+
                                 |
                                 v
                     +-----------------------+
                     |  AUTHORIZATION SYNC   |
                     |-----------------------|
                     | QBO company -> NB ID  |
                     | role -> permission    |
                     +-----------+-----------+
                                 |
                                 v
                     network_business_access
                                 |
                                 v
                            PostgreSQL


                       BENCHMARK-GATED EVOLUTION
                       =========================

                 Network Query
                      |
                      | hot cache if justified
                      + - - - - - - - - - - - > +------------------+
                                                | Redis            |
                                                | Hot neighborhood |
                                                | NOT authoritative|
                                                +------------------+

                 PostgreSQL / Outbox
                      |
                      | future projection
                      v
               +-------------------+
               | Projection Worker |
               +---------+---------+
                         |
                         | benchmark-gated
                         v
               +---------------------+
               | Neo4j               |
               | Graph projection    |
               | NOT authoritative   |
               +---------------------+
```

---

## 15. API Surface

| API | Purpose |
|---|---|
| `GET /businesses/{id}/network?depth=N&cursor=...` | Bounded authorized network view |
| `GET /relationships/path?from=A&to=B&maxDepth=N` | Bounded authorized shortest-hop path |
| `POST /businesses/resolve` | Read-only identity resolution |
| `POST /businesses/{owner}/vendors` | Idempotent Add Vendor workflow |
| `POST /businesses/{owner}/vendors/confirm` | Resume ambiguous Add Vendor flow |
| `POST /relationships` | Create a relationship between resolved ACTIVE businesses |

Important response semantics:

```text
INVALID_DEPTH
NOT_FOUND
NOT_FOUND_WITHIN_DEPTH
IDENTITY_NOT_ACTIVE
INVALID_OPERATION_STATE
```

`NOT_FOUND_WITHIN_DEPTH` means:

> No authorized path was found inside the configured search envelope.

It does not prove global disconnection.

---

## 16. Security

Authorization is enforced during traversal.

Example:

```text
A ---- B ---- C
```

If the caller can see A and C but cannot see B, returning:

```text
A -> B -> C
```

would reveal B's existence and relationship topology.

Therefore:

```text
candidate neighbor
      |
      v
authorization check
   /       \
 allow     deny
   |         |
 expand     skip
```

V1 uses a conservative rule: an edge is traversed only when the relevant endpoint is visible to the principal.

---

## 17. Reliability and Failure Handling

Keep the V1 reliability story simple:

### Idempotent Add Vendor

```text
Idempotency-Key
      |
      v
business_add_operation
      |
      v
same retry resumes same operation
```

This prevents duplicate businesses and duplicate relationships on client retries.

### Identity resolution failure

Do not create a speculative business if resolution cannot safely complete.

### External source association failure

The architecture keeps the business non-ACTIVE until source association succeeds. The executable slice simplifies the actual external retry machinery.

### Traversal overload

Bound work using:

```text
maxDepth
maxExploredNodes
maxExploredEdges
```

and return truncation/budget metadata instead of allowing unbounded expansion.

---

## 18. Key Trade-offs

| Decision | Alternative | V1 rationale |
|---|---|---|
| PostgreSQL authoritative | Neo4j from day one | Current scale and transactional identity workflows do not justify graph-database complexity yet. |
| Bounded BFS | Unbounded traversal | Degree-100 expansion can explode by depth 3. |
| Shortest hop count for path search | Weighted path search | Direct volume clearly represents relationship strength, but Product has not defined how strengths combine across a multi-hop path. |
| Volume for same-hop presentation ranking | Ignore weight entirely | Uses the requirement's relationship-strength signal without changing BFS path semantics. |
| Human confirmation for ambiguous identity | Automatic merge | Reduces false canonicalization of financial identities. |
| Derived undirected view | Store only undirected transaction state | Preserves directional transaction evidence while satisfying the undirected-network requirement. |
| PostgreSQL-only V1 | PostgreSQL + Neo4j + Redis | Avoids projection/cache complexity until benchmarks justify it. |

---

## 19. Evolution Triggers

Only evolve when measured behavior justifies it.

### Add Redis when

hot-business reads are repetitive enough that caching materially improves latency/load.

### Add Neo4j serving when

bounded multi-hop/path workloads become frequent or PostgreSQL cannot meet latency/work-budget targets with maintainable queries.

### Improve identity automation when

evaluation data shows a sufficiently low false-match rate for carefully scoped automatic decisions.

### Materialize relationship projection when

the derived view becomes a measured read bottleneck.

---

## 20. Executable Slice vs. Production Architecture

The executable implementation is intentionally a reference vertical slice, not a complete distributed production platform.

### Implemented / substantially demonstrated

- Spring Boot APIs.
- PostgreSQL domain model.
- `NetworkBusiness` identity.
- source mappings.
- Add Vendor orchestration/idempotency.
- identity-resolution decisions.
- relationship assertions.
- relationship directional metrics/read view.
- bounded Java BFS for network traversal.
- bounded Java BFS for shortest-hop path search.
- authorization during traversal.
- network-view ranking using hop first and transaction volume as a secondary signal.
- optional AI-assisted candidate ranking with deterministic fallback.

### Simplified or production-only concepts

- real QBO outbound source-creation integration;
- production retry worker/backoff;
- transaction CDC/evidence processor;
- wall-clock traversal cancellation;
- production-scale indexed identity candidate retrieval;
- Redis;
- Neo4j projection.

This distinction is intentional:

> The code proves the domain boundaries and critical flows; the design explains how those boundaries evolve for production scale and reliability.
