package com.quickbooks.biznetwork.identity.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "source_business_ref")
@Getter
@Setter
@NoArgsConstructor
public class SourceBusinessRef {

    @EmbeddedId
    private SourceBusinessRefId id;

    @Column(name = "network_business_id", nullable = false)
    private UUID networkBusinessId;

    @Column(name = "source_display_name", nullable = false)
    private String sourceDisplayName;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    public SourceBusinessRef(SourceBusinessRefId id, UUID networkBusinessId, String sourceDisplayName) {
        this.id = id;
        this.networkBusinessId = networkBusinessId;
        this.sourceDisplayName = sourceDisplayName;
    }
}
