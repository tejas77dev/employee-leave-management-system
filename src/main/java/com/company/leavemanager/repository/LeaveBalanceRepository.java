package com.company.leavemanager.repository;

import com.company.leavemanager.domain.LeaveBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Balance access, including the three conditional updates that keep concurrent
 * requests from overdrawing an allowance.
 *
 * <p>Each update carries its own sufficiency test in the {@code WHERE} clause
 * and reports how many rows it changed. Zero rows means another writer got
 * there first, and the caller turns that into a refusal. The check and the
 * write are therefore one statement, evaluated by MySQL under a row lock, and
 * two simultaneous requests can never both consume the same last day.
 */
public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, String> {

    Optional<LeaveBalance> findByUserIdAndLeaveTypeIdAndYear(String userId, String leaveTypeId, int year);

    /**
     * Reserves days against a balance, only if enough are unspent.
     *
     * @return rows changed: 1 when the days were reserved, 0 when refused
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LeaveBalance b
               set b.pending = b.pending + :days
             where b.id = :id
               and (b.entitled - b.used - b.pending) >= :days
            """)
    int reserveDays(@Param("id") String id, @Param("days") BigDecimal days);

    /**
     * Frees a reservation, only if it is still held.
     *
     * @return rows changed: 1 when released, 0 when there was nothing to free
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LeaveBalance b
               set b.pending = b.pending - :days
             where b.id = :id
               and b.pending >= :days
            """)
    int releaseDays(@Param("id") String id, @Param("days") BigDecimal days);

    /**
     * Consumes days against the entitlement.
     *
     * <p>{@code pending} is deliberately absent from the test: the request
     * being approved has already had its own reservation released, so the check
     * is only that the entitlement covers what is used plus these days.
     *
     * @return rows changed: 1 when consumed, 0 when refused
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LeaveBalance b
               set b.used = b.used + :days
             where b.id = :id
               and (b.entitled - b.used) >= :days
            """)
    int consumeDays(@Param("id") String id, @Param("days") BigDecimal days);

    /**
     * Reads a balance while holding an InnoDB row lock, so a concurrent
     * submission for the same employee, type and year waits here instead of
     * reading a {@code pending} value that is about to change.
     *
     * <p>This replaces the no-op {@code UPDATE ... SET pending = pending} that
     * the SQLite version used purely to seize the single-writer lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from LeaveBalance b where b.id = :id")
    Optional<LeaveBalance> findByIdForUpdate(@Param("id") String id);

    List<LeaveBalance> findByYear(int year);

    List<LeaveBalance> findByUserIdAndYear(String userId, int year);

    /**
     * Balances still sitting at an untouched default, which is what makes it
     * safe to re-sync them when a leave type's default changes. Anything with
     * days already used or reserved is deliberately excluded.
     */
    List<LeaveBalance> findByLeaveTypeIdAndYearAndEntitledAndUsedAndPending(
            String leaveTypeId, int year, BigDecimal entitled, BigDecimal used, BigDecimal pending);
}
