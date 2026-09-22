package com.quickbooks.biznetwork.merge.service;

import com.quickbooks.biznetwork.common.exception.ConflictException;
import com.quickbooks.biznetwork.common.exception.NotFoundException;
import com.quickbooks.biznetwork.identity.domain.NetworkBusiness;
import com.quickbooks.biznetwork.identity.domain.NetworkBusinessStatus;
import com.quickbooks.biznetwork.identity.repository.NetworkBusinessRepository;
import com.quickbooks.biznetwork.identity.service.AuthorizationService;
import com.quickbooks.biznetwork.identity.service.CanonicalizationService;
import com.quickbooks.biznetwork.merge.domain.*;
import com.quickbooks.biznetwork.merge.dto.MergeRequest;
import com.quickbooks.biznetwork.merge.dto.MergeResponse;
import com.quickbooks.biznetwork.merge.dto.ReverseMergeResponse;
import com.quickbooks.biznetwork.merge.repository.IdentityMergeEventRepository;
import com.quickbooks.biznetwork.merge.repository.MergeAssertionSnapshotRepository;
import com.quickbooks.biznetwork.merge.repository.MergeDirectionSnapshotRepository;
import com.quickbooks.biznetwork.relationship.domain.AssertionStatus;
import com.quickbooks.biznetwork.relationship.domain.Direction;
import com.quickbooks.biznetwork.relationship.domain.RelationshipAssertion;
import com.quickbooks.biznetwork.relationship.domain.RelationshipDirection;
import com.quickbooks.biznetwork.relationship.domain.RelationshipDirectionId;
import com.quickbooks.biznetwork.relationship.repository.RelationshipAssertionRepository;
import com.quickbooks.biznetwork.relationship.repository.RelationshipDirectionRepository;
import com.quickbooks.biznetwork.relationship.service.RelationshipService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Section 8.4. V1 vertical slice runs consolidation SYNCHRONOUSLY inside the
 * same transaction as merge confirmation (Section 17 collapses the async
 * consolidator into the request thread) -- the append-only event trail
 * (MERGE_CONFIRMED -> CONSOLIDATION_COMPLETED) is still recorded so the
 * audit shape matches the target architecture.
 *
 * Deliberate V1 simplification: source_business_ref rows are NOT rewritten
 * during consolidation. Identity lookups always canonicalize through
 * CanonicalizationService, so a source ref pointing at a SUPERSEDED
 * business still resolves correctly. This keeps merge reversal of source
 * mappings a true no-op instead of requiring a third snapshot table.
 */
@Service
public class MergeService {

    private final NetworkBusinessRepository businessRepository;
    private final CanonicalizationService canonicalizationService;
    private final AuthorizationService authorizationService;
    private final IdentityMergeEventRepository eventRepository;
    private final MergeDirectionSnapshotRepository directionSnapshotRepository;
    private final MergeAssertionSnapshotRepository assertionSnapshotRepository;
    private final RelationshipAssertionRepository assertionRepository;
    private final RelationshipDirectionRepository directionRepository;

    public MergeService(NetworkBusinessRepository businessRepository,
                         CanonicalizationService canonicalizationService,
                         AuthorizationService authorizationService,
                         IdentityMergeEventRepository eventRepository,
                         MergeDirectionSnapshotRepository directionSnapshotRepository,
                         MergeAssertionSnapshotRepository assertionSnapshotRepository,
                         RelationshipAssertionRepository assertionRepository,
                         RelationshipDirectionRepository directionRepository) {
        this.businessRepository = businessRepository;
        this.canonicalizationService = canonicalizationService;
        this.authorizationService = authorizationService;
        this.eventRepository = eventRepository;
        this.directionSnapshotRepository = directionSnapshotRepository;
        this.assertionSnapshotRepository = assertionSnapshotRepository;
        this.assertionRepository = assertionRepository;
        this.directionRepository = directionRepository;
    }

