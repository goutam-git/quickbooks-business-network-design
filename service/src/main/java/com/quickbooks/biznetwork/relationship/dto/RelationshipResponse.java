package com.quickbooks.biznetwork.relationship.dto;

import com.quickbooks.biznetwork.relationship.domain.AssertionStatus;
import com.quickbooks.biznetwork.relationship.domain.SourceType;

import java.time.Instant;
import java.util.UUID;

public record RelationshipResponse(
        UUID relationshipId,
        UUID businessLowId,
        UUID businessHighId,
        SourceType sourceType,
        String sourceReference,
        AssertionStatus status,
        Instant createdAt
) {
}
