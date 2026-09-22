package com.quickbooks.biznetwork.identity.repository;

import com.quickbooks.biznetwork.identity.domain.NetworkBusiness;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NetworkBusinessRepository extends JpaRepository<NetworkBusiness, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select nb from NetworkBusiness nb where nb.networkBusinessId = :id")
    Optional<NetworkBusiness> lockById(@Param("id") UUID id);

    @Query("select nb from NetworkBusiness nb where lower(nb.displayName) like lower(concat('%', :term, '%')) and nb.status = 'ACTIVE'")
    List<NetworkBusiness> searchByNameContaining(@Param("term") String term);
}
