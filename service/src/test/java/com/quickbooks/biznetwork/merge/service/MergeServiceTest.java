package com.quickbooks.biznetwork.merge.service;

import com.quickbooks.biznetwork.common.exception.ConflictException;
import com.quickbooks.biznetwork.identity.domain.NetworkBusiness;
import com.quickbooks.biznetwork.identity.domain.NetworkBusinessStatus;
import com.quickbooks.biznetwork.identity.repository.NetworkBusinessRepository;
import com.quickbooks.biznetwork.identity.service.AuthorizationService;
import com.quickbooks.biznetwork.identity.service.CanonicalizationService;
import com.quickbooks.biznetwork.merge.domain.IdentityMergeEvent;
import com.quickbooks.biznetwork.merge.domain.MergeAssertionSnapshot;
import com.quickbooks.biznetwork.merge.domain.MergeEventType;
import com.quickbooks.biznetwork.merge.dto.MergeRequest;
import com.quickbooks.biznetwork.merge.dto.MergeResponse;
import com.quickbooks.biznetwork.merge.dto.ReverseMergeResponse;
import com.quickbooks.biznetwork.merge.repository.IdentityMergeEventRepository;
import com.quickbooks.biznetwork.merge.repository.MergeAssertionSnapshotRepository;
import com.quickbooks.biznetwork.merge.repository.MergeDirectionSnapshotRepository;
import com.quickbooks.biznetwork.relationship.repository.RelationshipAssertionRepository;
import com.quickbooks.biznetwork.relationship.repository.RelationshipDirectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MergeServiceTest {

    @Mock private NetworkBusinessRepository businessRepository;
    @Mock private CanonicalizationService canonicalizationService;
    @Mock private AuthorizationService authorizationService;
    @Mock private IdentityMergeEventRepository eventRepository;
    @Mock private MergeDirectionSnapshotRepository directionSnapshotRepository;
    @Mock private MergeAssertionSnapshotRepository assertionSnapshotRepository;
    @Mock private RelationshipAssertionRepository assertionRepository;
    @Mock private RelationshipDirectionRepository directionRepository;

    private MergeService service;

    private static final String ACTOR = "demo-user";

    @BeforeEach
    void setUp() {
        service = new MergeService(businessRepository, canonicalizationService, authorizationService,
                eventRepository, directionSnapshotRepository, assertionSnapshotRepository,
                assertionRepository, directionRepository);
    }

    private NetworkBusiness activeBusiness(String name) {
        NetworkBusiness b = new NetworkBusiness(name, NetworkBusinessStatus.ACTIVE);
        b.setNetworkBusinessId(UUID.randomUUID());
        return b;
    }

    @Test
    void confirmMergeSupersedesSourceAndPointsAtTarget() {
        NetworkBusiness source = activeBusiness("Everest Hardware");
        NetworkBusiness target = activeBusiness("Delta Logistics");
        UUID sourceId = source.getNetworkBusinessId();
        UUID targetId = target.getNetworkBusinessId();

        when(businessRepository.lockById(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            if (id.equals(sourceId)) return Optional.of(source);
            if (id.equals(targetId)) return Optional.of(target);
            return Optional.empty();
        });
        when(canonicalizationService.resolveCanonical(sourceId)).thenReturn(source);
        when(canonicalizationService.resolveCanonical(targetId)).thenReturn(target);
        when(businessRepository.findAll()).thenReturn(List.of(source, target));
        when(assertionRepository.findByBusinessLowIdOrBusinessHighId(any(), any())).thenReturn(List.of());
        when(directionRepository.findAll()).thenReturn(List.of());

        MergeResponse response = service.confirmMerge(new MergeRequest(sourceId, targetId, "duplicate identity"), ACTOR);

        assertThat(response.status()).isEqualTo("CONSOLIDATION_COMPLETED");
        assertThat(response.resolvedSourceBusinessId()).isEqualTo(sourceId);
        assertThat(response.resolvedTargetBusinessId()).isEqualTo(targetId);

        // The whole point of the merge: source is superseded, pointed at target.
        assertThat(source.getStatus()).isEqualTo(NetworkBusinessStatus.SUPERSEDED);
        assertThat(source.getCanonicalBusinessId()).isEqualTo(targetId);
        assertThat(target.getStatus()).isEqualTo(NetworkBusinessStatus.ACTIVE);

        // Full audit trail: MERGE_CONFIRMED -> CONSOLIDATION_STARTED -> CONSOLIDATION_COMPLETED.
        verify(eventRepository, times(3)).save(any(IdentityMergeEvent.class));
    }

    @Test
    void mergingAlreadyMergedIdentitiesIsRejected() {
        NetworkBusiness same = activeBusiness("Same Business");
        UUID id = same.getNetworkBusinessId();
        when(businessRepository.lockById(any())).thenReturn(Optional.of(same));
        when(canonicalizationService.resolveCanonical(id)).thenReturn(same);

        assertThatThrownBy(() -> service.confirmMerge(new MergeRequest(id, id, "no-op"), ACTOR))
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((ConflictException) ex).getCode())
                .isEqualTo("MERGE_NOOP");
    }

    @Test
    void reverseMergeRestoresSourceToActive() {
        NetworkBusiness source = activeBusiness("Everest Hardware");
        NetworkBusiness target = activeBusiness("Delta Logistics");
        UUID sourceId = source.getNetworkBusinessId();
        UUID targetId = target.getNetworkBusinessId();
        UUID mergeOperationId = UUID.randomUUID();

        // Simulate post-merge state directly (unit test in isolation from confirmMerge).
        source.setStatus(NetworkBusinessStatus.SUPERSEDED);
        source.setCanonicalBusinessId(targetId);

        IdentityMergeEvent confirmedEvent = IdentityMergeEvent.of(mergeOperationId, sourceId, targetId,
                MergeEventType.MERGE_CONFIRMED, ACTOR, "reason");
        when(eventRepository.findFirstByMergeOperationIdAndEventTypeOrderByCreatedAtDesc(mergeOperationId, MergeEventType.MERGE_CONFIRMED))
                .thenReturn(Optional.of(confirmedEvent));

        when(businessRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(businessRepository.findById(targetId)).thenReturn(Optional.of(target));

        MergeAssertionSnapshot snapshot = new MergeAssertionSnapshot();
        snapshot.setMergeOperationId(mergeOperationId);
        snapshot.setRelationshipId(UUID.randomUUID());
        when(assertionSnapshotRepository.findByMergeOperationId(mergeOperationId)).thenReturn(List.of(snapshot));
        when(directionSnapshotRepository.findByMergeOperationId(mergeOperationId)).thenReturn(List.of());
        when(assertionRepository.findById(snapshot.getRelationshipId())).thenReturn(Optional.empty());

        ReverseMergeResponse response = service.reverseMerge(mergeOperationId, ACTOR, "merged in error");

        assertThat(response.status()).isEqualTo("MERGE_REVERSED");
        assertThat(response.restoredBusinessId()).isEqualTo(sourceId);
        assertThat(source.getStatus()).isEqualTo(NetworkBusinessStatus.ACTIVE);
        assertThat(source.getCanonicalBusinessId()).isNull();
    }

    @Test
    void reversalIsBlockedWhenTargetHasItselfBeenSuperseded() {
        NetworkBusiness source = activeBusiness("Everest Hardware");
        NetworkBusiness target = activeBusiness("Delta Logistics");
        UUID sourceId = source.getNetworkBusinessId();
        UUID targetId = target.getNetworkBusinessId();
        UUID mergeOperationId = UUID.randomUUID();

        source.setStatus(NetworkBusinessStatus.SUPERSEDED);
        source.setCanonicalBusinessId(targetId);
        // Target has itself since been merged into a third identity (A20).
        target.setStatus(NetworkBusinessStatus.SUPERSEDED);

        IdentityMergeEvent confirmedEvent = IdentityMergeEvent.of(mergeOperationId, sourceId, targetId,
                MergeEventType.MERGE_CONFIRMED, ACTOR, "reason");
        when(eventRepository.findFirstByMergeOperationIdAndEventTypeOrderByCreatedAtDesc(mergeOperationId, MergeEventType.MERGE_CONFIRMED))
                .thenReturn(Optional.of(confirmedEvent));
        when(businessRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(businessRepository.findById(targetId)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.reverseMerge(mergeOperationId, ACTOR, "trying to undo"))
                .isInstanceOf(ConflictException.class)
                .extracting(ex -> ((ConflictException) ex).getCode())
                .isEqualTo("MERGE_ALREADY_SUPERSEDED");
    }
}
