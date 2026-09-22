-- Seed data so the API is immediately testable via Postman without
-- first having to create businesses by hand.
-- Principal used throughout the Postman collection: "demo-user"

INSERT INTO network_business (network_business_id, display_name, status) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Acme Supplies Pvt Ltd', 'ACTIVE'),
    ('22222222-2222-2222-2222-222222222222', 'Bharat Traders',        'ACTIVE'),
    ('33333333-3333-3333-3333-333333333333', 'Chennai Textiles',      'ACTIVE'),
    ('44444444-4444-4444-4444-444444444444', 'Delta Logistics',       'ACTIVE'),
    ('55555555-5555-5555-5555-555555555555', 'Everest Hardware',      'ACTIVE');

INSERT INTO source_business_ref (source_system, source_entity_type, source_entity_id, network_business_id, source_display_name) VALUES
    ('QBO', 'VENDOR',   'V-1001', '11111111-1111-1111-1111-111111111111', 'Acme Supplies Pvt Ltd'),
    ('QBO', 'CUSTOMER', 'C-2001', '22222222-2222-2222-2222-222222222222', 'Bharat Traders'),
    ('QBO', 'VENDOR',   'V-1002', '33333333-3333-3333-3333-333333333333', 'Chennai Textiles'),
    ('QBO', 'CUSTOMER', 'C-2002', '44444444-4444-4444-4444-444444444444', 'Delta Logistics'),
    ('QBO', 'VENDOR',   'V-1003', '55555555-5555-5555-5555-555555555555', 'Everest Hardware');

-- Give the demo principal MANAGE on every seeded business so the full
-- Postman collection works out of the box.
INSERT INTO network_business_access (principal_id, network_business_id, permission)
SELECT 'demo-user', network_business_id, 'MANAGE' FROM network_business;

-- A couple of relationships so /network and /relationships/path have data.
INSERT INTO relationship_assertion (business_low_id, business_high_id, source_type, source_reference, status, created_by)
SELECT LEAST(a,b), GREATEST(a,b), 'USER', gen_random_uuid()::text, 'ACTIVE', 'demo-user' FROM (
    VALUES
    ('11111111-1111-1111-1111-111111111111'::uuid, '22222222-2222-2222-2222-222222222222'::uuid),
    ('22222222-2222-2222-2222-222222222222'::uuid, '33333333-3333-3333-3333-333333333333'::uuid),
    ('33333333-3333-3333-3333-333333333333'::uuid, '44444444-4444-4444-4444-444444444444'::uuid)
) AS t(a, b);

INSERT INTO relationship_direction (seller_business_id, buyer_business_id, transaction_count, transaction_amount, last_transaction_at) VALUES
    ('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 12, 450000.00, now() - interval '3 days'),
    ('22222222-2222-2222-2222-222222222222', '33333333-3333-3333-3333-333333333333', 5,  120000.00, now() - interval '10 days'),
    ('33333333-3333-3333-3333-333333333333', '44444444-4444-4444-4444-444444444444', 20, 980000.00, now() - interval '1 days');
