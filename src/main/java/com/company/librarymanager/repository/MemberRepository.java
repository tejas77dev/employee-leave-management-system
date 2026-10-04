package com.company.librarymanager.repository;

import com.company.librarymanager.domain.Member;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, String> {

    /**
     * A member with the account behind them already loaded.
     *
     * <p>Overridden rather than added alongside {@code findById}, the same way
     * {@link BookRepository} does it, so that every plain lookup hands back a
     * member whose display name can be resolved. With {@code open-in-view} off,
     * a lazy {@code user} here would fail the moment a caller outside the
     * transaction named the member, which is exactly what the audit summaries
     * and the queue rows do.
     */
    @Override
    @EntityGraph(attributePaths = "user")
    Optional<Member> findById(String id);

    Optional<Member> findByMemberIdIgnoreCase(String memberId);

    /**
     * A member's own account, with the user loaded.
     *
     * <p>The borrower's dashboard names the account holder, so it is read
     * outside a transaction with {@code open-in-view} off and has to be fetched
     * here.
     */
    @EntityGraph(attributePaths = "user")
    Optional<Member> findByUserId(String userId);

    /**
     * The whole register, oldest card first.
     *
     * <p>The linked account is fetched because every row on the register shows
     * the holder's name and email, and reading them per row would otherwise be
     * one query each.
     */
    @Query("select m from Member m left join fetch m.user order by m.memberId asc")
    List<Member> findAllByOrderByMemberIdAsc();

    /** Active members only, for the issue-desk picker. Fetches the user as above. */
    @Query("""
            select m from Member m left join fetch m.user
            where m.active = true
            order by m.memberId asc
            """)
    List<Member> findAllByActiveTrueOrderByMemberIdAsc();

    /**
     * Locks the member row for the rest of the transaction.
     *
     * <p>Two loans to the same member can name different books, so locking a
     * book row would not stop both from passing the "under the borrowing limit"
     * check. Locking the member row does, because every issue for that member
     * contends on it.
     *
     * <p>The account is fetched in the same statement because the caller names
     * the member in the audit line it writes, and a pessimistic lock holds the
     * row rather than reading it into the context first.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m left join fetch m.user where m.id = :id")
    Optional<Member> lockMember(@Param("id") String id);

    /** Next card number for a new member, e.g. {@code LIB-0043}. */
    @Query("select max(m.memberId) from Member m")
    String findHighestMemberId();

    long countByActiveTrue();
}
