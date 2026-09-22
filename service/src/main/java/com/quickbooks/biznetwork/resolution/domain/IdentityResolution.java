package com.quickbooks.biznetwork.resolution.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "identity_resolution")
@Getter
@Setter
@NoArgsConstructor
public class IdentityResolution {

    @Id
    @GeneratedValue
    @Column(name = "resolution_id")
    private UUID resolutionId;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "input_descriptor", columnDefinition = "jsonb", nullable = false)
    private JsonNode inputDescriptor;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false)
    private ResolutionDecision decision;

    @Column(name = "selected_business_id")
    private UUID selectedBusinessId;

    @Column(name = "method", nullable = false)
    private String method;

    @Column(name = "actor_id")
    private String actorId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
