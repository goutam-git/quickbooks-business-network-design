package com.quickbooks.biznetwork.identity.service;

import com.quickbooks.biznetwork.common.exception.NotFoundException;
import com.quickbooks.biznetwork.identity.domain.NetworkBusiness;
import com.quickbooks.biznetwork.identity.domain.NetworkBusinessStatus;
import com.quickbooks.biznetwork.identity.repository.NetworkBusinessRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Invariant 19: every ordinary write to relationship_direction /
 * relationship_assertion first resolves business IDs to their CURRENT
 * canonical form. This guards against a write racing an in-flight merge.
 */
@Service
public class CanonicalizationService {

    private static final int MAX_CHAIN_HOPS = 32; // defensive cap; cycles are prevented at merge time

    private final NetworkBusinessRepository repository;

    public CanonicalizationService(NetworkBusinessRepository repository) {
        this.repository = repository;
    }

    /** Follows canonical_business_id pointers until an ACTIVE (or non-SUPERSEDED) root is reached. */
    public NetworkBusiness resolveCanonical(UUID businessId) {
        NetworkBusiness current = repository.findById(businessId)
                .orElseThrow(() -> new NotFoundException("NOT_FOUND", "Business not found: " + businessId));

        int hops = 0;
        while (current.getStatus() == NetworkBusinessStatus.SUPERSEDED && current.getCanonicalBusinessId() != null) {
            if (++hops > MAX_CHAIN_HOPS) {
                throw new IllegalStateException("Canonicalization chain exceeded max hops for " + businessId);
            }
            current = repository.findById(current.getCanonicalBusinessId())
                    .orElseThrow(() -> new NotFoundException("NOT_FOUND", "Canonical target not found for chain from: " + businessId));
        }
        return current;
    }
}
