package com.quickbooks.biznetwork.vendor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quickbooks.biznetwork.common.exception.ConflictException;
import com.quickbooks.biznetwork.common.exception.NotFoundException;
import com.quickbooks.biznetwork.identity.domain.NetworkBusiness;
import com.quickbooks.biznetwork.identity.domain.NetworkBusinessStatus;
import com.quickbooks.biznetwork.identity.domain.SourceBusinessRef;
import com.quickbooks.biznetwork.identity.domain.SourceBusinessRefId;
import com.quickbooks.biznetwork.identity.repository.NetworkBusinessRepository;
import com.quickbooks.biznetwork.identity.repository.SourceBusinessRefRepository;
import com.quickbooks.biznetwork.identity.service.AuthorizationService;
import com.quickbooks.biznetwork.identity.service.CanonicalizationService;
import com.quickbooks.biznetwork.relationship.dto.CreateRelationshipRequest;
import com.quickbooks.biznetwork.relationship.service.RelationshipService;
import com.quickbooks.biznetwork.resolution.dto.CandidateDto;
import com.quickbooks.biznetwork.resolution.dto.ResolveRequest;
import com.quickbooks.biznetwork.resolution.dto.ResolveResponse;
import com.quickbooks.biznetwork.resolution.repository.IdentityResolutionCandidateRepository;
import com.quickbooks.biznetwork.resolution.service.IdentityResolutionService;
import com.quickbooks.biznetwork.vendor.domain.AddOperationState;
import com.quickbooks.biznetwork.vendor.domain.BusinessAddOperation;
import com.quickbooks.biznetwork.vendor.dto.*;
import com.quickbooks.biznetwork.vendor.repository.BusinessAddOperationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * FR3/FR4a. This is the ONLY component allowed to create a NetworkBusiness
 * (the read-only resolver in IdentityResolutionService never does). It owns
 * the PENDING_SOURCE -> source association -> ACTIVE -> relationship
 * sequence described in the design doc's FR4a diagram.
 *
 * V1 vertical-slice simplification (Section 17): the QBO source-record
 * creation/association step, which in production is an outbound call with
 * a server-owned background retry policy, is performed synchronously here
 * and assumed to succeed. The PENDING_SOURCE state is still modeled and
 * persisted so the state machine and API contract match the design; only
 * the async retry worker is out of scope for this executable slice.
 */
@Service
public class AddVendorService {

    private final BusinessAddOperationRepository operationRepository;
    private final NetworkBusinessRepository businessRepository;
    private final SourceBusinessRefRepository sourceRefRepository;
    private final IdentityResolutionService resolutionService;
    private final IdentityResolutionCandidateRepository candidateRepository;
    private final RelationshipService relationshipService;
    private final CanonicalizationService canonicalizationService;
    private final AuthorizationService authorizationService;
    private final ObjectMapper objectMapper;