    @Transactional
    public MergeResponse confirmMerge(MergeRequest request, String actor) {
        authorizationService.requireManage(actor, request.sourceBusinessId());
        authorizationService.requireManage(actor, request.targetBusinessId());

        // 1. Deterministic lock order to avoid deadlocks under overlapping merges.
        UUID first = RelationshipService.low(request.sourceBusinessId(), request.targetBusinessId());
        UUID second = RelationshipService.high(request.sourceBusinessId(), request.targetBusinessId());
        businessRepository.lockById(first).orElseThrow(() -> new NotFoundException("NOT_FOUND", "Business not found: " + first));
        businessRepository.lockById(second).orElseThrow(() -> new NotFoundException("NOT_FOUND", "Business not found: " + second));

        // 2. Re-resolve canonical roots AFTER locking.
        NetworkBusiness source = canonicalizationService.resolveCanonical(request.sourceBusinessId());
        NetworkBusiness target = canonicalizationService.resolveCanonical(request.targetBusinessId());

        if (source.getNetworkBusinessId().equals(target.getNetworkBusinessId())) {
            throw new ConflictException("MERGE_NOOP", "Source and target already resolve to the same canonical identity: " + target.getNetworkBusinessId());
        }
        if (source.getStatus() != NetworkBusinessStatus.ACTIVE || target.getStatus() != NetworkBusinessStatus.ACTIVE) {
            throw new ConflictException("IDENTITY_NOT_ACTIVE", "Both identities must be ACTIVE to merge");
        }

        UUID mergeOperationId = UUID.randomUUID();

        // 3. Append-only audit event.
        eventRepository.save(IdentityMergeEvent.of(mergeOperationId, source.getNetworkBusinessId(), target.getNetworkBusinessId(),
                MergeEventType.MERGE_CONFIRMED, actor, request.reason()));

        // 4. Snapshot pre-merge directional + assertion state for `source`.
        snapshotDirection(mergeOperationId, source.getNetworkBusinessId());
        snapshotAssertions(mergeOperationId, source.getNetworkBusinessId());

        // 5. Supersede source.
        source.setStatus(NetworkBusinessStatus.SUPERSEDED);
        source.setCanonicalBusinessId(target.getNetworkBusinessId());
        businessRepository.save(source);

        // Chain normalization: anything pointing at `source` now points at `target`.
        businessRepository.findAll().stream()
                .filter(b -> source.getNetworkBusinessId().equals(b.getCanonicalBusinessId()))
                .forEach(b -> {
                    b.setCanonicalBusinessId(target.getNetworkBusinessId());
                    businessRepository.save(b);
                });

        eventRepository.save(IdentityMergeEvent.of(mergeOperationId, source.getNetworkBusinessId(), target.getNetworkBusinessId(),
                MergeEventType.CONSOLIDATION_STARTED, actor, null));

        // 6. Consolidate (synchronous).
        consolidateAssertions(source.getNetworkBusinessId(), target.getNetworkBusinessId(), actor);
        consolidateDirection(source.getNetworkBusinessId(), target.getNetworkBusinessId());

        eventRepository.save(IdentityMergeEvent.of(mergeOperationId, source.getNetworkBusinessId(), target.getNetworkBusinessId(),
                MergeEventType.CONSOLIDATION_COMPLETED, actor, null));

        return new MergeResponse(mergeOperationId, source.getNetworkBusinessId(), target.getNetworkBusinessId(), "CONSOLIDATION_COMPLETED");
    }

