package com.quickbooks.biznetwork.resolution.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quickbooks.biznetwork.config.AiResolutionProperties;
import com.quickbooks.biznetwork.identity.domain.NetworkBusiness;
import com.quickbooks.biznetwork.identity.repository.NetworkBusinessRepository;
import com.quickbooks.biznetwork.resolution.client.AiRankRequest;
import com.quickbooks.biznetwork.resolution.client.AiRankResponse;
import com.quickbooks.biznetwork.resolution.client.AiRankingClient;
import com.quickbooks.biznetwork.resolution.domain.IdentityResolution;
import com.quickbooks.biznetwork.resolution.domain.IdentityResolutionCandidate;
import com.quickbooks.biznetwork.resolution.domain.IdentityResolutionCandidateId;
import com.quickbooks.biznetwork.resolution.domain.ResolutionDecision;
import com.quickbooks.biznetwork.resolution.dto.CandidateDto;
import com.quickbooks.biznetwork.resolution.dto.ResolveRequest;
import com.quickbooks.biznetwork.resolution.dto.ResolveResponse;
import com.quickbooks.biznetwork.resolution.repository.IdentityResolutionCandidateRepository;
import com.quickbooks.biznetwork.resolution.repository.IdentityResolutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FR4 / A10: POST /businesses/resolve is READ-ONLY. It never creates a
 * NetworkBusiness -- that side effect belongs to the Add Vendor command
 * (FR4a), which is the only caller allowed to act on a NO_MATCH result.
 *
 * Section 12 pipeline, implemented in two clearly separated steps:
 *   1. DETERMINISTIC candidate retrieval + scoring (NameSimilarity) --
 *      always runs, never depends on external services.
 *   2. AI/ML re-ranking as an ENHANCEMENT (ai-resolution/ranking) -- only
 *      re-scores the bounded top-K the deterministic step already found.
 *      If unavailable/disabled, step 1's result is used unchanged
 *      (Section 12 failure mode: an AI outage never blocks resolution).
 *
 * Final decision thresholds (A9: ambiguous matches always require
 * confirmation, never silent auto-merge) are applied to the BLENDED score:
 *   blended = (1 - blendWeight) * deterministicScore + blendWeight * aiScore
 *   score >= MATCH_THRESHOLD and clearly ahead of runner-up -> MATCH
 *   score >= CONFIRM_THRESHOLD (but not a clean MATCH)      -> CONFIRM_REQUIRED
 *   otherwise                                                -> NO_MATCH
 */
@Service
public class IdentityResolutionService {

    private static final double MATCH_THRESHOLD = 0.90;
    private static final double CONFIRM_THRESHOLD = 0.55;
    private static final double RUNNER_UP_GAP = 0.12;
    private static final int TOP_K = 5;
    private static final String DETERMINISTIC_METHOD = "NAME_SIMILARITY_V1";

    private final NetworkBusinessRepository businessRepository;
    private final IdentityResolutionRepository resolutionRepository;
    private final IdentityResolutionCandidateRepository candidateRepository;
    private final AiRankingClient aiRankingClient;
    private final AiResolutionProperties aiProperties;
    private final ObjectMapper objectMapper;

