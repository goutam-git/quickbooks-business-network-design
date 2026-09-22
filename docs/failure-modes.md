# Failure Modes

## Entity resolution service unavailable (Section 12)

`POST /businesses/resolve` and the resolver it wraps depend only on
PostgreSQL. If PostgreSQL itself is unavailable, resolution fails
closed — `503 ENTITY_RESOLUTION_UNAVAILABLE` conceptually (surfaced by
Spring's own datasource error handling in this slice) — **no
speculative business creation**.

## AI ranking service unavailable (`ai-resolution/ranking`)

This is the specific failure mode this iteration added, and it's the
one worth demonstrating live:

1. `IdentityResolutionService` always computes the deterministic
   candidate list and scores first — this never depends on
   `ai-resolution`.
2. It then calls `AiRankingClient.rank(...)`, which has a short
   connect/read timeout (`business-network.ai-resolution.connect-timeout-ms`
   / `read-timeout-ms`, defaults 1s/4s).
3. On **any** failure — connection refused, timeout, 5xx, malformed
   response — `AiRankingClient` catches it, logs a warning, and returns
   `Optional.empty()`.
4. `IdentityResolutionService` treats an empty result as "proceed
   deterministic-only": `blendWeight` effectively becomes `0`, the
   `method` field stays `NAME_SIMILARITY_V1` (no `+AI_RANKING(...)`
   suffix), and the response is *exactly* what it would have been with
   `ai-resolution.enabled: false`.

**To see this live:** run `docker compose stop ai-resolution`, then
re-run Postman request 15. The response still returns a decision;
`evidence.aiBackend` is simply absent from every candidate, and the
persisted `identity_resolution.method` column shows the fallback.

## AI ranking service reachable but wrong (garbage response)

Handled the same way as unavailable — `AiRankingClient` only trusts a
well-formed `AiRankResponse`; anything else throws inside
`RestTemplate` deserialization, which is caught by the same
`RestClientException` handler.

## Merge reversal blocked (A20)

`POST /business-identity/merges/{id}/reverse` returns
`409 MERGE_ALREADY_SUPERSEDED` if the merge's target has itself been
superseded by a later merge since, and `409 MERGE_NOT_REVERSIBLE` if
the retained snapshot provenance is insufficient. Both are deliberate,
documented refusals, not bugs — see `MergeService` and Section 8.4's
own "Limitation, stated explicitly rather than implied" note.

## Authorization denial never distinguishable from absence (Section 14)

Every authorization failure in `network-query` and merge endpoints
surfaces as `404 NOT_FOUND`, never `403`. This is intentional (see
`AuthorizationService`'s javadoc) — the alternative would let a caller
learn a business exists just by getting a different status code for
it.
