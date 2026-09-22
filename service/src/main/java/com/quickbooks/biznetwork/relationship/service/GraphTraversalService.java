package com.quickbooks.biznetwork.relationship.service;

import com.quickbooks.biznetwork.common.exception.BadRequestException;
import com.quickbooks.biznetwork.common.exception.NotFoundException;
import com.quickbooks.biznetwork.config.PathSearchProperties;
import com.quickbooks.biznetwork.config.TraversalProperties;
import com.quickbooks.biznetwork.identity.domain.NetworkBusiness;
import com.quickbooks.biznetwork.identity.repository.NetworkBusinessRepository;
import com.quickbooks.biznetwork.identity.service.AuthorizationService;
import com.quickbooks.biznetwork.relationship.dto.*;
import com.quickbooks.biznetwork.relationship.repository.BusinessRelationshipViewRepository;
import com.quickbooks.biznetwork.relationship.repository.BusinessRelationshipViewRepository.NeighborEdgeRow;
import com.quickbooks.biznetwork.relationship.repository.NeighborRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * FR1 (network view) and FR2 (relationship path search). Implements
 * Section 6's traversal/compute budget vs response/UX budget split, and
 * Section 14 / invariants 20-22: authorization is evaluated DURING
 * expansion (a hidden node is never used as a pass-through), not as a
 * post-hoc filter.
 */
@Service
@Transactional(readOnly = true)
public class GraphTraversalService {

    private final BusinessRelationshipViewRepository viewRepository;
    private final NetworkBusinessRepository businessRepository;
    private final AuthorizationService authorizationService;
    private final TraversalProperties traversalProperties;
    private final PathSearchProperties pathSearchProperties;

    public GraphTraversalService(BusinessRelationshipViewRepository viewRepository,
                                  NetworkBusinessRepository businessRepository,
                                  AuthorizationService authorizationService,
                                  TraversalProperties traversalProperties,
                                  PathSearchProperties pathSearchProperties) {
        this.viewRepository = viewRepository;
        this.businessRepository = businessRepository;
        this.authorizationService = authorizationService;
        this.traversalProperties = traversalProperties;
        this.pathSearchProperties = pathSearchProperties;
    }

    // ------------------------------------------------------------------
    // FR1 - network view
    // ------------------------------------------------------------------

    public NetworkViewResponse networkView(UUID ownerId, int depth, String principalId, int pageOffset) {
        if (depth < 1 || depth > traversalProperties.getMaxDepth()) {
            throw new BadRequestException("INVALID_DEPTH",
                    "depth must be between 1 and " + traversalProperties.getMaxDepth());
        }
        // Owner must be visible; unauthorized/nonexistent are indistinguishable (404).
        authorizationService.requireView(principalId, ownerId);

        Map<UUID, Integer> distance = new LinkedHashMap<>();
        distance.put(ownerId, 0);
        Set<UUID> frontier = new HashSet<>(Set.of(ownerId));

        Map<String, NeighborEdgeRow> edgesByKey = new LinkedHashMap<>();
        int exploredNodes = 1;
        boolean nodeBudgetExhausted = false;
        boolean edgeBudgetExhausted = false;

        for (int hop = 1; hop <= depth && !frontier.isEmpty(); hop++) {
            List<NeighborEdgeRow> rows = viewRepository.neighborsOfAny(frontier);
            Set<UUID> nextFrontier = new LinkedHashSet<>();

            for (NeighborEdgeRow row : rows) {
                UUID low = row.businessLowId();
                UUID high = row.businessHighId();
                boolean lowInFrontier = frontier.contains(low);
                boolean highInFrontier = frontier.contains(high);
                if (!lowInFrontier && !highInFrontier) continue; // not part of this hop's expansion

                UUID other = lowInFrontier ? high : low;

                // Invariant 21/22: both endpoints must be visible; a hidden
                // node is never used as an invisible intermediary.
                if (!authorizationService.canSee(principalId, other)) {
                    continue;
                }

                String edgeKey = low + "|" + high;
                if (!edgesByKey.containsKey(edgeKey)) {
                    if (edgesByKey.size() >= traversalProperties.getMaxExploredEdges()) {
                        edgeBudgetExhausted = true;
                    } else {
                        edgesByKey.put(edgeKey, row);
                    }
                }

                if (!distance.containsKey(other)) {
                    if (exploredNodes >= traversalProperties.getMaxExploredNodes()) {
                        nodeBudgetExhausted = true;
                    } else {
                        distance.put(other, hop);
                        nextFrontier.add(other);
                        exploredNodes++;
                    }
                }
            }
            frontier = nextFrontier;
            if (nodeBudgetExhausted || edgeBudgetExhausted) break;
        }

        // ---- Rank & cap for the RESPONSE/UX budget (separate from the compute budget above) ----
        List<UUID> otherIds = new ArrayList<>(distance.keySet());
        otherIds.remove(ownerId);

        Map<UUID, BigDecimal> bestEdgeWeightPerNode = new HashMap<>();
        for (NeighborEdgeRow e : edgesByKey.values()) {
            BigDecimal amt = e.volumeAmount() == null ? BigDecimal.ZERO : e.volumeAmount();
            bestEdgeWeightPerNode.merge(e.businessLowId(), amt, BigDecimal::max);
            bestEdgeWeightPerNode.merge(e.businessHighId(), amt, BigDecimal::max);
        }

        otherIds.sort((a, b) -> {
            int hopCmp = Integer.compare(distance.get(a), distance.get(b));
            if (hopCmp != 0) return hopCmp;
            BigDecimal wa = bestEdgeWeightPerNode.getOrDefault(a, BigDecimal.ZERO);
            BigDecimal wb = bestEdgeWeightPerNode.getOrDefault(b, BigDecimal.ZERO);
            return wb.compareTo(wa); // strongest first
        });

        int totalOthers = otherIds.size();
        int cappedTotal = Math.min(totalOthers, traversalProperties.getMaxReturnedNodes() - 1); // -1 reserves the owner
        int pageSize = traversalProperties.getDefaultPageSize();
        int from = Math.min(pageOffset, cappedTotal);
        int to = Math.min(from + pageSize, cappedTotal);

        List<UUID> pageIds = new ArrayList<>();
        pageIds.add(ownerId);
        pageIds.addAll(otherIds.subList(from, to));

        Map<UUID, NetworkBusiness> businessById = new HashMap<>();
        businessRepository.findAllById(pageIds).forEach(b -> businessById.put(b.getNetworkBusinessId(), b));

        List<NodeDto> nodes = new ArrayList<>();
        for (UUID id : pageIds) {
            NetworkBusiness b = businessById.get(id);
            if (b == null) continue;
            nodes.add(new NodeDto(id, b.getDisplayName(), distance.get(id)));
        }

        Set<UUID> pageIdSet = new HashSet<>(pageIds);
        List<EdgeDto> edges = edgesByKey.values().stream()
                .filter(e -> pageIdSet.contains(e.businessLowId()) && pageIdSet.contains(e.businessHighId()))
                .sorted((x, y) -> {
                    BigDecimal xa = x.volumeAmount() == null ? BigDecimal.ZERO : x.volumeAmount();
                    BigDecimal ya = y.volumeAmount() == null ? BigDecimal.ZERO : y.volumeAmount();
                    return ya.compareTo(xa);
                })
                .limit(traversalProperties.getMaxReturnedEdges())
                .map(e -> new EdgeDto(e.businessLowId(), e.businessHighId(), e.volumeAmount(), e.transactionCount(), e.lastTransactionAt()))
                .toList();

        boolean truncated = nodeBudgetExhausted || edgeBudgetExhausted || totalOthers > cappedTotal;
        String nextCursor = to < cappedTotal ? String.valueOf(to) : null;

        BudgetMetadata budget = new BudgetMetadata(exploredNodes, edgesByKey.size(), nodeBudgetExhausted, edgeBudgetExhausted, false);

        return new NetworkViewResponse(nodes, edges, nextCursor, truncated, budget);
    }

