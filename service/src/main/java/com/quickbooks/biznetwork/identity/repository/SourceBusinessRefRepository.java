package com.quickbooks.biznetwork.identity.repository;

import com.quickbooks.biznetwork.identity.domain.SourceBusinessRef;
import com.quickbooks.biznetwork.identity.domain.SourceBusinessRefId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SourceBusinessRefRepository extends JpaRepository<SourceBusinessRef, SourceBusinessRefId> {
    List<SourceBusinessRef> findByNetworkBusinessId(UUID networkBusinessId);
}
