package com.quickbooks.biznetwork.identity.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "network_business_access")
@Getter
@Setter
@NoArgsConstructor
public class NetworkBusinessAccess {

    @EmbeddedId
    private NetworkBusinessAccessId id;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false)
    private Permission permission;

    public NetworkBusinessAccess(NetworkBusinessAccessId id, Permission permission) {
        this.id = id;
        this.permission = permission;
    }
}
