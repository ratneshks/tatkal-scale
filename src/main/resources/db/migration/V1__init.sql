-- Tatkal-Scale schema. Flyway owns this; Hibernate runs with ddl-auto=validate.

CREATE TABLE show (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    starts_at   TIMESTAMPTZ  NOT NULL,
    total_seats INT          NOT NULL CHECK (total_seats >= 0)
);

CREATE TABLE seat (
    id              BIGSERIAL PRIMARY KEY,
    show_id         BIGINT       NOT NULL REFERENCES show (id),
    seat_no         VARCHAR(20)  NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
    held_by         VARCHAR(100),
    hold_token      UUID,
    hold_expires_at TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,      -- optimistic locking (@Version)
    CONSTRAINT uq_seat_show_seatno UNIQUE (show_id, seat_no),
    -- A HELD seat must always carry its holder, token and expiry.
    CONSTRAINT ck_seat_hold_fields CHECK (
        status <> 'HELD' OR (held_by IS NOT NULL AND hold_token IS NOT NULL AND hold_expires_at IS NOT NULL)
    )
);

CREATE INDEX ix_seat_show_status ON seat (show_id, status);
-- Partial index: the sweeper only ever scans HELD seats by expiry.
CREATE INDEX ix_seat_hold_expiry ON seat (hold_expires_at) WHERE status = 'HELD';

CREATE TABLE booking (
    id         BIGSERIAL PRIMARY KEY,
    seat_id    BIGINT       NOT NULL REFERENCES seat (id),
    user_id    VARCHAR(100) NOT NULL,
    hold_token UUID         NOT NULL UNIQUE,
    status     VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'EXPIRED', 'CANCELLED')),
    strategy   VARCHAR(20)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_booking_seat ON booking (seat_id);

-- THE last line of defence: at most one *live* booking per seat, enforced by the database
-- no matter what the Java code does. Expired/cancelled history rows are allowed to repeat.
CREATE UNIQUE INDEX ux_booking_one_active_per_seat
    ON booking (seat_id)
    WHERE status IN ('PENDING_PAYMENT', 'CONFIRMED');

CREATE TABLE stampede_run (
    id                  BIGSERIAL PRIMARY KEY,
    strategy            VARCHAR(20) NOT NULL,
    mode                VARCHAR(20) NOT NULL,
    threads             INT         NOT NULL,
    seats               INT         NOT NULL,
    db_guard            BOOLEAN     NOT NULL,
    virtual_threads     BOOLEAN     NOT NULL,
    think_time_ms       INT         NOT NULL,
    successes           INT         NOT NULL,
    sold_out            INT         NOT NULL,
    retries_exhausted   INT         NOT NULL,
    lock_timeouts       INT         NOT NULL,
    db_guard_rejections INT         NOT NULL,
    errors              INT         NOT NULL,
    total_retries       BIGINT      NOT NULL,
    active_bookings     INT         NOT NULL,
    oversold_seats      INT         NOT NULL,
    extra_bookings      INT         NOT NULL,
    p50_ms              DOUBLE PRECISION NOT NULL,
    p95_ms              DOUBLE PRECISION NOT NULL,
    p99_ms              DOUBLE PRECISION NOT NULL,
    max_ms              DOUBLE PRECISION NOT NULL,
    wall_ms             DOUBLE PRECISION NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL
);
