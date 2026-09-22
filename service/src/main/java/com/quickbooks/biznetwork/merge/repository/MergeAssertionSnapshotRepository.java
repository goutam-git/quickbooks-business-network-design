package com.quickbooks.biznetwork.merge.repository;

import com.quickbooks.biznetwork.merge.domain.MergeAssertionSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MergeAssertionSnapshotRepository extends JpaRepository<MergeAssertionSnapshot, MergeAssertionSnapshot.Pk> {
    List<MergeAssertionSnapshot> findByMergeOperationId(UUID mergeOperationId);
}
