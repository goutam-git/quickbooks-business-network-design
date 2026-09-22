package com.quickbooks.biznetwork.resolution.client;

import java.util.List;

public record AiRankRequest(String query, List<Candidate> candidates) {
    public record Candidate(String id, String name) {
    }
}
