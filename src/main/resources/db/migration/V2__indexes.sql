-- Indexes supporting the query paths in the application.
--
-- Ports the index sets from both Prisma migrations. Kept separate from V1 so
-- the table definitions stay readable and this set is easy to review as a
-- performance decision.

-- users
CREATE INDEX idx_users_role ON users (role);

-- sessions
CREATE INDEX idx_sessions_user_id ON sessions (user_id);
CREATE INDEX idx_sessions_expires_at ON sessions (expires_at);

-- leave_types: active-only listings drive the request form and dashboard.
CREATE INDEX idx_leave_types_active ON leave_types (active);

-- leave_balances: the composite unique index above already serves lookups by
-- (user, type, year); these cover the HR matrix and per-year rollups.
CREATE INDEX idx_leave_balances_year ON leave_balances (year);
CREATE INDEX idx_leave_balances_user_year ON leave_balances (user_id, year);
CREATE INDEX idx_leave_balances_type_year ON leave_balances (leave_type_id, year);

-- leave_requests: six access patterns, one per screen.
--   (user_id, status)         -> "My leave" filtered by state
--   (status)                   -> dashboard counts and the approval queue
--   (start_date, end_date)     -> overlap detection
--   (user_id, start_date)      -> per-employee history
--   (status, start_date)       -> approval queue ordered by date
--   (leave_type_id, status)    -> HR filtering by leave type
CREATE INDEX idx_leave_requests_user_status ON leave_requests (user_id, status);
CREATE INDEX idx_leave_requests_status ON leave_requests (status);
CREATE INDEX idx_leave_requests_date_range ON leave_requests (start_date, end_date);
CREATE INDEX idx_leave_requests_user_start ON leave_requests (user_id, start_date);
CREATE INDEX idx_leave_requests_status_start ON leave_requests (status, start_date);
CREATE INDEX idx_leave_requests_type_status ON leave_requests (leave_type_id, status);

-- audit_logs: the activity feed is read newest-first, optionally narrowed by
-- action, by actor, or by the entity that was changed.
CREATE INDEX idx_audit_logs_created_at ON audit_logs (created_at);
CREATE INDEX idx_audit_logs_actor_created ON audit_logs (actor_id, created_at);
CREATE INDEX idx_audit_logs_action ON audit_logs (action);
CREATE INDEX idx_audit_logs_entity ON audit_logs (entity_type, entity_id);
