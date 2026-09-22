package com.quickbooks.biznetwork.relationship.service;

import com.quickbooks.biznetwork.common.exception.ConflictException;
import com.quickbooks.biznetwork.identity.domain.NetworkBusiness;
import com.quickbooks.biznetwork.identity.service.CanonicalizationService;
import com.quickbooks.biznetwork.relationship.domain.AssertionStatus;
import com.quickbooks.biznetwork.relationship.domain.RelationshipAssertion;
import com.quickbooks.biznetwork.relationship.domain.RelationshipDirection;
import com.quickbooks.biznetwork.relationship.domain.RelationshipDirectionId;
import com.quickbooks.biznetwork.relationship.domain.SourceType;
import com.quickbooks.biznetwork.relationship.dto.CreateRelationshipRequest;
import com.quickbooks.biznetwork.relationship.dto.RelationshipResponse;
import com.quickbooks.biznetwork.relationship.repository.RelationshipAssertionRepository;
import com.quickbooks.biznetwork.relationship.repository.RelationshipDirectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class RelationshipService {

    private final RelationshipAssertionRepository assertionRepository;
    private final RelationshipDirectionRepository directionRepository;
    private final CanonicalizationService canonicalizationService;

    public RelationshipService(RelationshipAssertionRepository assertionRepository,
                                RelationshipDirectionRepository directionRepository,
                                CanonicalizationService canonicalizationService) {
        this.assertionRepository = assertionRepository;
        this.directionRepository = directionRepository;
        this.canonicalizationService = canonicalizationService;
    }

    public record CreateResult(RelationshipResponse response, boolean created) {
    }

    /** Invariant 19: canonicalize both endpoints before every ordinary write. */
    @Transactional
    public CreateResult createRelationship(CreateRelationshipRequest request, String actor) {
        NetworkBusiness a = canonicalizationService.resolveCanonical(request.businessAId());
        NetworkBusiness b = canonicalizationService.resolveCanonical(request.businessBId());

        requireActive(a);
        requireActive(b);

        if (a.getNetworkBusinessId().equals(b.getNetworkBusinessId())) {
            throw new ConflictException("SELF_RELATIONSHIP", "A business cannot have a relationship with itself");
        }

        UUID low = low(a.getNetworkBusinessId(), b.getNetworkBusinessId());
        UUID high = high(a.getNetworkBusinessId(), b.getNetworkBusinessId());

        var existing = assertionRepository.findByBusinessLowIdAndBusinessHighIdAndSourceTypeAndSourceReference(
                low, high, request.sourceType(), request.sourceReference());
        if (existing.isPresent()) {
            return new CreateResult(toResponse(existing.get()), false);
        }

        RelationshipAssertion assertion = RelationshipAssertion.newAssertion(low, high, request.sourceType(), request.sourceReference(), actor);
        assertion = assertionRepository.save(assertion);

        // Ensure a relationship_direction row exists so the pair is immediately
        // visible in business_relationship_view (volume 0 until real transactions land).
        ensureDirectionRow(low, high);

        return new CreateResult(toResponse(assertion), true);
    }

    private void ensureDirectionRow(UUID low, UUID high) {
        RelationshipDirectionId id = new RelationshipDirectionId(low, high);
        if (directionRepository.findById(id).isEmpty()) {
            directionRepository.save(new RelationshipDirection(id));
        }
    }

    private void requireActive(NetworkBusiness b) {
        if (!b.isActive()) {
            throw new ConflictException("IDENTITY_NOT_ACTIVE",
                    "Business " + b.getNetworkBusinessId() + " is not ACTIVE (status=" + b.getStatus() + ")");
        }
    }

    /**
     * Canonical pair ordering. Deliberately compares the canonical lowercase
     * hex STRING form rather than UUID#compareTo: Postgres's native `uuid`
     * type orders by raw byte value (equivalent to comparing the hex string),
     * while Java's UUID#compareTo compares mostSigBits/leastSigBits as SIGNED
     * longs -- which disagrees with Postgres for roughly half of all UUID
     * pairs. The business_relationship_view's LEAST/GREATEST aggregation
     * must land on the exact same low/high split as every assertion write,
     * or the view's join silently fails to match rows for the same pair.
     */
    public static UUID low(UUID a, UUID b) {
        return a.toString().compareTo(b.toString()) <= 0 ? a : b;
    }

    public static UUID high(UUID a, UUID b) {
        return a.toString().compareTo(b.toString()) <= 0 ? b : a;
    }

    private RelationshipResponse toResponse(RelationshipAssertion a) {
        return new RelationshipResponse(
                a.getRelationshipId(), a.getBusinessLowId(), a.getBusinessHighId(),
                a.getSourceType(), a.getSourceReference(), a.getStatus(), a.getCreatedAt());
    }
}
