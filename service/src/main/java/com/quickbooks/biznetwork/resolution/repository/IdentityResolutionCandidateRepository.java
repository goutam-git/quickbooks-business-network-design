package com.quickbooks.biznetwork.resolution.repository;

import com.quickbooks.biznetwork.resolution.domain.IdentityResolutionCandidate;
import com.quickbooks.biznetwork.resolution.domain.IdentityResolutionCandidateId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IdentityResolutionCandidateRepository extends JpaRepository<IdentityResolutionCandidate, IdentityResolutionCandidateId> {
    List<IdentityResolutionCandidate> findById_ResolutionIdOrderByRank(UUID resolutionId);
}
