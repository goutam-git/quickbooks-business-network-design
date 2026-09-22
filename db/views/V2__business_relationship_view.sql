-- Derived, read-only V1 serving view (Section 8.3). Never written to directly.
-- Edge existence comes from ACTIVE relationship_assertion rows; directional
-- transaction evidence (relationship_direction) is optional/additive.

CREATE VIEW business_relationship_view AS
WITH active_edges AS (
    SELECT DISTINCT business_low_id, business_high_id
    FROM relationship_assertion
    WHERE status = 'ACTIVE'
),
direction_totals AS (
    SELECT
        LEAST(seller_business_id, buyer_business_id)    AS business_low_id,
        GREATEST(seller_business_id, buyer_business_id) AS business_high_id,
        SUM(transaction_amount) AS volume_amount,
        SUM(transaction_count)  AS transaction_count,
        MAX(last_transaction_at) AS last_transaction_at
    FROM relationship_direction
    GROUP BY 1, 2
)
SELECT
    e.business_low_id,
    e.business_high_id,
    COALESCE(d.volume_amount, 0)     AS volume_amount,
    COALESCE(d.transaction_count, 0) AS transaction_count,
    d.last_transaction_at
FROM active_edges e
LEFT JOIN direction_totals d
  ON d.business_low_id = e.business_low_id
 AND d.business_high_id = e.business_high_id;