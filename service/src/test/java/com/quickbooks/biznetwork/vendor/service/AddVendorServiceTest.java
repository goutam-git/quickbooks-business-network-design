package com.quickbooks.biznetwork.vendor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quickbooks.biznetwork.identity.domain.SourceEntityType;
import com.quickbooks.biznetwork.identity.repository.NetworkBusinessRepository;
import com.quickbooks.biznetwork.identity.repository.SourceBusinessRefRepository;
import com.quickbooks.biznetwork.identity.service.AuthorizationService;
import com.quickbooks.biznetwork.identity.service.CanonicalizationService;
import com.quickbooks.biznetwork.relationship.service.RelationshipService;
import com.quickbooks.biznetwork.resolution.domain.ResolutionDecision;
import com.quickbooks.biznetwork.resolution.dto.CandidateDto;
import com.quickbooks.biznetwork.resolution.dto.ResolveResponse;
import com.quickbooks.biznetwork.resolution.repository.IdentityResolutionCandidateRepository;
import com.quickbooks.biznetwork.resolution.service.IdentityResolutionService;
import com.quickbooks.biznetwork.vendor.domain.AddOperationState;
import com.quickbooks.biznetwork.vendor.domain.BusinessAddOperation;
import com.quickbooks.biznetwork.vendor.dto.AddVendorRequest;
import com.quickbooks.biznetwork.vendor.dto.AddVendorResponse;
import com.quickbooks.biznetwork.vendor.repository.BusinessAddOperationRepository;
import com.quickbooks.biznetwork.relationship.domain.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AddVendorServiceTest {

    @Mock private BusinessAddOperationRepository operationRepository;
    @Mock private NetworkBusinessRepository businessRepository;
    @Mock private SourceBusinessRefRepository sourceRefRepository;
    @Mock private IdentityResolutionService resolutionService;
    @Mock private IdentityResolutionCandidateRepository candidateRepository;
    @Mock private RelationshipService relationshipService;
    @Mock private CanonicalizationService canonicalizationService;
    @Mock private AuthorizationService authorizationService;

    private AddVendorService service;

    private static final String PRINCIPAL = "demo-user";
    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AddVendorService(operationRepository, businessRepository, sourceRefRepository,
                resolutionService, candidateRepository, relationshipService, canonicalizationService,
                authorizationService, new ObjectMapper());
    }

    private AddVendorRequest request() {
        return new AddVendorRequest("QBO", SourceEntityType.VENDOR, "V-1", "Zenith Freight Solutions",
                null, null, null, SourceType.USER, "idem-key-1");
    }

    @Test
    void repeatedIdempotencyKeyReplaysStoredResultWithoutRedoingWork() {
        BusinessAddOperation completed = new BusinessAddOperation();
        completed.setOperationId(UUID.randomUUID());
        completed.setIdempotencyKey("idem-key-1");
        completed.setOwnerBusinessId(ownerId);
        completed.setState(AddOperationState.RELATIONSHIP_CREATED);
        completed.setNetworkBusinessId(UUID.randomUUID());
        completed.setRelationshipId(UUID.randomUUID());

        when(operationRepository.findByIdempotencyKey("idem-key-1")).thenReturn(Optional.of(completed));

        AddVendorResponse response = service.addVendor(ownerId, "idem-key-1", request(), PRINCIPAL);

        assertThat(response.state()).isEqualTo(AddOperationState.RELATIONSHIP_CREATED);
        assertThat(response.networkBusinessId()).isEqualTo(completed.getNetworkBusinessId());
        assertThat(response.relationshipId()).isEqualTo(completed.getRelationshipId());

        // The whole point of idempotent replay: none of the actual work runs again.
        verifyNoInteractions(resolutionService);
        verifyNoInteractions(relationshipService);
        verify(operationRepository, never()).save(any());
    }

    @Test
    void ambiguousResolutionMovesOperationToAwaitingConfirmation() {
        when(operationRepository.findByIdempotencyKey("idem-key-1")).thenReturn(Optional.empty());
        when(operationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UUID candidateId = UUID.randomUUID();
        ResolveResponse ambiguous = new ResolveResponse(UUID.randomUUID(), ResolutionDecision.CONFIRM_REQUIRED, null,
                List.of(new CandidateDto(candidateId, "Zenith Freight Pvt Ltd", 1, 0.7, "{}")));
        when(resolutionService.resolve(any(), eq(PRINCIPAL))).thenReturn(ambiguous);

        AddVendorResponse response = service.addVendor(ownerId, "idem-key-1", request(), PRINCIPAL);

        assertThat(response.state()).isEqualTo(AddOperationState.AWAITING_CONFIRMATION);
        assertThat(response.resolutionDecision()).isEqualTo(ResolutionDecision.CONFIRM_REQUIRED);
        assertThat(response.candidates()).hasSize(1);

        ArgumentCaptor<BusinessAddOperation> captor = ArgumentCaptor.forClass(BusinessAddOperation.class);
        verify(operationRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(AddOperationState.AWAITING_CONFIRMATION);

        // No relationship should be created while the operation is paused for confirmation.
        verifyNoInteractions(relationshipService);
    }
}
