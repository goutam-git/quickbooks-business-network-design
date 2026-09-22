package com.quickbooks.biznetwork.merge.dto;

import java.util.UUID;

public record MergeResponse(
        UUID mergeOperationId,
        UUID resolvedSourceBusinessId,
        UUID resolvedTargetBusinessId,
        String status
) {
}
