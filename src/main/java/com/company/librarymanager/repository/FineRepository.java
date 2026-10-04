package com.company.librarymanager.repository;

import com.company.librarymanager.domain.Fine;
import com.company.librarymanager.domain.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FineRepository extends JpaRepository<Fine, String> {

    Optional<Fine> findByIssueId(String issueId);

    /**
     * Outstanding fines, largest first, for the collection list.
     *
     * <p>Fetches the loan, the title and the member's account, because every
     * row on the list is read as "who owes what for which book". Each join is
     * to a many-to-one or a one-to-one, so none duplicates a row and the page
     * is exactly the size asked for.
     */
    @Query("""
            select f from Fine f
            join fetch f.issue i
            join fetch i.book b
            left join fetch b.category
            join fetch i.member m
            left join fetch m.user
            where f.paid = false
            order by f.amount desc
            """)
    List<Fine> findUnpaid(Pageable pageable);

    @Query("select f from Fine f where f.paid = false")
    List<Fine> findAllUnpaid();

    long countByPaidFalse();

    @Query("select coalesce(sum(f.amount), 0) from Fine f where f.paid = false")
    BigDecimal sumUnpaid();

    /**
     * Settles a fine, but only while it is still outstanding.
     *
     * <p>The {@code paid = false} test is inside the statement rather than
     * checked beforehand, so two members of staff pressing "Mark paid" on the
     * same fine cannot both record a payment. MySQL serialises them on the row
     * lock and the loser updates nothing, which the caller turns into a refusal.
     *
     * @return rows changed: 1 when this call settled it, 0 when it was already paid
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Fine f
               set f.paid = true,
                   f.paidAt = :paidAt,
                   f.paidBy = :paidBy
             where f.id = :id
               and f.paid = false
            """)
    int markPaid(@Param("id") String id,
                 @Param("paidBy") User paidBy,
                 @Param("paidAt") Instant paidAt);
}
