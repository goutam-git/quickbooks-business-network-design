package com.quickbooks.biznetwork.resolution.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NameSimilarityTest {

    @Test
    void exactNameScoresOne() {
        assertThat(NameSimilarity.similarity("Acme Supplies Pvt Ltd", "Acme Supplies Pvt Ltd")).isEqualTo(1.0);
    }

    @Test
    void normalizedLegalSuffixStillScoresHigh() {
        // "Pvt Ltd" is stripped by normalize(), so this should be near-identical
        // to the exact-match case even though the raw strings differ.
        double score = NameSimilarity.similarity("Acme Supplies Pvt Ltd", "Acme Supplies");
        assertThat(score).isGreaterThanOrEqualTo(0.95);
    }

    @Test
    void weaklyRelatedNamesScoreLow() {
        double score = NameSimilarity.similarity("Acme Supplies Pvt Ltd", "Zenith Freight Solutions");
        assertThat(score).isLessThan(0.4);
    }

    @Test
    void normalizeIsCaseAndPunctuationInsensitive() {
        assertThat(NameSimilarity.normalize("ACME, Supplies & Co.")).isEqualTo(NameSimilarity.normalize("acme supplies co"));
    }
}
