package com.quickbooks.biznetwork.relationship.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "relationship_assertion")
@Getter
@Setter
@NoArgsConstructor
public class RelationshipAssertion {

    @Id
    @GeneratedValue
    @Column(name = "relationship_id")
    private UUID relationshipId;

    @Column(name = "business_low_id", nullable = false)
    private UUID businessLowId;

    @Column(name = "business_high_id", nullable = false)
    private UUID businessHighId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;

    @Column(name = "source_reference", nullable = false)
    private String sourceReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AssertionStatus status;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "retracted_by")
    private String retractedBy;

    @Column(name = "retracted_at")
    private Instant retractedAt;

    @Column(name = "retraction_reason")
    private String retractionReason;

    public static RelationshipAssertion newAssertion(UUID low, UUID high, SourceType sourceType, String sourceReference, String createdBy) {
        RelationshipAssertion a = new RelationshipAssertion();
        a.businessLowId = low;
        a.businessHighId = high;
        a.sourceType = sourceType;
        a.sourceReference = sourceReference;
        a.status = AssertionStatus.ACTIVE;
        a.createdBy = createdBy;
        return a;
    }

    public void retract(String actor, String reason) {
        this.status = AssertionStatus.RETRACTED;
        this.retractedBy = actor;
        this.retractedAt = Instant.now();
        this.retractionReason = reason;
    }
}
