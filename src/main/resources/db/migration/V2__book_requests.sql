-- Lending requests: a member asks for a title, the desk decides.
--
-- A request is not a loan. Only approval takes a copy off the shelf, and it
-- records the loan it produced in issue_id so the two can be read together.
CREATE TABLE book_requests (
    id VARCHAR(36) NOT NULL,
    book_id VARCHAR(36) NOT NULL,
    member_id VARCHAR(36) NOT NULL,
    requested_by_id VARCHAR(36) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    note VARCHAR(500) NULL,
    decided_by_id VARCHAR(36) NULL,
    decided_at DATETIME(6) NULL,
    decision_note VARCHAR(500) NULL,
    issue_id VARCHAR(36) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_book_requests_status CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT ck_book_requests_decided CHECK (
        (status = 'PENDING' AND decided_at IS NULL) OR status <> 'PENDING'),
    CONSTRAINT fk_book_requests_book FOREIGN KEY (book_id) REFERENCES books (id),
    CONSTRAINT fk_book_requests_member FOREIGN KEY (member_id) REFERENCES members (id),
    CONSTRAINT fk_book_requests_requester FOREIGN KEY (requested_by_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_book_requests_decider FOREIGN KEY (decided_by_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_book_requests_issue FOREIGN KEY (issue_id) REFERENCES book_issues (id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- status first because the desk reads the queue by nothing else, and oldest
-- first within it, so a request cannot be starved by a later one for the same
-- title. member_id and book_id pair up for "does this member already have one
-- of these waiting", which is the check that keeps one member from queueing
-- the same title five times.
CREATE INDEX idx_book_requests_status ON book_requests(status, created_at);
CREATE INDEX idx_book_requests_member_status ON book_requests(member_id, status);
CREATE INDEX idx_book_requests_book_status ON book_requests(book_id, status);
CREATE INDEX idx_book_requests_created_at ON book_requests(created_at);