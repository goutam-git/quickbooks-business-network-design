package com.quickbooks.biznetwork.relationship.service;

import com.quickbooks.biznetwork.common.exception.BadRequestException;
import com.quickbooks.biznetwork.common.exception.NotFoundException;
import com.quickbooks.biznetwork.config.PathSearchProperties;
import com.quickbooks.biznetwork.config.TraversalProperties;
import com.quickbooks.biznetwork.identity.domain.NetworkBusiness;
import com.quickbooks.biznetwork.identity.domain.NetworkBusinessStatus;
import com.quickbooks.biznetwork.identity.repository.NetworkBusinessRepository;
import com.quickbooks.biznetwork.identity.service.AuthorizationService;
import com.quickbooks.biznetwork.relationship.dto.NetworkViewResponse;
import com.quickbooks.biznetwork.relationship.dto.PathResponse;
import com.quickbooks.biznetwork.relationship.repository.BusinessRelationshipViewRepository;
import com.quickbooks.biznetwork.relationship.repository.BusinessRelationshipViewRepository.NeighborEdgeRow;
import com.quickbooks.biznetwork.relationship.repository.NeighborRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphTraversalServiceTest {

    @Mock
    private BusinessRelationshipViewRepository viewRepository;
    @Mock
    private NetworkBusinessRepository businessRepository;
    @Mock
    private AuthorizationService authorizationService;

    private TraversalProperties traversalProperties;
    private PathSearchProperties pathSearchProperties;
    private GraphTraversalService service;

    private static final String PRINCIPAL = "demo-user";

    // Chain graph: A - B - C - D  (also used as a triangle A-B-C-A for the cycle test)
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final UUID d = UUID.randomUUID();

    private List<NeighborEdgeRow> allEdges;

    @BeforeEach
    void setUp() {
        traversalProperties = new TraversalProperties();
        pathSearchProperties = new PathSearchProperties();
        service = new GraphTraversalService(viewRepository, businessRepository, authorizationService,
                traversalProperties, pathSearchProperties);

        // Default: everyone can see everyone (individual tests override with lenient()/specific stubs).
        lenient().when(authorizationService.canSee(eq(PRINCIPAL), any(UUID.class))).thenReturn(true);
        lenient().when(businessRepository.findAllById(any())).thenAnswer(inv -> {
            List<UUID> ids = new ArrayList<>();
            ((Iterable<UUID>) inv.getArgument(0)).forEach(ids::add);
            List<NetworkBusiness> result = new ArrayList<>();
            Map<UUID, String> names = Map.of(a, "A", b, "B", c, "C", d, "D");
            for (UUID id : ids) {
                NetworkBusiness nb = new NetworkBusiness(names.getOrDefault(id, "?"), NetworkBusinessStatus.ACTIVE);
                nb.setNetworkBusinessId(id);
                result.add(nb);
            }
            return result;
        });
    }

    private NeighborEdgeRow edge(UUID low, UUID high) {
        UUID lo = low.compareTo(high) <= 0 ? low : high;
        UUID hi = low.compareTo(high) <= 0 ? high : low;
        return new NeighborEdgeRow(lo, hi, BigDecimal.valueOf(1000), 1, null);
    }

    /** Mocks neighborsOfAny/neighborsOf to filter a fixed edge list down to rows
     * touching the relevant frontier/node. lenient() because a given test only
     * exercises one of the two traversal methods, and Mockito's strict
     * stubbing would otherwise fail the other, unused stub.
     *
     * neighborsOfAny returns raw pair rows (NeighborEdgeRow, low/high as
     * stored); neighborsOf returns rows already oriented relative to the
     * single business queried (NeighborRow, just a counterpartId) -- these
     * are genuinely different shapes in the real repository, not
     * interchangeable, so the mock has to produce each correctly. */
    private void wireChainGraph(List<NeighborEdgeRow> edges) {
        lenient().when(viewRepository.neighborsOfAny(anySet())).thenAnswer(inv -> {
            Set<UUID> frontier = inv.getArgument(0);
            List<NeighborEdgeRow> matched = new ArrayList<>();
            for (NeighborEdgeRow e : edges) {
                if (frontier.contains(e.businessLowId()) || frontier.contains(e.businessHighId())) {
                    matched.add(e);
                }
            }
            return matched;
        });
        lenient().when(viewRepository.neighborsOf(any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            List<NeighborRow> matched = new ArrayList<>();
            for (NeighborEdgeRow e : edges) {
                if (e.businessLowId().equals(id)) {
                    matched.add(new NeighborRow(e.businessHighId(), e.volumeAmount(), e.transactionCount(), e.lastTransactionAt()));
                } else if (e.businessHighId().equals(id)) {
                    matched.add(new NeighborRow(e.businessLowId(), e.volumeAmount(), e.transactionCount(), e.lastTransactionAt()));
                }
            }
            return matched;
        });
    }

    @Test
    void depthOneReturnsOnlyDirectNeighbor() {
        wireChainGraph(List.of(edge(a, b), edge(b, c), edge(c, d)));

        NetworkViewResponse response = service.networkView(a, 1, PRINCIPAL, 0);

        assertThat(response.nodes()).extracting(n -> n.networkBusinessId()).containsExactlyInAnyOrder(a, b);
        assertThat(response.edges()).hasSize(1);
    }

    @Test
    void depthTwoReachesSecondHop() {
        wireChainGraph(List.of(edge(a, b), edge(b, c), edge(c, d)));

        NetworkViewResponse response = service.networkView(a, 2, PRINCIPAL, 0);

        assertThat(response.nodes()).extracting(n -> n.networkBusinessId()).containsExactlyInAnyOrder(a, b, c);
    }

    @Test
    void depthThreeReachesThirdHop() {
        wireChainGraph(List.of(edge(a, b), edge(b, c), edge(c, d)));

        NetworkViewResponse response = service.networkView(a, 3, PRINCIPAL, 0);

        assertThat(response.nodes()).extracting(n -> n.networkBusinessId()).containsExactlyInAnyOrder(a, b, c, d);
    }

    @Test
    void outOfRangeDepthIsRejected() {
        traversalProperties.setMaxDepth(3);
        assertThatThrownBy(() -> service.networkView(a, 99, PRINCIPAL, 0))
                .isInstanceOf(BadRequestException.class)
                .extracting(ex -> ((BadRequestException) ex).getCode())
                .isEqualTo("INVALID_DEPTH");
    }

    @Test
    void cyclesDoNotCauseRevisitsOrInfiniteLoop() {
        // Triangle A-B-C-A.
        wireChainGraph(List.of(edge(a, b), edge(b, c), edge(c, a)));

        NetworkViewResponse response = service.networkView(a, 3, PRINCIPAL, 0);

        // Each node appears exactly once despite the cycle.
        assertThat(response.nodes()).extracting(n -> n.networkBusinessId())
                .containsExactlyInAnyOrder(a, b, c)
                .doesNotHaveDuplicates();
    }

    @Test
    void nodeBudgetExhaustionTruncatesAndIsReported() {
        wireChainGraph(List.of(edge(a, b), edge(b, c), edge(c, d)));
        traversalProperties.setMaxExploredNodes(2); // owner (1) + only one more allowed

        NetworkViewResponse response = service.networkView(a, 3, PRINCIPAL, 0);

        assertThat(response.budget().nodeBudgetExhausted()).isTrue();
        assertThat(response.truncated()).isTrue();
        assertThat(response.budget().exploredNodes()).isLessThanOrEqualTo(2);
    }

    @Test
    void shortestPathFollowsChain() {
        wireChainGraph(List.of(edge(a, b), edge(b, c), edge(c, d)));

        PathResponse response = service.shortestPath(a, d, PRINCIPAL, 4);

        assertThat(response.hopCount()).isEqualTo(3);
        assertThat(response.path()).containsExactly(a, b, c, d);
    }

    @Test
    void shortestPathBeyondMaxDepthIsNotFoundWithinDepth() {
        wireChainGraph(List.of(edge(a, b), edge(b, c), edge(c, d)));

        assertThatThrownBy(() -> service.shortestPath(a, d, PRINCIPAL, 1))
                .isInstanceOf(NotFoundException.class)
                .extracting(ex -> ((NotFoundException) ex).getCode())
                .isEqualTo("NOT_FOUND_WITHIN_DEPTH");
    }
}
