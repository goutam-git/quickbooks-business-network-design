package com.quickbooks.biznetwork.merge.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "merge_direction_snapshot")
@Getter
@Setter
@NoArgsConstructor
public class MergeDirectionSnapshot {

    @EmbeddedId
    private MergeDirectionSnapshotId id;

    @Column(name = "transaction_count", nullable = false)
    private long transactionCount;

    @Column(name = "transaction_amount", nullable = false)
    private BigDecimal transactionAmount;

    @Column(name = "last_transaction_at")
    private Instant lastTransactionAt;

    @CreationTimestamp
    @Column(name = "captured_at", updatable = false)
    private Instant capturedAt;

    public MergeDirectionSnapshot(MergeDirectionSnapshotId id, long count, BigDecimal amount, Instant lastTxAt) {
        this.id = id;
        this.transactionCount = count;
        this.transactionAmount = amount;
        this.lastTransactionAt = lastTxAt;
    }
}
