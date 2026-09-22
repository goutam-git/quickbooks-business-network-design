package com.quickbooks.biznetwork.relationship.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One row of the derived business_relationship_view, oriented from a
 * given business toward its counterpart. */
public record NeighborRow(
        UUID counterpartId,
        BigDecimal volumeAmount,
        long transactionCount,
        Instant lastTransactionAt
) {
}
