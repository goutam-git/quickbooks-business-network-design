package com.quickbooks.biznetwork.relationship.dto;

import java.util.List;

public record NetworkViewResponse(
        List<NodeDto> nodes,
        List<EdgeDto> edges,
        String nextCursor,
        boolean truncated,
        BudgetMetadata budget
) {
}
