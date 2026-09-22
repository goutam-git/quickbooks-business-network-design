package com.quickbooks.biznetwork.relationship.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record EdgeDto(
        UUID businessLowId,
        UUID businessHighId,
        BigDecimal transactionAmount,
        long transactionCount,
        Instant lastTransactionAt
) {
}
