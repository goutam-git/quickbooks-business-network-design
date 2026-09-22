package com.quickbooks.biznetwork.controller;

import com.quickbooks.biznetwork.relationship.dto.NetworkViewResponse;
import com.quickbooks.biznetwork.relationship.service.GraphTraversalService;
import com.quickbooks.biznetwork.resolution.dto.ResolveRequest;
import com.quickbooks.biznetwork.resolution.dto.ResolveResponse;
import com.quickbooks.biznetwork.resolution.service.IdentityResolutionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/businesses")
public class BusinessController {

    private final IdentityResolutionService resolutionService;
    private final GraphTraversalService traversalService;

    public BusinessController(IdentityResolutionService resolutionService, GraphTraversalService traversalService) {
        this.resolutionService = resolutionService;
        this.traversalService = traversalService;
    }

    /** FR4 -- read-only. Never creates a NetworkBusiness. */
    @PostMapping("/resolve")
    public ResponseEntity<ResolveResponse> resolve(@Valid @RequestBody ResolveRequest request,
                                                     @RequestHeader("X-Principal-Id") String principalId) {
        return ResponseEntity.ok(resolutionService.resolve(request, principalId));
    }

    /** FR1. */
    @GetMapping("/{id}/network")
    public ResponseEntity<NetworkViewResponse> networkView(@PathVariable("id") UUID id,
                                                             @RequestParam(defaultValue = "2") int depth,
                                                             @RequestParam(required = false, defaultValue = "0") int cursor,
                                                             @RequestHeader("X-Principal-Id") String principalId) {
        return ResponseEntity.ok(traversalService.networkView(id, depth, principalId, cursor));
    }
}
