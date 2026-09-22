package com.quickbooks.biznetwork.resolution.repository;

import com.quickbooks.biznetwork.resolution.domain.IdentityResolution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IdentityResolutionRepository extends JpaRepository<IdentityResolution, UUID> {
}
