package com.quickbooks.biznetwork.resolution.domain;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class IdentityResolutionCandidateId implements Serializable {
    private UUID resolutionId;
    private UUID candidateBusinessId;
}
