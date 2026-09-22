package com.quickbooks.biznetwork.identity.repository;

import com.quickbooks.biznetwork.identity.domain.NetworkBusinessAccess;
import com.quickbooks.biznetwork.identity.domain.NetworkBusinessAccessId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface NetworkBusinessAccessRepository extends JpaRepository<NetworkBusinessAccess, NetworkBusinessAccessId> {

    @Query("select a from NetworkBusinessAccess a where a.id.principalId = :principalId and a.id.networkBusinessId = :businessId")
    Optional<NetworkBusinessAccess> find(@Param("principalId") String principalId, @Param("businessId") UUID businessId);

    @Query("select a.id.networkBusinessId from NetworkBusinessAccess a where a.id.principalId = :principalId and a.id.networkBusinessId in :businessIds")
    Set<UUID> findVisibleAmong(@Param("principalId") String principalId, @Param("businessIds") Set<UUID> businessIds);

    List<NetworkBusinessAccess> findByIdPrincipalId(String principalId);
}
