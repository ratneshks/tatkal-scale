-- Show 1: a normal train for manual testing (40 seats).
INSERT INTO show (id, name, starts_at, total_seats)
VALUES (1, 'Rajdhani Express 12301 - Tatkal Quota', now() + interval '1 day', 40);

INSERT INTO seat (show_id, seat_no, status)
SELECT 1, 'B1-' || g, 'AVAILABLE' FROM generate_series(1, 40) AS g;

-- Show 2: the Stampede Arena. The simulator resets it to N seats before every run.
INSERT INTO show (id, name, starts_at, total_seats)
VALUES (2, 'Stampede Arena - Flash Sale', now() + interval '1 day', 5);

INSERT INTO seat (show_id, seat_no, status)
SELECT 2, 'A-' || g, 'AVAILABLE' FROM generate_series(1, 5) AS g;

-- Explicit ids were used above, so move the sequence past them.
SELECT setval('show_id_seq', (SELECT max(id) FROM show));
