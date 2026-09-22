package com.quickbooks.biznetwork.resolution.client;

import java.util.List;

public record AiRankResponse(String backend, String model, List<RankedResult> results) {
    public record RankedResult(String id, double score) {
    }
}
