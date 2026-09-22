package com.quickbooks.biznetwork.resolution.client;

import com.quickbooks.biznetwork.config.AiResolutionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

/**
 * Thin client for ai-resolution/ranking. This is the ONLY place the Java
 * service talks to the AI/ML enhancement layer, and it is deliberately
 * fail-soft: per Section 12's failure mode, an AI-enhancement outage must
 * never block resolution -- deterministic matching keeps working. Every
 * call site treats an empty Optional as "proceed deterministic-only."
 */
@Component
public class AiRankingClient {

    private static final Logger log = LoggerFactory.getLogger(AiRankingClient.class);

    private final AiResolutionProperties properties;
    private final RestTemplate restTemplate;

    public AiRankingClient(AiResolutionProperties properties, RestTemplate aiResolutionRestTemplate) {
        this.properties = properties;
        this.restTemplate = aiResolutionRestTemplate;
    }

    public Optional<AiRankResponse> rank(String query, List<AiRankRequest.Candidate> candidates) {
        if (!properties.isEnabled() || candidates.isEmpty()) {
            return Optional.empty();
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<AiRankRequest> entity = new HttpEntity<>(new AiRankRequest(query, candidates), headers);
            AiRankResponse response = restTemplate.postForObject(properties.getUrl(), entity, AiRankResponse.class);
            return Optional.ofNullable(response);
        } catch (RestClientException ex) {
            log.warn("ai-resolution/ranking unavailable ({}); proceeding deterministic-only", ex.getMessage());
            return Optional.empty();
        }
    }
}
