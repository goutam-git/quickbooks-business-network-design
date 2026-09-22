package com.quickbooks.biznetwork.merge.domain;

import com.quickbooks.biznetwork.relationship.domain.Direction;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
public class MergeDirectionSnapshotId implements Serializable {
    private UUID mergeOperationId;
    private UUID businessId;
    private UUID counterpartyBusinessId;
    @Enumerated(EnumType.STRING)
    private Direction direction;
}
