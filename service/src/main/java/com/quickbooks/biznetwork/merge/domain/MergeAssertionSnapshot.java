package com.quickbooks.biznetwork.merge.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merge_assertion_snapshot")
@Getter
@Setter
@NoArgsConstructor
@IdClass(MergeAssertionSnapshot.Pk.class)
public class MergeAssertionSnapshot {

    @Id
    @Column(name = "merge_operation_id")
    private UUID mergeOperationId;

    @Id
    @Column(name = "relationship_id")
    private UUID relationshipId;

    @Column(name = "business_low_id", nullable = false)
    private UUID businessLowId;

    @Column(name = "business_high_id", nullable = false)
    private UUID businessHighId;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "source_reference", nullable = false)
    private String sourceReference;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "retracted_by")
    private String retractedBy;

    @Column(name = "retracted_at")
    private Instant retractedAt;

    @CreationTimestamp
    @Column(name = "captured_at", updatable = false)
    private Instant capturedAt;

    public static class Pk implements java.io.Serializable {
        private UUID mergeOperationId;
        private UUID relationshipId;

        public Pk() {}
        public Pk(UUID mergeOperationId, UUID relationshipId) {
            this.mergeOperationId = mergeOperationId;
            this.relationshipId = relationshipId;
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return java.util.Objects.equals(mergeOperationId, pk.mergeOperationId) && java.util.Objects.equals(relationshipId, pk.relationshipId);
        }
        @Override public int hashCode() { return java.util.Objects.hash(mergeOperationId, relationshipId); }
    }
}
