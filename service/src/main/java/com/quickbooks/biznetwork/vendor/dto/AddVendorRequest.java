package com.quickbooks.biznetwork.vendor.dto;

import com.quickbooks.biznetwork.identity.domain.SourceEntityType;
import com.quickbooks.biznetwork.relationship.domain.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AddVendorRequest(
        @NotBlank String sourceSystem,
        @NotNull SourceEntityType sourceEntityType,
        String sourceEntityId,
        @NotBlank String displayName,
        String taxId,
        String email,
        String phone,
        @NotNull SourceType relationshipSourceType,
        @NotBlank String relationshipSourceReference
) {
}
