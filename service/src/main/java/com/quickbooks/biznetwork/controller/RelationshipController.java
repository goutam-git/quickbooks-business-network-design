package com.quickbooks.biznetwork.controller;

import com.quickbooks.biznetwork.relationship.dto.CreateRelationshipRequest;
import com.quickbooks.biznetwork.relationship.dto.PathResponse;
import com.quickbooks.biznetwork.relationship.dto.RelationshipResponse;
import com.quickbooks.biznetwork.relationship.service.GraphTraversalService;
import com.quickbooks.biznetwork.relationship.service.RelationshipService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/relationships")
public class RelationshipController {

    private final RelationshipService relationshipService;
    private final GraphTraversalService traversalService;

    public RelationshipController(RelationshipService relationshipService, GraphTraversalService traversalService) {
        this.relationshipService = relationshipService;
        this.traversalService = traversalService;
    }

    /** FR3 (lower-level command). Requires two ACTIVE resolved NetworkBusinessIds.
     * V1 simplification: idempotency is enforced via the (low,high,sourceType,sourceReference)
     * unique constraint on relationship_assertion rather than a separate Idempotency-Key
     * ledger (unlike the heavier Add Vendor orchestration, this call has no multi-step
     * side effects to resume) -- the header is still accepted/logged for API symmetry. */
    @PostMapping
    public ResponseEntity<RelationshipResponse> create(@Valid @RequestBody CreateRelationshipRequest request,
                                                         @RequestHeader("X-Principal-Id") String principalId,
                                                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var result = relationshipService.createRelationship(request, principalId);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    /** FR2 -- shortest path by hop count. */
    @GetMapping("/path")
    public ResponseEntity<PathResponse> path(@RequestParam UUID from,
                                              @RequestParam UUID to,
                                              @RequestParam(required = false) Integer maxDepth,
                                              @RequestHeader("X-Principal-Id") String principalId) {
        return ResponseEntity.ok(traversalService.shortestPath(from, to, principalId, maxDepth));
    }
}
