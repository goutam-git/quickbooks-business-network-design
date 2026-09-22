package com.quickbooks.biznetwork.vendor.dto;

import com.quickbooks.biznetwork.resolution.domain.ResolutionDecision;
import com.quickbooks.biznetwork.resolution.dto.CandidateDto;
import com.quickbooks.biznetwork.vendor.domain.AddOperationState;

import java.util.List;
import java.util.UUID;

public record AddVendorResponse(
        UUID operationId,
        AddOperationState state,
        UUID networkBusinessId,
        UUID relationshipId,
        ResolutionDecision resolutionDecision,
        List<CandidateDto> candidates
) {
}
