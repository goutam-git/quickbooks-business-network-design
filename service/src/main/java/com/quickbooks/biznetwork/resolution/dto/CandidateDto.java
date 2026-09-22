package com.quickbooks.biznetwork.resolution.dto;

import java.util.UUID;

public record CandidateDto(
        UUID networkBusinessId,
        String displayName,
        int rank,
        double score,
        String evidence
) {
}
