package com.quickbooks.biznetwork.relationship.dto;

import com.quickbooks.biznetwork.relationship.domain.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateRelationshipRequest(
        @NotNull UUID businessAId,
        @NotNull UUID businessBId,
        @NotNull SourceType sourceType,
        @NotBlank String sourceReference
) {
}
