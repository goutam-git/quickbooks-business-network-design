package com.quickbooks.biznetwork.vendor.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ConfirmVendorRequest(
        @NotNull UUID operationId,
        @NotNull ConfirmDecision decision,
        UUID selectedNetworkBusinessId // required when decision == USE_EXISTING
) {
}
