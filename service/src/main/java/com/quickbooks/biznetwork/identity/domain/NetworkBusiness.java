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
@Table(name = "network_business")
@Getter
@Setter
@NoArgsConstructor
public class NetworkBusiness {

    @Id
    @GeneratedValue
    @Column(name = "network_business_id")
    private UUID networkBusinessId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private NetworkBusinessStatus status;

    @Column(name = "canonical_business_id")
    private UUID canonicalBusinessId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private Long version;

    public NetworkBusiness(String displayName, NetworkBusinessStatus status) {
        this.displayName = displayName;
        this.status = status;
    }

    public boolean isActive() {
        return status == NetworkBusinessStatus.ACTIVE;
    }
}
