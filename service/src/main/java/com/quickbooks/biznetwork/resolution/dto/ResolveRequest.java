package com.quickbooks.biznetwork.resolution.dto;

import com.quickbooks.biznetwork.identity.domain.SourceEntityType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Descriptor fields available from the caller. No Tax ID / platform
 * identifier is assumed unless actually supplied (Section 13.1).
 */
public record ResolveRequest(
        @NotBlank String sourceSystem,
        @NotNull SourceEntityType sourceEntityType,
        String sourceEntityId,
        @NotBlank String displayName,
        String taxId,
        String email,
        String phone
) {
}