    public IdentityResolutionService(NetworkBusinessRepository businessRepository,
                                      IdentityResolutionRepository resolutionRepository,
                                      IdentityResolutionCandidateRepository candidateRepository,
                                      AiRankingClient aiRankingClient,
                                      AiResolutionProperties aiProperties,
                                      ObjectMapper objectMapper) {
        this.businessRepository = businessRepository;
        this.resolutionRepository = resolutionRepository;
        this.candidateRepository = candidateRepository;
        this.aiRankingClient = aiRankingClient;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    private record Scored(NetworkBusiness business, double deterministicScore, Double aiScore, double blendedScore) {
    }

    @Transactional
    public ResolveResponse resolve(ResolveRequest request, String actorId) {
        // ---- Step 1: deterministic candidate retrieval + scoring ----
        List<NetworkBusiness> pool = businessRepository.findAll().stream()
                .filter(NetworkBusiness::isActive)
                .toList();

        record DeterministicScore(NetworkBusiness business, double score) {
        }

        List<DeterministicScore> deterministic = pool.stream()
                .map(b -> new DeterministicScore(b, NameSimilarity.similarity(request.displayName(), b.getDisplayName())))
                .filter(s -> s.score() >= CONFIRM_THRESHOLD)
                .sorted(Comparator.comparingDouble(DeterministicScore::score).reversed())
                .limit(TOP_K)
                .toList();

        // ---- Step 2: AI/ML re-ranking as an enhancement (fail-soft) ----
        Optional<AiRankResponse> aiResponse = Optional.empty();
        if (!deterministic.isEmpty()) {
            List<AiRankRequest.Candidate> aiCandidates = deterministic.stream()
                    .map(s -> new AiRankRequest.Candidate(s.business().getNetworkBusinessId().toString(), s.business().getDisplayName()))
                    .toList();
            aiResponse = aiRankingClient.rank(request.displayName(), aiCandidates);
        }

        Map<String, Double> aiScoresById = new HashMap<>();
        aiResponse.ifPresent(r -> r.results().forEach(res -> aiScoresById.put(res.id(), res.score())));

        double blendWeight = aiResponse.isPresent() ? aiProperties.getBlendWeight() : 0.0;

        List<Scored> scored = deterministic.stream()
                .map(s -> {
                    Double aiScore = aiScoresById.get(s.business().getNetworkBusinessId().toString());
                    double effectiveAiScore = aiScore != null ? aiScore : s.score();
                    double blended = (1 - blendWeight) * s.score() + blendWeight * effectiveAiScore;
                    return new Scored(s.business(), s.score(), aiScore, blended);
                })
                .sorted(Comparator.comparingDouble(Scored::blendedScore).reversed())
                .toList();

        String method = aiResponse.map(r -> DETERMINISTIC_METHOD + "+AI_RANKING(" + r.backend() + ")").orElse(DETERMINISTIC_METHOD);

        // ---- Decision (A9: always confirm ambiguous matches) ----
        ResolutionDecision decision;
        NetworkBusiness selected = null;

        if (!scored.isEmpty() && scored.get(0).blendedScore() >= MATCH_THRESHOLD
                && (scored.size() == 1 || (scored.get(0).blendedScore() - scored.get(1).blendedScore()) >= RUNNER_UP_GAP)) {
            decision = ResolutionDecision.MATCH;
            selected = scored.get(0).business();
        } else if (!scored.isEmpty()) {
            decision = ResolutionDecision.CONFIRM_REQUIRED;
        } else {
            decision = ResolutionDecision.NO_MATCH;
        }

        IdentityResolution resolution = new IdentityResolution();
        resolution.setInputDescriptor(toJson(request));
        resolution.setDecision(decision);
        resolution.setSelectedBusinessId(selected != null ? selected.getNetworkBusinessId() : null);
        resolution.setMethod(method);
        resolution.setActorId(actorId);
        resolution = resolutionRepository.save(resolution);

        List<CandidateDto> candidateDtos = new ArrayList<>();
        int rank = 1;
        for (Scored s : scored) {
            boolean isSelected = decision == ResolutionDecision.MATCH && s.business().equals(selected);

            ObjectNode evidence = objectMapper.createObjectNode();
            evidence.put("deterministicScore", round(s.deterministicScore()));
            if (s.aiScore() != null) {
                evidence.put("aiScore", round(s.aiScore()));
                evidence.put("aiBackend", aiResponse.map(AiRankResponse::backend).orElse(null));
                evidence.put("aiModel", aiResponse.map(AiRankResponse::model).orElse(null));
            }
            evidence.put("blendedScore", round(s.blendedScore()));
            evidence.put("method", method);

            IdentityResolutionCandidate candidate = new IdentityResolutionCandidate(
                    new IdentityResolutionCandidateId(resolution.getResolutionId(), s.business().getNetworkBusinessId()),
                    rank,
                    s.blendedScore(),
                    evidence,
                    isSelected
            );
            candidateRepository.save(candidate);
            candidateDtos.add(new CandidateDto(s.business().getNetworkBusinessId(), s.business().getDisplayName(), rank, round(s.blendedScore()), evidence.toString()));
            rank++;
        }

        return new ResolveResponse(
                resolution.getResolutionId(),
                decision,
                selected != null ? selected.getNetworkBusinessId() : null,
                candidateDtos
        );
    }

    private JsonNode toJson(ResolveRequest request) {
        return objectMapper.valueToTree(request);
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
