package com.quickbooks.biznetwork.resolution.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quickbooks.biznetwork.config.AiResolutionProperties;
import com.quickbooks.biznetwork.identity.domain.NetworkBusiness;
import com.quickbooks.biznetwork.identity.domain.NetworkBusinessStatus;
import com.quickbooks.biznetwork.identity.domain.SourceEntityType;
import com.quickbooks.biznetwork.identity.repository.NetworkBusinessRepository;
import com.quickbooks.biznetwork.resolution.client.AiRankRequest;
import com.quickbooks.biznetwork.resolution.client.AiRankResponse;
import com.quickbooks.biznetwork.resolution.client.AiRankingClient;
import com.quickbooks.biznetwork.resolution.domain.IdentityResolution;
import com.quickbooks.biznetwork.resolution.domain.ResolutionDecision;
import com.quickbooks.biznetwork.resolution.dto.ResolveRequest;
import com.quickbooks.biznetwork.resolution.dto.ResolveResponse;
import com.quickbooks.biznetwork.resolution.repository.IdentityResolutionCandidateRepository;
import com.quickbooks.biznetwork.resolution.repository.IdentityResolutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests (mocked repositories/client -- no Spring context, no DB) for
 * the two-step resolution pipeline: deterministic scoring first, AI
 * re-ranking as a fail-soft enhancement, decision thresholds applied to
 * the BLENDED score.
 */
@ExtendWith(MockitoExtension.class)
class IdentityResolutionServiceTest {

    @Mock
    private NetworkBusinessRepository businessRepository;
    @Mock
    private IdentityResolutionRepository resolutionRepository;
    @Mock
    private IdentityResolutionCandidateRepository candidateRepository;
    @Mock
    private AiRankingClient aiRankingClient;

    private IdentityResolutionService service;
    private AiResolutionProperties aiProperties;

    @BeforeEach
    void setUp() {
        aiProperties = new AiResolutionProperties();
        aiProperties.setBlendWeight(0.5);
        service = new IdentityResolutionService(businessRepository, resolutionRepository, candidateRepository,
                aiRankingClient, aiProperties, new ObjectMapper());

        // save() just needs to hand back something with a non-null id; the
        // service reads resolution.getResolutionId() afterward.
        when(resolutionRepository.save(any())).thenAnswer(inv -> {
            IdentityResolution r = inv.getArgument(0);
            r.setResolutionId(UUID.randomUUID());
            return r;
        });
    }

    private NetworkBusiness business(String name) {
        NetworkBusiness b = new NetworkBusiness(name, NetworkBusinessStatus.ACTIVE);
        b.setNetworkBusinessId(UUID.randomUUID());
        return b;
    }

    private ResolveRequest request(String name) {
        return new ResolveRequest("QBO", SourceEntityType.VENDOR, "V-1", name, null, null, null);
    }

    @Test
    void exactNameMatchIsMatch() {
        NetworkBusiness acme = business("Acme Supplies Pvt Ltd");
        when(businessRepository.findAll()).thenReturn(List.of(acme));
        when(aiRankingClient.rank(any(), any())).thenReturn(Optional.empty());

        ResolveResponse response = service.resolve(request("Acme Supplies Pvt Ltd"), "tester");

        assertThat(response.decision()).isEqualTo(ResolutionDecision.MATCH);
        assertThat(response.matchedBusinessId()).isEqualTo(acme.getNetworkBusinessId());
    }

    @Test
    void twoSimilarNonExactCandidatesRequireConfirmation() {
        // Both candidates land comfortably between CONFIRM_THRESHOLD (0.55) and
        // MATCH_THRESHOLD (0.90) -- neither can win outright.
        NetworkBusiness a = business("Bharat Textiles");           // ~0.60 vs query
        NetworkBusiness b = business("Bharat Transport Company");  // ~0.69 vs query
        when(businessRepository.findAll()).thenReturn(List.of(a, b));
        when(aiRankingClient.rank(any(), any())).thenReturn(Optional.empty());

        ResolveResponse response = service.resolve(request("Bharat Traders"), "tester");

        assertThat(response.decision()).isEqualTo(ResolutionDecision.CONFIRM_REQUIRED);
        assertThat(response.matchedBusinessId()).isNull();
        assertThat(response.candidates()).isNotEmpty();
    }

    @Test
    void noCandidateAboveThresholdIsNoMatch() {
        NetworkBusiness unrelated = business("Zenith Freight Solutions");
        when(businessRepository.findAll()).thenReturn(List.of(unrelated));

        ResolveResponse response = service.resolve(request("Completely Different Company Name"), "tester");

        assertThat(response.decision()).isEqualTo(ResolutionDecision.NO_MATCH);
        assertThat(response.matchedBusinessId()).isNull();
        // Deterministic pool was filtered out before ever reaching the AI step.
        verifyNoInteractions(aiRankingClient);
    }

    @Test
    void aiUnavailableFallsBackToDeterministicOnly() {
        NetworkBusiness acme = business("Acme Supplies Pvt Ltd");
        when(businessRepository.findAll()).thenReturn(List.of(acme));
        when(aiRankingClient.rank(any(), any())).thenReturn(Optional.empty());

        ResolveResponse response = service.resolve(request("Acme Supplies Pvt Ltd"), "tester");

        assertThat(response.decision()).isEqualTo(ResolutionDecision.MATCH);
        var resolutionCaptor = org.mockito.ArgumentCaptor.forClass(IdentityResolution.class);
        verify(resolutionRepository).save(resolutionCaptor.capture());
        assertThat(resolutionCaptor.getValue().getMethod()).isEqualTo("NAME_SIMILARITY_V1");
    }

    @Test
    void aiCanReorderCandidatesButCannotBypassMatchThreshold() {
        // Deterministic scores (~0.60 / ~0.64 vs the query below) are both
        // solidly in "ambiguous" territory -- nowhere near MATCH_THRESHOLD.
        NetworkBusiness weakA = business("Bharat Textiles");        // ~0.60 vs query
        NetworkBusiness weakB = business("Bharat Tradeco Exports"); // ~0.64 vs query -- deterministically AHEAD of weakA
        when(businessRepository.findAll()).thenReturn(List.of(weakA, weakB));

        // The AI backend disagrees strongly and favors weakA instead.
        AiRankResponse aiResponse = new AiRankResponse("EMBEDDING", "test-model", List.of(
                new AiRankResponse.RankedResult(weakA.getNetworkBusinessId().toString(), 0.99),
                new AiRankResponse.RankedResult(weakB.getNetworkBusinessId().toString(), 0.05)
        ));
        when(aiRankingClient.rank(any(), any())).thenReturn(Optional.of(aiResponse));

        ResolveResponse response = service.resolve(request("Bharat Traders"), "tester");

        // AI's opinion flips the ranking: weakA (deterministically behind) is now first.
        assertThat(response.candidates().get(0).networkBusinessId()).isEqualTo(weakA.getNetworkBusinessId());
        // ...but a 0.99 AI opinion blended 50/50 with a weak deterministic score
        // (blended = 0.5*0.60 + 0.5*0.99 = 0.795) still cannot cross
        // MATCH_THRESHOLD (0.90) on its own -- the deterministic signal is
        // never bypassed, only re-ranked.
        assertThat(response.decision()).isEqualTo(ResolutionDecision.CONFIRM_REQUIRED);

        var resolutionCaptor = org.mockito.ArgumentCaptor.forClass(IdentityResolution.class);
        verify(resolutionRepository).save(resolutionCaptor.capture());
        assertThat(resolutionCaptor.getValue().getMethod()).contains("AI_RANKING(EMBEDDING)");
    }
}
