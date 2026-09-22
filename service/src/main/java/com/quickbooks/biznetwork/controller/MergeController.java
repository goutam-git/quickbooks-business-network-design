package com.quickbooks.biznetwork.controller;

import com.quickbooks.biznetwork.merge.dto.MergeRequest;
import com.quickbooks.biznetwork.merge.dto.MergeResponse;
import com.quickbooks.biznetwork.merge.dto.ReverseMergeRequest;
import com.quickbooks.biznetwork.merge.dto.ReverseMergeResponse;
import com.quickbooks.biznetwork.merge.service.MergeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/business-identity/merges")
public class MergeController {

    private final MergeService mergeService;

    public MergeController(MergeService mergeService) {
        this.mergeService = mergeService;
    }

    @PostMapping
    public ResponseEntity<MergeResponse> confirmMerge(@Valid @RequestBody MergeRequest request,
                                                        @RequestHeader("X-Principal-Id") String principalId,
                                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mergeService.confirmMerge(request, principalId));
    }

    @PostMapping("/{mergeOperationId}/reverse")
    public ResponseEntity<ReverseMergeResponse> reverseMerge(@PathVariable UUID mergeOperationId,
                                                               @RequestHeader("X-Principal-Id") String principalId,
                                                               @RequestBody(required = false) ReverseMergeRequest request) {
        String reason = request != null ? request.reason() : null;
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mergeService.reverseMerge(mergeOperationId, principalId, reason));
    }
}
