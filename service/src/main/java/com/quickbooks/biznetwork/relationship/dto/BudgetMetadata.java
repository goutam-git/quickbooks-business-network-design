package com.quickbooks.biznetwork.relationship.dto;

public record BudgetMetadata(
        int exploredNodes,
        int exploredEdges,
        boolean nodeBudgetExhausted,
        boolean edgeBudgetExhausted,
        boolean timedOut
) {
}
