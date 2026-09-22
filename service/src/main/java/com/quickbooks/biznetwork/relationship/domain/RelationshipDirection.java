package com.quickbooks.biznetwork.relationship.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "relationship_direction")
@Getter
@Setter
@NoArgsConstructor
public class RelationshipDirection {

    @EmbeddedId
    private RelationshipDirectionId id;

    @Column(name = "transaction_count", nullable = false)
    private long transactionCount;

    @Column(name = "transaction_amount", nullable = false)
    private BigDecimal transactionAmount;

    @Column(name = "last_transaction_at")
    private Instant lastTransactionAt;

    @Version
    @Column(name = "version")
    private Long version;

    public RelationshipDirection(RelationshipDirectionId id) {
        this.id = id;
        this.transactionCount = 0;
        this.transactionAmount = BigDecimal.ZERO;
    }
}