    public AddVendorService(BusinessAddOperationRepository operationRepository,
                             NetworkBusinessRepository businessRepository,
                             SourceBusinessRefRepository sourceRefRepository,
                             IdentityResolutionService resolutionService,
                             IdentityResolutionCandidateRepository candidateRepository,
                             RelationshipService relationshipService,
                             CanonicalizationService canonicalizationService,
                             AuthorizationService authorizationService,
                             ObjectMapper objectMapper) {
        this.operationRepository = operationRepository;
        this.businessRepository = businessRepository;
        this.sourceRefRepository = sourceRefRepository;
        this.resolutionService = resolutionService;
        this.candidateRepository = candidateRepository;
        this.relationshipService = relationshipService;
        this.canonicalizationService = canonicalizationService;
        this.authorizationService = authorizationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AddVendorResponse addVendor(UUID ownerBusinessId, String idempotencyKey, AddVendorRequest request, String actor) {
        authorizationService.requireManage(actor, ownerBusinessId);

        var existing = operationRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return resumeExisting(existing.get());
        }

        BusinessAddOperation op = new BusinessAddOperation();
        op.setIdempotencyKey(idempotencyKey);
        op.setOwnerBusinessId(ownerBusinessId);
        op.setState(AddOperationState.RESOLVING);
        op.setRequestPayload(objectMapper.valueToTree(request));
        op = operationRepository.save(op);

        ResolveRequest resolveRequest = new ResolveRequest(request.sourceSystem(), request.sourceEntityType(),
                request.sourceEntityId(), request.displayName(), request.taxId(), request.email(), request.phone());
        ResolveResponse resolution = resolutionService.resolve(resolveRequest, actor);
        op.setResolutionId(resolution.resolutionId());

        return switch (resolution.decision()) {
            case MATCH -> finalizeWithExistingBusiness(op, resolution.matchedBusinessId(), request, actor, resolution.decision(), null);
            case NO_MATCH -> createNewBusinessAndFinalize(op, request, actor);
            case CONFIRM_REQUIRED -> {
                op.setState(AddOperationState.AWAITING_CONFIRMATION);
                operationRepository.save(op);
                yield new AddVendorResponse(op.getOperationId(), op.getState(), null, null,
                        resolution.decision(), resolution.candidates());
            }
        };
    }

    @Transactional
    public AddVendorResponse confirm(UUID ownerBusinessId, ConfirmVendorRequest request, String actor) {
        authorizationService.requireManage(actor, ownerBusinessId);

        BusinessAddOperation op = operationRepository.findById(request.operationId())
                .orElseThrow(() -> new NotFoundException("NOT_FOUND", "Add-vendor operation not found: " + request.operationId()));

        if (!op.getOwnerBusinessId().equals(ownerBusinessId)) {
            throw new NotFoundException("NOT_FOUND", "Add-vendor operation not found: " + request.operationId());
        }
        if (op.getState() != AddOperationState.AWAITING_CONFIRMATION) {
            throw new ConflictException("INVALID_OPERATION_STATE",
                    "Operation " + op.getOperationId() + " is not awaiting confirmation (state=" + op.getState() + ")");
        }

        AddVendorRequest originalRequest = objectMapper.convertValue(op.getRequestPayload(), AddVendorRequest.class);

        if (request.decision() == ConfirmDecision.USE_EXISTING) {
            if (request.selectedNetworkBusinessId() == null) {
                throw new ConflictException("SELECTION_REQUIRED", "selectedNetworkBusinessId is required for USE_EXISTING");
            }
            return finalizeWithExistingBusiness(op, request.selectedNetworkBusinessId(), originalRequest, actor, null, null);
        } else {
            return createNewBusinessAndFinalize(op, originalRequest, actor);
        }
    }

    // ------------------------------------------------------------------

    private AddVendorResponse resumeExisting(BusinessAddOperation op) {
        return switch (op.getState()) {
            case RELATIONSHIP_CREATED -> new AddVendorResponse(op.getOperationId(), op.getState(),
                    op.getNetworkBusinessId(), op.getRelationshipId(), null, List.of());
            case AWAITING_CONFIRMATION -> {
                List<CandidateDto> candidates = op.getResolutionId() == null ? List.of() :
                        candidateRepository.findById_ResolutionIdOrderByRank(op.getResolutionId()).stream()
                                .map(c -> {
                                    NetworkBusiness b = businessRepository.findById(c.getId().getCandidateBusinessId()).orElse(null);
                                    return new CandidateDto(c.getId().getCandidateBusinessId(),
                                            b != null ? b.getDisplayName() : "(unknown)", c.getRank(), c.getScore(),
                                            c.getEvidence() != null ? c.getEvidence().toString() : null);
                                }).toList();
                yield new AddVendorResponse(op.getOperationId(), op.getState(), null, null,
                        com.quickbooks.biznetwork.resolution.domain.ResolutionDecision.CONFIRM_REQUIRED, candidates);
            }
            default -> new AddVendorResponse(op.getOperationId(), op.getState(), op.getNetworkBusinessId(),
                    op.getRelationshipId(), null, List.of());
        };
    }

