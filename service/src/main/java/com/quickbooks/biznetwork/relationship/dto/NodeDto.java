package com.quickbooks.biznetwork.relationship.dto;

import java.util.UUID;

public record NodeDto(
        UUID networkBusinessId,
        String displayName,
        int hopDistance
) {
}
