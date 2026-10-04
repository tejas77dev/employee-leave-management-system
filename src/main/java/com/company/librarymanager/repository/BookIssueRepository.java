package com.company.librarymanager.repository;

import com.company.librarymanager.domain.BookIssue;
import com.company.librarymanager.domain.IssueStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BookIssueRepository extends JpaRepository<BookIssue, String> {

    /**
     * Copies of one title currently out, most recently issued first.
     *
     * <p>Written as a query rather than left as a derived name so the book and
     * its category come back attached: the detail page shows them, and with
     * {@code open-in-view} off a lazy association would fail at render time.
     */
    @Query("""
            select i from BookIssue i
            join fetch i.book b
            left join fetch b.category
            join fetch i.member m
            left join fetch m.user
            where i.book.id = :bookId
              and i.status = :status
            order by i.issueDate desc
            """)
    List<BookIssue> findByBookIdAndStatusOrderByIssueDateDesc(@Param("bookId") String bookId,
                                                              @Param("status") IssueStatus status);

    /**
     * Loans still out for one member, oldest first.
     *
     * <p>Fetches the book and the member's account because both the dashboard
     * and the register render them.
     */
    @Query("""
            select i from BookIssue i
            join fetch i.book b
            left join fetch b.category
            join fetch i.member m
            left join fetch m.user
            where i.member.id = :memberId
              and i.status = :status
            order by i.issueDate asc
            """)
    List<BookIssue> findByMemberIdAndStatusOrderByIssueDateAsc(@Param("memberId") String memberId,
                                                               @Param("status") IssueStatus status);

    long countByMemberIdAndStatus(String memberId, IssueStatus status);

    /**
     * Copies of one title this member already has out.
     *
     * <p>Used to refuse a request for a book the member is already holding:
     * asking to borrow what is in their own bag is not a request the desk can
     * act on. Written out rather than derived because it names two
     * associations and a status.
     */
    @Query("""
            select count(i) from BookIssue i
            where i.member.id = :memberId
              and i.book.id = :bookId
              and i.status = :status
            """)
    long countByMemberIdAndBookIdAndStatus(@Param("memberId") String memberId,
                                           @Param("bookId") String bookId,
                                           @Param("status") IssueStatus status);

    /**
     * One loan with its book, category, member and that member's account already
     * attached.
     *
     * <p>Used by the desk actions that settle a loan — return, loss, fine
     * payment — which all read the title and the card number to describe what
     * they have just done. A bare {@code findById} leaves those two associations
     * as uninitialised proxies, and every one of those calls happens outside the
     * persistence context once the surrounding transaction is done.
     */
    @Query("""
            select i from BookIssue i
            join fetch i.book b
            left join fetch b.category
            join fetch i.member m
            left join fetch m.user
            where i.id = :id
            """)
    Optional<BookIssue> findByIdWithDetails(@Param("id") String id);

    long countByStatus(IssueStatus status);

    /**
     * Loans past their due date and not yet returned, most overdue first.
     *
     * <p>The test is {@code dueDate < today} rather than
     * {@code dueDate <= today}, because a book due back today is not late until
     * tomorrow. There is no time component on a loan, so "end of today" is the
     * only deadline it could have.
     */
    @Query("""
            select i from BookIssue i
            join fetch i.book b
            left join fetch b.category
            join fetch i.member m
            left join fetch m.user
            where i.status = com.company.librarymanager.domain.IssueStatus.ISSUED
              and i.dueDate < :today
            order by i.dueDate asc
            """)
    List<BookIssue> findOverdue(@Param("today") LocalDate today);

    /**
     * How many loans are overdue, matching {@link #findOverdue} exactly.
     *
     * <p>Written out rather than derived from the method name: the name would
     * otherwise be parsed as a property path on BookIssue, which has no
     * {@code countOverdue} property. It fetches nothing, being a count.
     */
    @Query("""
            select count(i) from BookIssue i
            where i.status = com.company.librarymanager.domain.IssueStatus.ISSUED
              and i.dueDate < :today
            """)
    long countOverdue(@Param("today") LocalDate today);

    /** Loans issued today, for the dashboard's counter. */
    @Query("""
            select i from BookIssue i
            join fetch i.book b
            left join fetch b.category
            join fetch i.member m
            left join fetch m.user
            where i.issueDate = :today
            order by i.createdAt desc
            """)
    List<BookIssue> findIssuedOn(@Param("today") LocalDate today);

    /**
     * Most recent activity, newest first, for the dashboard table.
     *
     * <p>A query rather than a derived name so the associations are fetched.
     * Every join here is to a many-to-one or a one-to-one, so none of them
     * duplicates a row and the page is exactly the size asked for.
     */
    @Query("""
            select i from BookIssue i
            join fetch i.book b
            left join fetch b.category
            join fetch i.member m
            left join fetch m.user
            order by i.createdAt desc
            """)
    List<BookIssue> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * The lending log, filtered by status and by a search across the title, the
     * author and the card number.
     *
     * <p>The filter reads {@code i.book.title} and {@code i.member.memberId}
     * without fetching them, which is deliberate: those are joins for filtering
     * only, and the rows the view actually renders arrive attached from the
     * fetch joins above.
     */
    @Query("""
            select i from BookIssue i
            join fetch i.book b
            left join fetch b.category
            join fetch i.member m
            left join fetch m.user
            left join fetch i.fine
            where (:status is null or i.status = :status)
              and (:query is null
                   or lower(b.title) like concat('%', lower(:query), '%')
                   or lower(b.author) like concat('%', lower(:query), '%')
                   or lower(m.memberId) like concat('%', lower(:query), '%')
                   or lower(m.department) like concat('%', lower(:query), '%'))
            order by i.createdAt desc
            """)
    List<BookIssue> search(@Param("status") IssueStatus status,
                           @Param("query") String query,
                           Pageable pageable);

    /** Row count for the same filters as {@link #search}, for the pager. */
    @Query("""
            select count(i) from BookIssue i
            where (:status is null or i.status = :status)
              and (:query is null
                   or lower(i.book.title) like concat('%', lower(:query), '%')
                   or lower(i.book.author) like concat('%', lower(:query), '%')
                   or lower(i.member.memberId) like concat('%', lower(:query), '%')
                   or lower(i.member.department) like concat('%', lower(:query), '%'))
            """)
    long countSearch(@Param("status") IssueStatus status, @Param("query") String query);
}