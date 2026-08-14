-- Seed: 100 registered users, 5 of them inactive.
--
-- Special addresses drive the mail simulator behaviour:
--   temp-fail@... -> transient failure (2x), succeeds on 3rd attempt
--   bounce@...    -> permanent rejection, no retry
--   slow@...      -> high latency, keeps the campaign visibly PROCESSING
--
-- All emails are lowercase (enforced by the CHECK constraint on users in V1).

-- 9 named users with fixed UUIDs (referenced by tests, README and the Postman demo)
INSERT INTO users (id, email, active) VALUES
    ('11111111-1111-1111-1111-111111111101', 'john@example.com',      TRUE),
    ('11111111-1111-1111-1111-111111111102', 'jane@example.com',      TRUE),
    ('11111111-1111-1111-1111-111111111103', 'alice@example.com',     TRUE),
    ('11111111-1111-1111-1111-111111111104', 'bob@example.com',       TRUE),
    ('11111111-1111-1111-1111-111111111105', 'carol@example.com',     TRUE),
    ('11111111-1111-1111-1111-111111111106', 'temp-fail@example.com', TRUE),
    ('11111111-1111-1111-1111-111111111107', 'bounce@example.com',    TRUE),
    ('11111111-1111-1111-1111-111111111108', 'slow@example.com',      TRUE),
    ('11111111-1111-1111-1111-111111111109', 'inactive@example.com',  FALSE);

-- Bulk users user10..user100 (91 rows, deterministic UUIDs).
-- user97..user100 are inactive -> together with inactive@example.com that is
-- 5 inactive users out of 100 total.
INSERT INTO users (id, email, active)
SELECT
    ('22222222-2222-2222-2222-' || lpad(gs::text, 12, '0'))::uuid,
    'user' || gs || '@example.com',
    gs < 97
FROM generate_series(10, 100) AS gs;
