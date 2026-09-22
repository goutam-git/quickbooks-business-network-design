package com.quickbooks.biznetwork.merge.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record MergeRequest(
        @NotNull UUID sourceBusinessId,
        @NotNull UUID targetBusinessId,
        String reason
) {
}
