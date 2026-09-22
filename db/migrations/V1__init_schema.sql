-- =====================================================================
-- QuickBooks Business Network - V1 schema
-- Mirrors design doc Section 8 (Physical Data Model)
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------
-- 8.1 Identity
-- ---------------------------------------------------------------------

CREATE TABLE network_business (
    network_business_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name            VARCHAR(255) NOT NULL,
    status                   VARCHAR(32) NOT NULL
                             CHECK (status IN ('ACTIVE','PENDING_SOURCE','SOURCE_CREATION_FAILED','SUPERSEDED')),
    canonical_business_id   UUID NULL REFERENCES network_business(network_business_id),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                  BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_no_self_cycle CHECK (network_business_id <> canonical_business_id)
);

CREATE INDEX idx_network_business_status ON network_business(status);
CREATE INDEX idx_network_business_canonical ON network_business(canonical_business_id);

CREATE TABLE source_business_ref (
    source_system            VARCHAR(32) NOT NULL,
    source_entity_type       VARCHAR(16) NOT NULL CHECK (source_entity_type IN ('VENDOR','CUSTOMER')),
    source_entity_id         VARCHAR(128) NOT NULL,
    network_business_id      UUID NOT NULL REFERENCES network_business(network_business_id),
    source_display_name      VARCHAR(255) NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source_system, source_entity_type, source_entity_id)
);

CREATE INDEX idx_source_business_ref_nb ON source_business_ref(network_business_id);

CREATE TABLE network_business_access (
    principal_id              VARCHAR(128) NOT NULL,
    network_business_id       UUID NOT NULL REFERENCES network_business(network_business_id),
    permission                 VARCHAR(16) NOT NULL CHECK (permission IN ('VIEW','MANAGE','ADMIN')),
    PRIMARY KEY (principal_id, network_business_id)
);

CREATE INDEX idx_nba_business_principal ON network_business_access(network_business_id, principal_id);

