package com.company.librarymanager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Money owed on one loan.
 *
 * <p>At most one fine per issue, enforced by a unique index on {@code issue_id}.
 * The amount is copied from the issue at assessment time rather than recomputed
 * on display, so a later change to the rate cannot retroactively alter what a
 * member was already quoted.
 */
@Entity
@Table(name = "fines")
public class Fine {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issue_id", nullable = false, unique = true)
    private BookIssue issue;

    @Column(name = "amount", nullable = false, precision = 6, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid", nullable = false)
    private boolean paid;

    @Column(name = "calculated_at", nullable = false, updatable = false)
    private Instant calculatedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paid_by_id")
    private User paidBy;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (calculatedAt == null) {
            calculatedAt = Instant.now();
        }
    }

    /** What is still owed: zero once settled. */
    public BigDecimal outstanding() {
        return paid ? BigDecimal.ZERO : amount;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public BookIssue getIssue() {
        return issue;
    }

    public void setIssue(BookIssue issue) {
        this.issue = issue;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public boolean isPaid() {
        return paid;
    }

    public void setPaid(boolean paid) {
        this.paid = paid;
    }

    public Instant getCalculatedAt() {
        return calculatedAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(Instant paidAt) {
        this.paidAt = paidAt;
    }

    public User getPaidBy() {
        return paidBy;
    }

    public void setPaidBy(User paidBy) {
        this.paidBy = paidBy;
    }
}
