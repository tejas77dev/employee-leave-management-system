package com.company.leavemanager.repository;

import com.company.leavemanager.domain.LeaveRequest;
import com.company.leavemanager.domain.RequestStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Every query that returns requests joins the leave type and user.
 *
* <p>All three name-bearing associations are {@code LAZY} and the results are
 * rendered by Thymeleaf after the service transaction has closed, so an
 * unjoined fetch would fail with a {@code LazyInitializationException} the
 * moment a template read a name. The reviewer is joined with a left join
 * because it is null until a decision is made.
 */
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, String> {

    /**
     * A pending or approved request whose dates touch the given range.
     * Rejected requests are ignored, so those dates are free to use again.
     *
     * <p>Both bounds are inclusive, so two requests meeting on a single day
     * count as overlapping.
     */
    @Query("""
            select r from LeaveRequest r
            join fetch r.leaveType
            join fetch r.user
            left join fetch r.reviewedBy
            where r.user.id = :userId
              and r.status in :statuses
              and r.startDate <= :endDate
              and r.endDate >= :startDate
              and (:excludeId is null or r.id <> :excludeId)
            order by r.startDate asc
            """)
    List<LeaveRequest> findOverlapping(@Param("userId") String userId,
                                       @Param("startDate") LocalDate startDate,
                                       @Param("endDate") LocalDate endDate,
                                       @Param("statuses") Collection<RequestStatus> statuses,
                                       @Param("excludeId") String excludeId);

    /** Requests touching a calendar year, with one year of slack at the front. */
    @Query("""
            select r from LeaveRequest r
            join fetch r.leaveType
            join fetch r.user
            left join fetch r.reviewedBy
            where r.user.id = :userId
              and r.startDate >= :from
              and r.endDate <= :to
            order by r.startDate desc
            """)
    List<LeaveRequest> findForUserInWindow(@Param("userId") String userId,
                                           @Param("from") LocalDate from,
                                           @Param("to") LocalDate to);

    @Query("""
            select r from LeaveRequest r
            join fetch r.leaveType
            join fetch r.user
            left join fetch r.reviewedBy
            where r.user.id = :userId
            order by r.createdAt desc
            """)
    List<LeaveRequest> findRecentForUser(@Param("userId") String userId, Pageable pageable);

    /** Approved leave covering a given day, i.e. who is off today. */
    @Query("""
            select r from LeaveRequest r
            join fetch r.leaveType
            join fetch r.user
            left join fetch r.reviewedBy
            where r.status = com.company.leavemanager.domain.RequestStatus.APPROVED
              and r.startDate <= :day
              and r.endDate >= :day
            order by r.user.name asc
            """)
    List<LeaveRequest> findApprovedCovering(@Param("day") LocalDate day);

    @Query("""
            select r from LeaveRequest r
            join fetch r.leaveType
            join fetch r.user
            left join fetch r.reviewedBy
            where r.status = :status
            order by r.startDate asc
            """)
    List<LeaveRequest> findByStatus(@Param("status") RequestStatus status);

    @Query("""
            select r from LeaveRequest r
            join fetch r.leaveType
            join fetch r.user
            left join fetch r.reviewedBy
            where r.status = :status
            order by r.reviewedAt desc
            """)
    List<LeaveRequest> findDecided(@Param("status") RequestStatus status, Pageable pageable);

    /**
     * The approval queue and decision log, narrowed by status, leave type and
     * a name-or-email search. A null argument means "do not filter on this".
     */
    @Query("""
            select r from LeaveRequest r
            join fetch r.leaveType
            join fetch r.user
            left join fetch r.reviewedBy
            where (:status is null or r.status = :status)
              and (:leaveTypeId is null or r.leaveType.id = :leaveTypeId)
              and (:query is null
                   or lower(r.user.name) like concat('%', lower(:query), '%')
                   or lower(r.user.email) like concat('%', lower(:query), '%'))
            order by coalesce(r.reviewedAt, r.createdAt) desc
            """)
    List<LeaveRequest> search(@Param("status") RequestStatus status,
                              @Param("leaveTypeId") String leaveTypeId,
                              @Param("query") String query,
                              Pageable pageable);

    long countByStatus(RequestStatus status);

    long countByUserId(String userId);

    /**
     * A single request with its leave type and user loaded.
     *
     * <p>Used by the review path, which reads names to build the audit summary.
     * {@code findById} would leave both associations as detached proxies.
     */
    @Query("""
            select r from LeaveRequest r
            join fetch r.leaveType
            join fetch r.user
            left join fetch r.reviewedBy
            where r.id = :id
            """)
    Optional<LeaveRequest> findByIdWithDetails(@Param("id") String id);
}