    @Transactional
    public ReverseMergeResponse reverseMerge(UUID mergeOperationId, String actor, String reason) {
        IdentityMergeEvent confirmedEvent = eventRepository
                .findFirstByMergeOperationIdAndEventTypeOrderByCreatedAtDesc(mergeOperationId, MergeEventType.MERGE_CONFIRMED)
                .orElseThrow(() -> new NotFoundException("NOT_FOUND", "Merge operation not found: " + mergeOperationId));

        UUID sourceId = confirmedEvent.getSourceBusinessId();
        UUID targetId = confirmedEvent.getTargetBusinessId();

        authorizationService.requireManage(actor, sourceId);
        authorizationService.requireManage(actor, targetId);

        UUID first = RelationshipService.low(sourceId, targetId);
        UUID second = RelationshipService.high(sourceId, targetId);
        businessRepository.lockById(first);
        businessRepository.lockById(second);

        NetworkBusiness source = businessRepository.findById(sourceId)
                .orElseThrow(() -> new NotFoundException("NOT_FOUND", "Business not found: " + sourceId));
        NetworkBusiness target = businessRepository.findById(targetId)
                .orElseThrow(() -> new NotFoundException("NOT_FOUND", "Business not found: " + targetId));

        // A20 / invariant 28: blocked (not cascaded) if target has itself been superseded since.
        if (target.getStatus() != NetworkBusinessStatus.ACTIVE) {
            throw new ConflictException("MERGE_ALREADY_SUPERSEDED",
                    "Merge target " + targetId + " has itself been superseded since this merge; reversal is blocked, not cascaded");
        }
        if (source.getStatus() != NetworkBusinessStatus.SUPERSEDED || !targetId.equals(source.getCanonicalBusinessId())) {
            throw new ConflictException("MERGE_NOT_REVERSIBLE",
                    "Source " + sourceId + " is not currently superseded by " + targetId + " (possibly already reversed or superseded again)");
        }

        List<MergeDirectionSnapshot> directionSnapshots = directionSnapshotRepository.findByMergeOperationId(mergeOperationId);
        List<MergeAssertionSnapshot> assertionSnapshots = assertionSnapshotRepository.findByMergeOperationId(mergeOperationId);
        if (directionSnapshots.isEmpty() && assertionSnapshots.isEmpty()) {
            throw new ConflictException("MERGE_NOT_REVERSIBLE", "No retained provenance for merge operation " + mergeOperationId);
        }

        eventRepository.save(IdentityMergeEvent.of(mergeOperationId, sourceId, targetId, MergeEventType.MERGE_REVERSE_REQUESTED, actor, reason));

        // Restore relationship_assertion rows to their pre-merge endpoints/status.
        for (MergeAssertionSnapshot snap : assertionSnapshots) {
            assertionRepository.findById(snap.getRelationshipId()).ifPresent(a -> {
                a.setBusinessLowId(snap.getBusinessLowId());
                a.setBusinessHighId(snap.getBusinessHighId());
                a.setStatus(AssertionStatus.valueOf(snap.getStatus()));
                a.setRetractedBy(snap.getRetractedBy());
                a.setRetractedAt(snap.getRetractedAt());
                assertionRepository.save(a);
            });
        }

        // Restore relationship_direction rows and subtract their contribution from target's consolidated rows.
        for (MergeDirectionSnapshot snap : directionSnapshots) {
            UUID counterparty = snap.getId().getCounterpartyBusinessId();
            Direction direction = snap.getId().getDirection();

            RelationshipDirectionId originalId = direction == Direction.SELLER
                    ? new RelationshipDirectionId(sourceId, counterparty)
                    : new RelationshipDirectionId(counterparty, sourceId);
            RelationshipDirection restored = new RelationshipDirection(originalId);
            restored.setTransactionCount(snap.getTransactionCount());
            restored.setTransactionAmount(snap.getTransactionAmount());
            restored.setLastTransactionAt(snap.getLastTransactionAt());
            directionRepository.save(restored);

            if (!counterparty.equals(targetId)) {
                RelationshipDirectionId consolidatedId = direction == Direction.SELLER
                        ? new RelationshipDirectionId(targetId, counterparty)
                        : new RelationshipDirectionId(counterparty, targetId);
                directionRepository.findById(consolidatedId).ifPresent(consolidated -> {
                    long newCount = consolidated.getTransactionCount() - snap.getTransactionCount();
                    BigDecimal newAmount = consolidated.getTransactionAmount().subtract(snap.getTransactionAmount());
                    if (newCount <= 0 && newAmount.signum() <= 0) {
                        directionRepository.delete(consolidated);
                    } else {
                        consolidated.setTransactionCount(Math.max(newCount, 0));
                        consolidated.setTransactionAmount(newAmount.max(BigDecimal.ZERO));
                        // last_transaction_at cannot be exactly recomputed from aggregates alone;
                        // left as-is -- a documented reversal-fidelity limitation (invariant 27).
                        directionRepository.save(consolidated);
                    }
                });
            }
        }

        source.setStatus(NetworkBusinessStatus.ACTIVE);
        source.setCanonicalBusinessId(null);
        businessRepository.save(source);

        eventRepository.save(IdentityMergeEvent.of(mergeOperationId, sourceId, targetId, MergeEventType.MERGE_REVERSED, actor, reason));

        return new ReverseMergeResponse(mergeOperationId, sourceId, "MERGE_REVERSED");
    }

    // ------------------------------------------------------------------

    private void snapshotDirection(UUID mergeOperationId, UUID businessId) {
        // As seller
        directionRepository.findAll().stream()
                .filter(d -> d.getId().getSellerBusinessId().equals(businessId))
                .forEach(d -> directionSnapshotRepository.save(new MergeDirectionSnapshot(
                        new com.quickbooks.biznetwork.merge.domain.MergeDirectionSnapshotId(mergeOperationId, businessId, d.getId().getBuyerBusinessId(), Direction.SELLER),
                        d.getTransactionCount(), d.getTransactionAmount(), d.getLastTransactionAt())));
        // As buyer
        directionRepository.findAll().stream()
                .filter(d -> d.getId().getBuyerBusinessId().equals(businessId))
                .forEach(d -> directionSnapshotRepository.save(new MergeDirectionSnapshot(
                        new com.quickbooks.biznetwork.merge.domain.MergeDirectionSnapshotId(mergeOperationId, businessId, d.getId().getSellerBusinessId(), Direction.BUYER),
                        d.getTransactionCount(), d.getTransactionAmount(), d.getLastTransactionAt())));
    }

