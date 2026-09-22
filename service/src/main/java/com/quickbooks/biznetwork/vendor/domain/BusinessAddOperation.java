package com.quickbooks.biznetwork.vendor.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "business_add_operation")
@Getter
@Setter
@NoArgsConstructor
public class BusinessAddOperation {

    @Id
    @GeneratedValue
    @Column(name = "operation_id")
    private UUID operationId;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "owner_business_id", nullable = false)
    private UUID ownerBusinessId;

    @Column(name = "network_business_id")
    private UUID networkBusinessId;

    @Column(name = "resolution_id")
    private UUID resolutionId;

    @Column(name = "relationship_id")
    private UUID relationshipId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private AddOperationState state;

    @Column(name = "last_error_code")
    private String lastErrorCode;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "request_payload", columnDefinition = "jsonb")
    private JsonNode requestPayload;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
