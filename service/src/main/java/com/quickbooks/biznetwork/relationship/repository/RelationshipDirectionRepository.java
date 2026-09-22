package com.quickbooks.biznetwork.relationship.repository;

import com.quickbooks.biznetwork.relationship.domain.RelationshipDirection;
import com.quickbooks.biznetwork.relationship.domain.RelationshipDirectionId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RelationshipDirectionRepository extends JpaRepository<RelationshipDirection, RelationshipDirectionId> {
}
