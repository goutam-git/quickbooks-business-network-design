package com.quickbooks.biznetwork.resolution.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "identity_resolution_candidate")
@Getter
@Setter
@NoArgsConstructor
public class IdentityResolutionCandidate {

    @EmbeddedId
    private IdentityResolutionCandidateId id;

    @Column(name = "rank", nullable = false)
    private int rank;

    @Column(name = "score", nullable = false)
    private double score;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "evidence", columnDefinition = "jsonb")
    private JsonNode evidence;

    @Column(name = "selected", nullable = false)
    private boolean selected;

    public IdentityResolutionCandidate(IdentityResolutionCandidateId id, int rank, double score, JsonNode evidence, boolean selected) {
        this.id = id;
        this.rank = rank;
        this.score = score;
        this.evidence = evidence;
        this.selected = selected;
    }
}
