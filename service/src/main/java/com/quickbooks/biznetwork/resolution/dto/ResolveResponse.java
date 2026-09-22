package com.quickbooks.biznetwork.resolution.dto;

import com.quickbooks.biznetwork.resolution.domain.ResolutionDecision;

import java.util.List;
import java.util.UUID;

public record ResolveResponse(
        UUID resolutionId,
        ResolutionDecision decision,
        UUID matchedBusinessId,
        List<CandidateDto> candidates
) {
}
