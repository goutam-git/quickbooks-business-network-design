package com.quickbooks.biznetwork.merge.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "identity_merge_event")
@Getter
@Setter
@NoArgsConstructor
public class IdentityMergeEvent {

    @Id
    @GeneratedValue
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "merge_operation_id", nullable = false)
    private UUID mergeOperationId;

    @Column(name = "source_business_id", nullable = false)
    private UUID sourceBusinessId;

    @Column(name = "target_business_id", nullable = false)
    private UUID targetBusinessId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private MergeEventType eventType;

    @Column(name = "actor_id")
    private String actorId;

    @Column(name = "reason")
    private String reason;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private JsonNode metadata;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    public static IdentityMergeEvent of(UUID mergeOperationId, UUID source, UUID target, MergeEventType type, String actor, String reason) {
        IdentityMergeEvent e = new IdentityMergeEvent();
        e.mergeOperationId = mergeOperationId;
        e.sourceBusinessId = source;
        e.targetBusinessId = target;
        e.eventType = type;
        e.actorId = actor;
        e.reason = reason;
        return e;
    }
}
