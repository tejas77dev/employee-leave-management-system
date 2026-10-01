-- Initial schema for the leave management system.
--
-- Ports of prisma/schema.prisma and its two migration files. The differences
-- from the original SQLite schema are deliberate:
--   * day counts are DECIMAL(5,2) rather than REAL, so half-day arithmetic
--     stays exact instead of relying on rounding to hide float drift;
--   * enums are VARCHAR + CHECK instead of unconstrained TEXT, so MySQL
--     itself rejects a value outside the domain;
--   * leave dates are DATE rather than a UTC-midnight timestamp, because a
--     leave request is a calendar range and carries no time of day.

CREATE TABLE users (
    id            VARCHAR(36)     NOT NULL,
    email         VARCHAR(191) NOT NULL,
    name          VARCHAR(191) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16)  NOT NULL DEFAULT 'EMPLOYEE',
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('EMPLOYEE', 'HR'))
) ENGINE = InnoDB;

CREATE TABLE sessions (
    id         VARCHAR(36)    NOT NULL,
    token      VARCHAR(64)    NOT NULL,
    user_id    VARCHAR(36)    NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_sessions_token UNIQUE (token),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE TABLE leave_types (
    id           VARCHAR(36)     NOT NULL,
    name         VARCHAR(191) NOT NULL,
    description  VARCHAR(255) NULL,
    default_days DECIMAL(5, 2) NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_leave_types_name UNIQUE (name)
) ENGINE = InnoDB;

CREATE TABLE leave_balances (
    id            VARCHAR(36)     NOT NULL,
    user_id       VARCHAR(36)     NOT NULL,
    leave_type_id VARCHAR(36)     NOT NULL,
    year          INT          NOT NULL,
    entitled      DECIMAL(5, 2) NOT NULL DEFAULT 0,
    used          DECIMAL(5, 2) NOT NULL DEFAULT 0,
    pending       DECIMAL(5, 2) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_leave_balances_user_type_year UNIQUE (user_id, leave_type_id, year),
    CONSTRAINT fk_leave_balances_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_leave_balances_leave_type FOREIGN KEY (leave_type_id) REFERENCES leave_types (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE TABLE leave_requests (
    id            VARCHAR(36)     NOT NULL,
    user_id       VARCHAR(36)     NOT NULL,
    leave_type_id VARCHAR(36)     NOT NULL,
    start_date    DATE         NOT NULL,
    end_date      DATE         NOT NULL,
    days          DECIMAL(5, 2) NOT NULL,
    is_half_day   BOOLEAN      NOT NULL DEFAULT FALSE,
    part_of_day   VARCHAR(16)  NULL,
    reason        VARCHAR(500) NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    reviewed_by_id VARCHAR(36)    NULL,
    review_note   VARCHAR(500) NULL,
    reviewed_at   DATETIME(6)  NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_leave_requests_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_leave_requests_part_of_day CHECK (part_of_day IN ('MORNING', 'AFTERNOON')),
    -- A full-day request must not carry a half, and a half-day must carry one.
    CONSTRAINT ck_leave_requests_half_day_part CHECK (
        (is_half_day = FALSE AND part_of_day IS NULL)
        OR (is_half_day = TRUE AND part_of_day IS NOT NULL)
    ),
    CONSTRAINT ck_leave_requests_dates CHECK (end_date >= start_date),
    -- A half-day covers exactly one calendar date, so the range cannot span more.
    CONSTRAINT ck_leave_requests_half_day_span CHECK (is_half_day = FALSE OR end_date = start_date),
    CONSTRAINT fk_leave_requests_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_leave_requests_leave_type FOREIGN KEY (leave_type_id) REFERENCES leave_types (id),
    CONSTRAINT fk_leave_requests_reviewer FOREIGN KEY (reviewed_by_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB;

CREATE TABLE audit_logs (
    id          VARCHAR(36)     NOT NULL,
    actor_id    VARCHAR(36)     NULL,
    actor_name  VARCHAR(191) NOT NULL,
    action      VARCHAR(40)  NOT NULL,
    entity_type VARCHAR(40)  NOT NULL,
    entity_id   VARCHAR(36)     NULL,
    summary     VARCHAR(500) NOT NULL,
    metadata    JSON         NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_audit_logs_actor FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB;
