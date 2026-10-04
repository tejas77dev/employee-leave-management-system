-- Library Management System schema v1
CREATE TABLE users (
    id VARCHAR(36) NOT NULL,
    email VARCHAR(191) NOT NULL,
    name VARCHAR(191) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN','LIBRARIAN','MEMBER'))
) ENGINE=InnoDB;

CREATE TABLE sessions (
    id VARCHAR(36) NOT NULL,
    token VARCHAR(64) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_sessions_token UNIQUE (token),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE categories (
    id VARCHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(255) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_categories_name UNIQUE (name)
) ENGINE=InnoDB;

CREATE TABLE books (
    id VARCHAR(36) NOT NULL,
    isbn VARCHAR(20) NOT NULL,
    title VARCHAR(255) NOT NULL,
    author VARCHAR(255) NOT NULL,
    publisher VARCHAR(255) NULL,
    category_id VARCHAR(36) NULL,
    total_copies INT NOT NULL,
    available_copies INT NOT NULL,
    rack_no VARCHAR(50) NULL,
    published_year INT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_books_isbn UNIQUE (isbn),
    CONSTRAINT fk_books_category FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE SET NULL,
    CONSTRAINT ck_books_total_copies CHECK (total_copies >= 0),
    CONSTRAINT ck_books_available_copies CHECK (available_copies >= 0 AND available_copies <= total_copies)
) ENGINE=InnoDB;

CREATE TABLE members (
    id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NULL,
    member_id VARCHAR(20) NOT NULL,
    phone VARCHAR(20) NULL,
    department VARCHAR(100) NULL,
    address VARCHAR(255) NULL,
    max_books_allowed INT NOT NULL DEFAULT 3,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_members_user UNIQUE (user_id),
    CONSTRAINT uq_members_member_id UNIQUE (member_id),
    CONSTRAINT fk_members_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_members_max_books CHECK (max_books_allowed >= 0)
) ENGINE=InnoDB;

CREATE TABLE book_issues (
    id VARCHAR(36) NOT NULL,
    book_id VARCHAR(36) NOT NULL,
    member_id VARCHAR(36) NOT NULL,
    issued_by_id VARCHAR(36) NULL,
    issue_date DATE NOT NULL,
    due_date DATE NOT NULL,
    return_date DATE NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ISSUED',
    fine_amount DECIMAL(6,2) NOT NULL DEFAULT 0.00,
    remarks VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_book_issues_status CHECK (status IN ('ISSUED','RETURNED','LOST')),
    CONSTRAINT ck_book_issues_dates CHECK (due_date >= issue_date),
    CONSTRAINT fk_book_issues_book FOREIGN KEY (book_id) REFERENCES books (id),
    CONSTRAINT fk_book_issues_member FOREIGN KEY (member_id) REFERENCES members (id),
    CONSTRAINT fk_book_issues_issuer FOREIGN KEY (issued_by_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE fines (
    id VARCHAR(36) NOT NULL,
    issue_id VARCHAR(36) NOT NULL,
    amount DECIMAL(6,2) NOT NULL,
    paid BOOLEAN NOT NULL DEFAULT FALSE,
    calculated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    paid_at DATETIME(6) NULL,
    paid_by_id VARCHAR(36) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_fines_issue UNIQUE (issue_id),
    CONSTRAINT fk_fines_issue FOREIGN KEY (issue_id) REFERENCES book_issues (id) ON DELETE CASCADE,
    CONSTRAINT fk_fines_payer FOREIGN KEY (paid_by_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_fines_amount CHECK (amount >= 0)
) ENGINE=InnoDB;

CREATE TABLE audit_logs (
    id VARCHAR(36) NOT NULL,
    actor_id VARCHAR(36) NULL,
    actor_name VARCHAR(191) NOT NULL,
    action VARCHAR(40) NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id VARCHAR(36) NULL,
    summary VARCHAR(500) NOT NULL,
    metadata JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_audit_logs_actor FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE INDEX idx_books_title ON books(title);
CREATE INDEX idx_books_author ON books(author);
CREATE INDEX idx_books_isbn ON books(isbn);
CREATE INDEX idx_books_active ON books(active);
CREATE INDEX idx_books_category ON books(category_id);
CREATE INDEX idx_book_issues_member_status ON book_issues(member_id,status);
CREATE INDEX idx_book_issues_book_status ON book_issues(book_id,status);
CREATE INDEX idx_book_issues_due_date ON book_issues(due_date);
CREATE INDEX idx_book_issues_status ON book_issues(status);
CREATE INDEX idx_fines_paid ON fines(paid);
CREATE INDEX idx_members_member_id ON members(member_id);
CREATE INDEX idx_members_active ON members(active);
