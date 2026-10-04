package com.company.librarymanager.repository;

import com.company.librarymanager.domain.Book;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookRepository extends JpaRepository<Book, String> {

    /*
     * Every bulk update below sets flushAutomatically but deliberately not
     * clearAutomatically. Clearing detaches the whole persistence context, and
     * these statements run in the middle of a service method that still has
     * entities to read afterwards — the loan it just settled, the member it is
     * about to name in an audit line. Any of those that was not already loaded
     * becomes an uninitialisable proxy, which surfaces as a
     * LazyInitializationException. Flushing is what these statements actually
     * need: a bulk update bypasses the context, so pending changes have to be
     * written first or they would be applied on top of it.
     *
     * <p>The timestamp is passed in rather than written as current_timestamp.
     * That function yields a java.sql.Timestamp, which a MySQL mapping will
     * accept but which cannot be assigned to an Instant field under any other
     * database. Naming the value keeps the statement portable.
     */

    /**
     * A title with its category loaded.
     *
     * <p>{@code open-in-view} is off, so a lazy category would fail the moment
     * the view read {@code book.category.name}. Overridden rather than added
     * alongside {@code findById} so every caller of the plain finder gets a
     * usable book and there is no second method to remember.
     */
    @Override
    @EntityGraph(attributePaths = "category")
    Optional<Book> findById(String id);

    Optional<Book> findByIsbn(String isbn);

    boolean existsByIsbn(String isbn);

    List<Book> findAllByOrderByTitleAsc();

    List<Book> findAllByActiveTrueOrderByTitleAsc();

    /**
     * Catalogue search across title, author and ISBN, narrowed by category.
     *
     * <p>A null for either text filter means "no restriction", so the same query
     * serves the unfiltered browse page and a narrowed one. The
     * {@code :categoryId} comparison is done in SQL rather than in Java because
     * the catalogue is paged: filtering after the fact would drop rows off the
     * end of a page and leave it short.
     *
     * <p>{@code activeOnly} is a parameter rather than baked into the query
     * because the two callers genuinely disagree. The public catalogue hides
     * retired titles; the desk's editable list shows them, since a librarian has
     * to be able to find a title in order to put it back into circulation.
     *
     * <p>The category is fetched because every row names it. It is a
     * many-to-one, so joining it multiplies no rows and the page stays the size
     * the pager asked for. The join is a left one because a book may be filed
     * without a category: the desk accepts a blank one, and the column is
     * nullable. An inner join here would not merely leave the category blank, it
     * would drop the book from the catalogue altogether.
     */
    @Query("""
            select b from Book b
            left join fetch b.category
            where (:activeOnly = false or b.active = true)
              and (:categoryId is null or b.category.id = :categoryId)
              and (:query is null
                   or lower(b.title) like concat('%', lower(:query), '%')
                   or lower(b.author) like concat('%', lower(:query), '%')
                   or b.isbn like concat('%', :query, '%'))
            order by b.title asc
            """)
    List<Book> search(@Param("query") String query,
                      @Param("categoryId") String categoryId,
                      @Param("activeOnly") boolean activeOnly,
                      Pageable pageable);

    /** Row count for the same filters as {@link #search}, for the pager. */
    @Query("""
            select count(b) from Book b
            where (:activeOnly = false or b.active = true)
              and (:categoryId is null or b.category.id = :categoryId)
              and (:query is null
                   or lower(b.title) like concat('%', lower(:query), '%')
                   or lower(b.author) like concat('%', lower(:query), '%')
                   or b.isbn like concat('%', :query, '%'))
            """)
    long countSearch(@Param("query") String query,
                     @Param("categoryId") String categoryId,
                     @Param("activeOnly") boolean activeOnly);

    /**
     * Takes one copy off the shelf, but only if there is one to take.
     *
     * <p>The {@code available_copies > 0} test is part of the statement rather
     * than a check made beforehand, so the read and the write cannot disagree.
     * Two librarians issuing the last copy at the same moment both run this;
     * MySQL serialises them on the row lock and the second finds no rows to
     * update, which the service reports as "no copies left". Checking in Java
     * first would let both see 1 available and hand out two copies.
     *
     * @return rows changed: 1 when a copy was taken, 0 when none was free
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Book b
               set b.availableCopies = b.availableCopies - 1,
                   b.updatedAt = :now
             where b.id = :id
               and b.active = true
               and b.availableCopies > 0
            """)
    int takeAvailableCopy(@Param("id") String id, @Param("now") Instant now);

    /** Puts a returned copy back on the shelf. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Book b
               set b.availableCopies = b.availableCopies + 1,
                   b.updatedAt = :now
             where b.id = :id
               and b.availableCopies < b.totalCopies
            """)
    int returnAvailableCopy(@Param("id") String id, @Param("now") Instant now);

    /**
     * Writes a copy off the shelf for good after it is reported lost.
     *
     * <p>Only {@code totalCopies} drops. The copy was already off the shelf when
     * it went out on loan, so {@code availableCopies} never counted it and must
     * not change: shrinking both would quietly invent a copy back on the shelf.
     * Guarded on {@code totalCopies - availableCopies > 0}, so the last
     * outstanding copy cannot be written off twice.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Book b
               set b.totalCopies = b.totalCopies - 1,
                   b.updatedAt = :now
             where b.id = :id
               and b.totalCopies - b.availableCopies > 0
            """)
    int writeOffCopy(@Param("id") String id, @Param("now") Instant now);

    long countByActiveTrue();

    long countByActiveTrueAndAvailableCopiesGreaterThan(int copies);
}
