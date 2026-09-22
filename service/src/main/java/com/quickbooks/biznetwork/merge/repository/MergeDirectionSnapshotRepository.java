package com.quickbooks.biznetwork.merge.repository;

import com.quickbooks.biznetwork.merge.domain.MergeDirectionSnapshot;
import com.quickbooks.biznetwork.merge.domain.MergeDirectionSnapshotId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MergeDirectionSnapshotRepository extends JpaRepository<MergeDirectionSnapshot, MergeDirectionSnapshotId> {
    @Query("select s from MergeDirectionSnapshot s where s.id.mergeOperationId = :opId")
    List<MergeDirectionSnapshot> findByMergeOperationId(@Param("opId") UUID opId);
}