    private AddVendorResponse finalizeWithExistingBusiness(BusinessAddOperation op, UUID businessId, AddVendorRequest request,
                                                             String actor, com.quickbooks.biznetwork.resolution.domain.ResolutionDecision decision,
                                                             List<CandidateDto> candidates) {
        NetworkBusiness nb = canonicalizationService.resolveCanonical(businessId);
        if (!nb.isActive()) {
            throw new ConflictException("IDENTITY_NOT_ACTIVE",
                    "Resolved business " + nb.getNetworkBusinessId() + " is not ACTIVE (status=" + nb.getStatus() + ")");
        }
        return completeRelationship(op, nb, request, actor, decision, candidates);
    }

    private AddVendorResponse createNewBusinessAndFinalize(BusinessAddOperation op, AddVendorRequest request, String actor) {
        NetworkBusiness nb;
        SourceBusinessRefId sourceId = null;
        if (request.sourceEntityId() != null && !request.sourceEntityId().isBlank()) {
            sourceId = new SourceBusinessRefId(request.sourceSystem(), request.sourceEntityType(), request.sourceEntityId());
            var existingRef = sourceRefRepository.findById(sourceId);
            if (existingRef.isPresent()) {
                // Idempotent re-association: the source record already maps to a NetworkBusiness.
                nb = canonicalizationService.resolveCanonical(existingRef.get().getNetworkBusinessId());
                return completeRelationship(op, nb, request, actor, null, null);
            }
        }

        nb = new NetworkBusiness(request.displayName(), NetworkBusinessStatus.PENDING_SOURCE);
        nb = businessRepository.save(nb);
        op.setNetworkBusinessId(nb.getNetworkBusinessId());
        op.setState(AddOperationState.SOURCE_PENDING);
        operationRepository.save(op);

        // --- Simulated synchronous QBO source association (see class javadoc) ---
        String sourceEntityId = request.sourceEntityId() != null && !request.sourceEntityId().isBlank()
                ? request.sourceEntityId()
                : "GEN-" + UUID.randomUUID();
        SourceBusinessRefId id = sourceId != null ? sourceId
                : new SourceBusinessRefId(request.sourceSystem(), request.sourceEntityType(), sourceEntityId);
        SourceBusinessRef ref = new SourceBusinessRef(id, nb.getNetworkBusinessId(), request.displayName());
        sourceRefRepository.save(ref);

        nb.setStatus(NetworkBusinessStatus.ACTIVE);
        nb = businessRepository.save(nb);
        op.setState(AddOperationState.SOURCE_CREATED);
        operationRepository.save(op);
        // --- end simulated association ---

        return completeRelationship(op, nb, request, actor, null, null);
    }

    private AddVendorResponse completeRelationship(BusinessAddOperation op, NetworkBusiness nb, AddVendorRequest request,
                                                     String actor, com.quickbooks.biznetwork.resolution.domain.ResolutionDecision decision,
                                                     List<CandidateDto> candidates) {
        var result = relationshipService.createRelationship(
                new CreateRelationshipRequest(op.getOwnerBusinessId(), nb.getNetworkBusinessId(),
                        request.relationshipSourceType(), request.relationshipSourceReference()),
                actor);

        op.setNetworkBusinessId(nb.getNetworkBusinessId());
        op.setRelationshipId(result.response().relationshipId());
        op.setState(AddOperationState.RELATIONSHIP_CREATED);
        operationRepository.save(op);

        return new AddVendorResponse(op.getOperationId(), op.getState(), nb.getNetworkBusinessId(),
                result.response().relationshipId(), decision, candidates == null ? List.of() : candidates);
    }
}
