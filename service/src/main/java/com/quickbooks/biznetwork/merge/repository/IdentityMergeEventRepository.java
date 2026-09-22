package com.quickbooks.biznetwork.merge.repository;

import com.quickbooks.biznetwork.merge.domain.IdentityMergeEvent;
import com.quickbooks.biznetwork.merge.domain.MergeEventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdentityMergeEventRepository extends JpaRepository<IdentityMergeEvent, UUID> {
    List<IdentityMergeEvent> findByMergeOperationIdOrderByCreatedAtAsc(UUID mergeOperationId);
    Optional<IdentityMergeEvent> findFirstByMergeOperationIdAndEventTypeOrderByCreatedAtDesc(UUID mergeOperationId, MergeEventType eventType);
}
