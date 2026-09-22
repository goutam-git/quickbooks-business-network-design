package com.quickbooks.biznetwork.vendor.repository;

import com.quickbooks.biznetwork.vendor.domain.BusinessAddOperation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BusinessAddOperationRepository extends JpaRepository<BusinessAddOperation, UUID> {
    Optional<BusinessAddOperation> findByIdempotencyKey(String idempotencyKey);
}
