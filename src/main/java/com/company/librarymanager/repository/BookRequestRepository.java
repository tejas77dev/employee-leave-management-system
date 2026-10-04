package com.company.librarymanager.repository;

import com.company.librarymanager.domain.BookRequest;
import com.company.librarymanager.domain.RequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BookRequestRepository extends JpaRepository<BookRequest, String> {

    /**
     * One request with everything the desk and the audit line read from it.
     *
     * <p>The title, its category, the member and that member's account are all
     * named on the queue and in the decision summary, and with
     * {@code open-in-view} off a lazy association would fail at render time.
     * The loan it turned into comes with it for the same reason. Every join is
     * many-to-one, so none of them multiplies rows.
     */
    @Query("""
            select r from BookRequest r
            join fetch r.book b
            left join fetch b.category
            join fetch r.member m
            left join fetch m.user
            left join fetch r.requestedBy
            left join fetch r.decidedBy
            left join fetch r.issue
            where r.id = :id
            """)
    Optional<BookRequest> findByIdWithDetails(@Param("id") String id);

    /**
     * The same row, locked for the rest of the transaction.
     *
     * <p>Approval is not a status write on its own: it also takes a copy off the
     * shelf and writes a loan. Two librarians pressing "Approve" on one request
     * would otherwise both see PENDING, both find a copy free, and hand out the
     * same request twice. Locking the request first means the second one waits,
     * then finds a decided request and is told so.
     *
     * <p>The lock is taken before the member row that
     * {@link com.company.librarymanager.service.LendingService} then locks, so
     * every path that touches both rows takes them in this order and the two
     * cannot deadlock against each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from BookRequest r
            join fetch r.book b
            join fetch r.member m
            where r.id = :id
            """)
    Optional<BookRequest> lockRequest(@Param("id") String id);

    /**
     * The desk's queue: requests still waiting, oldest first.
     *
     * <p>Oldest first on purpose. Ordered newest first, a popular title's queue
     * is forever a page of requests raised in the last hour and the ones from
     * last month never reach the top.
     */
    @Query("""
            select r from BookRequest r
            join fetch r.book b
            left join fetch b.category
            join fetch r.member m
            left join fetch m.user
            where r.status = :status
            order by r.createdAt asc
            """)
    List<BookRequest> findByStatusOrderByCreatedAtAsc(@Param("status") RequestStatus status,
                                                      Pageable pageable);

    /**
     * A member's own requests, newest first, for their dashboard.
     *
     * <p>The loan is fetched because an approved request shows which loan it
     * became. It is a many-to-one and nullable, so the left join keeps requests
     * that were never granted, and it multiplies no rows.
     */
    @Query("""
            select r from BookRequest r
            join fetch r.book b
            left join fetch b.category
            left join fetch r.issue
            where r.member.id = :memberId
            order by r.createdAt desc
            """)
    List<BookRequest> findByMemberIdOrderByCreatedAtDesc(@Param("memberId") String memberId);

    /** Recently decided requests, for the "just dealt with" strip on the queue. */
    @Query("""
            select r from BookRequest r
            join fetch r.book b
            join fetch r.member m
            left join fetch r.decidedBy
            left join fetch r.issue
            where r.status <> :pending
            order by r.decidedAt desc
            """)
    List<BookRequest> findRecentlyDecided(@Param("pending") RequestStatus pending, Pageable pageable);

    /** The live request this member already has for this title, if any. */
    @Query("""
            select r from BookRequest r
            where r.member.id = :memberId
              and r.book.id = :bookId
              and r.status = :status
            order by r.createdAt desc
            """)
    List<BookRequest> findLiveForMemberAndBook(@Param("memberId") String memberId,
                                               @Param("bookId") String bookId,
                                               @Param("status") RequestStatus status);

    long countByStatus(RequestStatus status);

    long countByMemberIdAndStatus(String memberId, RequestStatus status);

    long countByBookIdAndStatus(String bookId, RequestStatus status);
}