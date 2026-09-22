package com.quickbooks.biznetwork.relationship.dto;

import java.util.List;
import java.util.UUID;

public record PathResponse(
        List<UUID> path,
        int hopCount,
        List<EdgeDto> edges
) {
}
