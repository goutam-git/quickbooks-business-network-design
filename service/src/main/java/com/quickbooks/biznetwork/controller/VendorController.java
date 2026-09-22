package com.quickbooks.biznetwork.controller;

import com.quickbooks.biznetwork.vendor.dto.AddVendorRequest;
import com.quickbooks.biznetwork.vendor.dto.AddVendorResponse;
import com.quickbooks.biznetwork.vendor.dto.ConfirmVendorRequest;
import com.quickbooks.biznetwork.vendor.domain.AddOperationState;
import com.quickbooks.biznetwork.vendor.service.AddVendorService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/businesses/{ownerBusinessId}/vendors")
public class VendorController {

    private final AddVendorService addVendorService;

    public VendorController(AddVendorService addVendorService) {
        this.addVendorService = addVendorService;
    }

    /** FR3/FR4a -- idempotent side-effecting Add Vendor command. */
    @PostMapping
    public ResponseEntity<AddVendorResponse> addVendor(@PathVariable UUID ownerBusinessId,
                                                         @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                         @RequestHeader("X-Principal-Id") String principalId,
                                                         @Valid @RequestBody AddVendorRequest request) {
        AddVendorResponse response = addVendorService.addVendor(ownerBusinessId, idempotencyKey, request, principalId);
        HttpStatus status = switch (response.state()) {
            case RELATIONSHIP_CREATED -> HttpStatus.CREATED;
            case AWAITING_CONFIRMATION -> HttpStatus.OK;
            case SOURCE_PENDING -> HttpStatus.ACCEPTED;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(response);
    }

    /** Resumes an AWAITING_CONFIRMATION operation with USE_EXISTING or CREATE_NEW. */
    @PostMapping("/confirm")
    public ResponseEntity<AddVendorResponse> confirm(@PathVariable UUID ownerBusinessId,
                                                       @RequestHeader("X-Principal-Id") String principalId,
                                                       @Valid @RequestBody ConfirmVendorRequest request) {
        AddVendorResponse response = addVendorService.confirm(ownerBusinessId, request, principalId);
        HttpStatus status = response.state() == AddOperationState.RELATIONSHIP_CREATED ? HttpStatus.CREATED : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(response);
    }
}