CREATE TABLE business_add_operation (
    operation_id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key             VARCHAR(255) NOT NULL UNIQUE,
    owner_business_id           UUID NOT NULL REFERENCES network_business(network_business_id),
    network_business_id         UUID NULL REFERENCES network_business(network_business_id),
    resolution_id                UUID NULL,
    relationship_id               UUID NULL, -- demo-only convenience column (not in the original design doc schema) so a repeated Idempotency-Key can replay the exact prior result without recomputation
    state                        VARCHAR(32) NOT NULL
                                 CHECK (state IN ('RESOLVING','AWAITING_CONFIRMATION','SOURCE_PENDING','SOURCE_CREATED','RELATIONSHIP_CREATED','FAILED')),
    last_error_code              VARCHAR(64) NULL,
    request_payload              JSONB NULL,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 8.2 Identity resolution
-- ---------------------------------------------------------------------

CREATE TABLE identity_resolution (
    resolution_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    input_descriptor            JSONB NOT NULL,
    decision                     VARCHAR(16) NOT NULL CHECK (decision IN ('MATCH','NO_MATCH','CONFIRM_REQUIRED')),
    selected_business_id        UUID NULL REFERENCES network_business(network_business_id),
    method                       VARCHAR(64) NOT NULL,
    actor_id                     VARCHAR(128) NULL,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE identity_resolution_candidate (
    resolution_id               UUID NOT NULL REFERENCES identity_resolution(resolution_id),
    candidate_business_id       UUID NOT NULL REFERENCES network_business(network_business_id),
    rank                         INT NOT NULL,
    score                        DOUBLE PRECISION NOT NULL,
    evidence                     JSONB NULL,
    selected                     BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (resolution_id, candidate_business_id)
);

-- ---------------------------------------------------------------------
-- 8.3 Relationships
-- ---------------------------------------------------------------------

CREATE TABLE relationship_assertion (
    relationship_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_low_id               UUID NOT NULL REFERENCES network_business(network_business_id),
    business_high_id              UUID NOT NULL REFERENCES network_business(network_business_id),
    source_type                   VARCHAR(16) NOT NULL CHECK (source_type IN ('USER','TRANSACTION','IMPORT')),
    source_reference               VARCHAR(255) NOT NULL,
    status                         VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','RETRACTED')),
    created_by                     VARCHAR(128) NOT NULL,
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    retracted_by                   VARCHAR(128) NULL,
    retracted_at                   TIMESTAMPTZ NULL,
    retraction_reason              VARCHAR(64) NULL,
    CONSTRAINT chk_low_high_order CHECK (business_low_id < business_high_id),
    CONSTRAINT uq_assertion UNIQUE (business_low_id, business_high_id, source_type, source_reference)
);

CREATE INDEX idx_assertion_low ON relationship_assertion(business_low_id);
CREATE INDEX idx_assertion_high ON relationship_assertion(business_high_id);
CREATE INDEX idx_assertion_status ON relationship_assertion(status);

CREATE TABLE relationship_direction (
    seller_business_id            UUID NOT NULL REFERENCES network_business(network_business_id),
    buyer_business_id              UUID NOT NULL REFERENCES network_business(network_business_id),
    transaction_count               BIGINT NOT NULL DEFAULT 0,
    transaction_amount              NUMERIC(18,2) NOT NULL DEFAULT 0,
    last_transaction_at              TIMESTAMPTZ NULL,
    version                          BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (seller_business_id, buyer_business_id)
);

CREATE INDEX idx_direction_buyer ON relationship_direction(buyer_business_id);

-- business_relationship_view is defined separately in db/views/ (see V2__business_relationship_view.sql)
-- ---------------------------------------------------------------------
-- 8.4 Merge & consolidation
-- ---------------------------------------------------------------------

CREATE TABLE identity_merge_event (
    event_id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merge_operation_id          UUID NOT NULL,
    source_business_id          UUID NOT NULL REFERENCES network_business(network_business_id),
    target_business_id          UUID NOT NULL REFERENCES network_business(network_business_id),
    event_type                   VARCHAR(32) NOT NULL
                                 CHECK (event_type IN ('MERGE_PROPOSED','MERGE_CONFIRMED','CONSOLIDATION_STARTED','CONSOLIDATION_COMPLETED','MERGE_REVERSE_REQUESTED','MERGE_REVERSED')),
    actor_id                     VARCHAR(128) NULL,
    reason                       VARCHAR(255) NULL,
    metadata                     JSONB NULL,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_merge_event_operation ON identity_merge_event(merge_operation_id);

CREATE TABLE merge_direction_snapshot (
    merge_operation_id            UUID NOT NULL,
    business_id                    UUID NOT NULL,
    counterparty_business_id       UUID NOT NULL,
    direction                       VARCHAR(8) NOT NULL CHECK (direction IN ('SELLER','BUYER')),
    transaction_count                BIGINT NOT NULL,
    transaction_amount               NUMERIC(18,2) NOT NULL,
    last_transaction_at              TIMESTAMPTZ NULL,
    captured_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (merge_operation_id, business_id, counterparty_business_id, direction)
);

CREATE TABLE merge_assertion_snapshot (
    merge_operation_id             UUID NOT NULL,
    relationship_id                 UUID NOT NULL,
    business_low_id                  UUID NOT NULL,
    business_high_id                 UUID NOT NULL,
    source_type                      VARCHAR(16) NOT NULL,
    source_reference                  VARCHAR(255) NOT NULL,
    status                            VARCHAR(16) NOT NULL,
    created_by                        VARCHAR(128) NOT NULL,
    created_at                        TIMESTAMPTZ NOT NULL,
    retracted_by                      VARCHAR(128) NULL,
    retracted_at                      TIMESTAMPTZ NULL,
    captured_at                       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (merge_operation_id, relationship_id)
);