    // ------------------------------------------------------------------
    // FR2 - shortest path by hop count
    // ------------------------------------------------------------------

    public PathResponse shortestPath(UUID fromId, UUID toId, String principalId, Integer requestedMaxDepth) {
        int maxDepth = requestedMaxDepth != null ? requestedMaxDepth : pathSearchProperties.getMaxDepth();
        if (maxDepth < 1 || maxDepth > pathSearchProperties.getMaxDepth()) {
            throw new BadRequestException("INVALID_DEPTH",
                    "maxDepth must be between 1 and " + pathSearchProperties.getMaxDepth());
        }
        // Endpoints must be visible; otherwise 404 NOT_FOUND (never leak existence).
        authorizationService.requireView(principalId, fromId);
        authorizationService.requireView(principalId, toId);

        if (fromId.equals(toId)) {
            return new PathResponse(List.of(fromId), 0, List.of());
        }

        Map<UUID, UUID> predecessor = new HashMap<>();
        Map<UUID, NeighborRow> edgeToPredecessor = new HashMap<>();
        Set<UUID> visited = new HashSet<>();
        visited.add(fromId);
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(fromId);
        Map<UUID, Integer> depthOf = new HashMap<>();
        depthOf.put(fromId, 0);

        int exploredNodes = 1;
        boolean found = false;

        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            int currentDepth = depthOf.get(current);
            if (currentDepth >= maxDepth) continue;

            List<NeighborRow> rows = viewRepository.neighborsOf(current);
            for (NeighborRow row : rows) {
                UUID other = row.counterpartId();
                if (visited.contains(other)) continue;

                // A19/invariant 22: a hidden node cannot be used as an
                // invisible intermediary -- and its absence is indistinguishable
                // from a genuine dead end.
                if (!authorizationService.canSee(principalId, other)) continue;

                if (exploredNodes >= pathSearchProperties.getMaxExploredNodes()) {
                    break;
                }

                visited.add(other);
                predecessor.put(other, current);
                edgeToPredecessor.put(other, row);
                depthOf.put(other, currentDepth + 1);
                exploredNodes++;

                if (other.equals(toId)) {
                    found = true;
                    break;
                }
                queue.add(other);
            }
            if (found) break;
        }

        if (!found) {
            // Deliberately the same response whether truly disconnected within
            // budget or blocked only by a hidden intermediary (A19).
            throw new NotFoundException("NOT_FOUND_WITHIN_DEPTH",
                    "No authorized path found between " + fromId + " and " + toId + " within depth " + maxDepth);
        }

        LinkedList<UUID> path = new LinkedList<>();
        List<EdgeDto> edges = new LinkedList<>();
        UUID cursor = toId;
        while (cursor != null && !cursor.equals(fromId)) {
            path.addFirst(cursor);
            NeighborRow row = edgeToPredecessor.get(cursor);
            UUID predecessorId = predecessor.get(cursor);
            edges.add(0, new EdgeDto(RelationshipService.low(predecessorId, cursor), RelationshipService.high(predecessorId, cursor),
                    row.volumeAmount(), row.transactionCount(), row.lastTransactionAt()));
            cursor = predecessorId;
        }
        path.addFirst(fromId);

        return new PathResponse(path, path.size() - 1, edges);
    }
}
