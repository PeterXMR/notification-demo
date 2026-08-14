-- always-fail@example.com: an active, registered user whose mailbox answers 451 on every
-- attempt (see SimulatedMailSender). It exists to make the RETRY BOUND demonstrable:
-- the recipient burns exactly notification.retry.max-attempts attempts and then ends
-- FAILED, proving retries are limited rather than endless.
--
-- Deliberately NOT part of the "all simulator behaviours" bulk campaign — it is used by
-- its own focused scenario so the expected failed count stays unambiguous.
INSERT INTO users (id, email, active) VALUES
    ('11111111-1111-1111-1111-111111111110', 'always-fail@example.com', TRUE);
