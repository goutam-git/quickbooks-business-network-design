package com.quickbooks.biznetwork.merge.dto;

import java.util.UUID;

public record ReverseMergeResponse(
        UUID mergeOperationId,
        UUID restoredBusinessId,
        String status
) {
}
