package com.quickbooks.biznetwork.relationship.repository;

import com.quickbooks.biznetwork.relationship.domain.AssertionStatus;
import com.quickbooks.biznetwork.relationship.domain.RelationshipAssertion;
import com.quickbooks.biznetwork.relationship.domain.SourceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RelationshipAssertionRepository extends JpaRepository<RelationshipAssertion, UUID> {

    Optional<RelationshipAssertion> findByBusinessLowIdAndBusinessHighIdAndSourceTypeAndSourceReference(
            UUID businessLowId, UUID businessHighId, SourceType sourceType, String sourceReference);

    List<RelationshipAssertion> findByBusinessLowIdAndBusinessHighIdAndStatus(
            UUID businessLowId, UUID businessHighId, AssertionStatus status);

    List<RelationshipAssertion> findByBusinessLowIdOrBusinessHighId(UUID businessLowId, UUID businessHighId);
}
