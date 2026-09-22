package com.quickbooks.biznetwork.identity.service;

import com.quickbooks.biznetwork.common.exception.NotFoundException;
import com.quickbooks.biznetwork.identity.domain.Permission;
import com.quickbooks.biznetwork.identity.repository.NetworkBusinessAccessRepository;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * Section 14: authorization is evaluated during traversal, and a business
 * the principal cannot see must never be distinguishable from one that does
 * not exist -- so denial always surfaces as 404 NOT_FOUND, never 403.
 */
@Service
public class AuthorizationService {

    private final NetworkBusinessAccessRepository accessRepository;

    public AuthorizationService(NetworkBusinessAccessRepository accessRepository) {
        this.accessRepository = accessRepository;
    }

    public boolean canSee(String principalId, UUID businessId) {
        return accessRepository.find(principalId, businessId).isPresent();
    }

    public boolean hasPermission(String principalId, UUID businessId, Permission required) {
        return accessRepository.find(principalId, businessId)
                .map(a -> a.getPermission().atLeast(required))
                .orElse(false);
    }

    /** Throws 404 (never 403) if the principal cannot at least VIEW the business. */
    public void requireView(String principalId, UUID businessId) {
        if (!canSee(principalId, businessId)) {
            throw new NotFoundException("NOT_FOUND", "Business not found or not visible: " + businessId);
        }
    }

    /** Throws 404 (never 403) if the principal lacks MANAGE on the business. */
    public void requireManage(String principalId, UUID businessId) {
        if (!hasPermission(principalId, businessId, Permission.MANAGE)) {
            throw new NotFoundException("NOT_FOUND", "Business not found, not visible, or insufficient permission: " + businessId);
        }
    }

    /** A17/invariant 21: given a candidate set of business IDs, returns the
     * subset visible to the principal -- used to gate traversal expansion. */
    public Set<UUID> visibleAmong(String principalId, Set<UUID> candidateIds) {
        if (candidateIds.isEmpty()) return Set.of();
        return accessRepository.findVisibleAmong(principalId, candidateIds);
    }
}