    private void snapshotAssertions(UUID mergeOperationId, UUID businessId) {
        List<RelationshipAssertion> affected = assertionRepository.findByBusinessLowIdOrBusinessHighId(businessId, businessId);
        for (RelationshipAssertion a : affected) {
            MergeAssertionSnapshot snap = new MergeAssertionSnapshot();
            snap.setMergeOperationId(mergeOperationId);
            snap.setRelationshipId(a.getRelationshipId());
            snap.setBusinessLowId(a.getBusinessLowId());
            snap.setBusinessHighId(a.getBusinessHighId());
            snap.setSourceType(a.getSourceType().name());
            snap.setSourceReference(a.getSourceReference());
            snap.setStatus(a.getStatus().name());
            snap.setCreatedBy(a.getCreatedBy());
            snap.setCreatedAt(a.getCreatedAt());
            snap.setRetractedBy(a.getRetractedBy());
            snap.setRetractedAt(a.getRetractedAt());
            assertionSnapshotRepository.save(snap);
        }
    }

    /** Rewrites affected relationship_assertion endpoints from source -> target,
     * collapsing duplicate logical edges (invariant 17): on a unique-tuple
     * collision, keep the earlier-created assertion ACTIVE and retract the
     * later one with reason MERGE_DUPLICATE. A self-loop (source's
     * counterparty *is* the target) is retracted outright -- it no longer
     * represents a relationship between two distinct identities. */
    private void consolidateAssertions(UUID sourceId, UUID targetId, String actor) {
        List<RelationshipAssertion> affected = assertionRepository.findByBusinessLowIdOrBusinessHighId(sourceId, sourceId);
        for (RelationshipAssertion a : affected) {
            UUID counterparty = a.getBusinessLowId().equals(sourceId) ? a.getBusinessHighId() : a.getBusinessLowId();

            if (counterparty.equals(targetId)) {
                a.retract(actor, "MERGE_SELF_LOOP");
                assertionRepository.save(a);
                continue;
            }

            UUID newLow = RelationshipService.low(targetId, counterparty);
            UUID newHigh = RelationshipService.high(targetId, counterparty);

            Optional<RelationshipAssertion> collision = assertionRepository
                    .findByBusinessLowIdAndBusinessHighIdAndSourceTypeAndSourceReference(newLow, newHigh, a.getSourceType(), a.getSourceReference());

            if (collision.isPresent() && !collision.get().getRelationshipId().equals(a.getRelationshipId())) {
                RelationshipAssertion existing = collision.get();
                RelationshipAssertion earlier = existing.getCreatedAt().isBefore(a.getCreatedAt()) ? existing : a;
                RelationshipAssertion later = earlier == existing ? a : existing;
                if (later.getStatus() == AssertionStatus.ACTIVE) {
                    later.retract(actor, "MERGE_DUPLICATE");
                    assertionRepository.save(later);
                }
                if (later == a) {
                    // `a` was retracted above and must NOT also be re-pointed; skip endpoint rewrite.
                    continue;
                }
            }

            a.setBusinessLowId(newLow);
            a.setBusinessHighId(newHigh);
            assertionRepository.save(a);
        }
    }

    /** Sums source's directional aggregates into target's, dropping the source rows. */
    private void consolidateDirection(UUID sourceId, UUID targetId) {
        List<RelationshipDirection> affected = directionRepository.findAll().stream()
                .filter(d -> d.getId().getSellerBusinessId().equals(sourceId) || d.getId().getBuyerBusinessId().equals(sourceId))
                .toList();

        for (RelationshipDirection d : affected) {
            boolean sourceIsSeller = d.getId().getSellerBusinessId().equals(sourceId);
            UUID counterparty = sourceIsSeller ? d.getId().getBuyerBusinessId() : d.getId().getSellerBusinessId();

            directionRepository.delete(d);

            if (counterparty.equals(targetId)) {
                continue; // self-loop created by the merge; drop rather than fold into itself
            }

            RelationshipDirectionId newId = sourceIsSeller
                    ? new RelationshipDirectionId(targetId, counterparty)
                    : new RelationshipDirectionId(counterparty, targetId);

            RelationshipDirection target = directionRepository.findById(newId)
                    .orElseGet(() -> new RelationshipDirection(newId));
            target.setTransactionCount(target.getTransactionCount() + d.getTransactionCount());
            target.setTransactionAmount(target.getTransactionAmount().add(d.getTransactionAmount()));
            Instant maxTs = maxInstant(target.getLastTransactionAt(), d.getLastTransactionAt());
            target.setLastTransactionAt(maxTs);
            directionRepository.save(target);
        }
    }

    private Instant maxInstant(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}
