package com.quickbooks.biznetwork.relationship.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the derived, read-only business_relationship_view (Section 8.3).
 * Deliberately implemented with plain JDBC rather than a JPA @Entity,
 * since the view has no primary key and is never written to directly.
 */
@Repository
public class BusinessRelationshipViewRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public BusinessRelationshipViewRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** All neighbors of a single business, oriented outward from it. */
    public List<NeighborRow> neighborsOf(UUID businessId) {
        String sql = """
                SELECT CASE WHEN business_low_id = :id THEN business_high_id ELSE business_low_id END AS counterpart_id,
                       volume_amount, transaction_count, last_transaction_at
                FROM business_relationship_view
                WHERE business_low_id = :id OR business_high_id = :id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource("id", businessId);
        return jdbc.query(sql, params, (rs, rowNum) -> new NeighborRow(
                UUID.fromString(rs.getString("counterpart_id")),
                rs.getBigDecimal("volume_amount"),
                rs.getLong("transaction_count"),
                toInstant(rs.getTimestamp("last_transaction_at"))
        ));
    }

    /** Batch neighbor lookup for a frontier of nodes during BFS -- avoids N+1 round-trips. */
    public List<NeighborEdgeRow> neighborsOfAny(Set<UUID> businessIds) {
        if (businessIds.isEmpty()) return List.of();
        String sql = """
                SELECT business_low_id, business_high_id, volume_amount, transaction_count, last_transaction_at
                FROM business_relationship_view
                WHERE business_low_id IN (:ids) OR business_high_id IN (:ids)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource("ids", businessIds);
        return jdbc.query(sql, params, (rs, rowNum) -> new NeighborEdgeRow(
                UUID.fromString(rs.getString("business_low_id")),
                UUID.fromString(rs.getString("business_high_id")),
                rs.getBigDecimal("volume_amount"),
                rs.getLong("transaction_count"),
                toInstant(rs.getTimestamp("last_transaction_at"))
        ));
    }

    private static java.time.Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public record NeighborEdgeRow(
            UUID businessLowId,
            UUID businessHighId,
            BigDecimal volumeAmount,
            long transactionCount,
            java.time.Instant lastTransactionAt
    ) {
    }
}
