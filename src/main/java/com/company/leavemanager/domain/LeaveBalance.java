package com.company.leavemanager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An employee's day allowance for one leave type in one year.
 *
 * <p>This is the row that every leave decision competes for. {@code pending}
 * holds days reserved by submitted-but-unreviewed requests, so submitting a
 * request immediately stops those days being spent twice.
 */
@Entity
@Table(name = "leave_balances")
public class LeaveBalance {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leave_type_id", nullable = false)
    private LeaveType leaveType;

    @Column(name = "year", nullable = false)
    private int year;

    @Column(name = "entitled", nullable = false, precision = 5, scale = 2)
    private BigDecimal entitled = BigDecimal.ZERO;

    @Column(name = "used", nullable = false, precision = 5, scale = 2)
    private BigDecimal used = BigDecimal.ZERO;

    @Column(name = "pending", nullable = false, precision = 5, scale = 2)
    private BigDecimal pending = BigDecimal.ZERO;

    /** Entitlement not yet consumed or reserved. May go negative on overage. */
    public BigDecimal remaining() {
        return entitled.subtract(used).subtract(pending);
    }

    /** Days already spoken for, either taken or awaiting a decision. */
    public BigDecimal committed() {
        return used.add(pending);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public LeaveType getLeaveType() {
        return leaveType;
    }

    public void setLeaveType(LeaveType leaveType) {
        this.leaveType = leaveType;
    }

    public int getYear() {
        return year;
    }

    public void setYear(int year) {
        this.year = year;
    }

    public BigDecimal getEntitled() {
        return entitled;
    }

    public void setEntitled(BigDecimal entitled) {
        this.entitled = entitled;
    }

    public BigDecimal getUsed() {
        return used;
    }

    public void setUsed(BigDecimal used) {
        this.used = used;
    }

    public BigDecimal getPending() {
        return pending;
    }

    public void setPending(BigDecimal pending) {
        this.pending = pending;
    }
}
