package com.company.librarymanager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One loan of one copy of a title to one member.
 *
 * <p>A row is never deleted. Returning or writing a book off closes it by
 * setting {@code returnDate} and {@link IssueStatus}, which keeps the history
 * of who had what and when, and keeps {@code fines} referencing something real.
 *
 * <p>{@code fineAmount} is the assessed total and is a running figure: it is
 * filled in on return and never revised afterwards, so a fine cannot quietly
 * shrink after the member has seen it quoted.
 */
@Entity
@Table(name = "book_issues")
public class BookIssue {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    /** The staff member who handed the book over, or null if the row predates it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issued_by_id")
    private User issuedBy;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "return_date")
    private LocalDate returnDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private IssueStatus status = IssueStatus.ISSUED;

    @Column(name = "fine_amount", nullable = false, precision = 6, scale = 2)
    private BigDecimal fineAmount = BigDecimal.ZERO;

    @Column(name = "remarks", length = 500)
    private String remarks;

    @OneToOne(fetch = FetchType.LAZY, mappedBy = "issue")
    private Fine fine;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** A loan still out, whether or not it is late. */
    public boolean isOpen() {
        return status == IssueStatus.ISSUED;
    }

    /**
     * Whole days past the due date as of {@code today}, never negative.
     *
     * <p>Counted from the due date rather than the issue date, so a book
     * returned on its due date owes nothing. An open loan is measured against
     * today, which is what makes an overdue list go stale on its own.
     */
    public long daysOverdue(LocalDate today) {
        if (!isOpen() && (returnDate == null || !returnDate.isAfter(dueDate))) {
            return 0;
        }
        LocalDate reference = returnDate != null ? returnDate : today;
        if (!reference.isAfter(dueDate)) {
            return 0;
        }
        return ChronoUnit.DAYS.between(dueDate, reference);
    }

    public boolean isOverdue(LocalDate today) {
        return daysOverdue(today) > 0;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Book getBook() {
        return book;
    }

    public void setBook(Book book) {
        this.book = book;
    }

    public Member getMember() {
        return member;
    }

    public void setMember(Member member) {
        this.member = member;
    }

    public User getIssuedBy() {
        return issuedBy;
    }

    public void setIssuedBy(User issuedBy) {
        this.issuedBy = issuedBy;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public void setIssueDate(LocalDate issueDate) {
        this.issueDate = issueDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public LocalDate getReturnDate() {
        return returnDate;
    }

    public void setReturnDate(LocalDate returnDate) {
        this.returnDate = returnDate;
    }

    public IssueStatus getStatus() {
        return status;
    }

    public void setStatus(IssueStatus status) {
        this.status = status;
    }

    public BigDecimal getFineAmount() {
        return fineAmount;
    }

    public void setFineAmount(BigDecimal fineAmount) {
        this.fineAmount = fineAmount;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public Fine getFine() {
        return fine;
    }

    public void setFine(Fine fine) {
        this.fine = fine;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
