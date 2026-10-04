package com.company.librarymanager.service;

import com.company.librarymanager.domain.Book;
import com.company.librarymanager.domain.BookIssue;
import com.company.librarymanager.domain.Fine;
import com.company.librarymanager.domain.IssueStatus;
import com.company.librarymanager.domain.Member;
import com.company.librarymanager.domain.Role;
import com.company.librarymanager.domain.User;
import com.company.librarymanager.repository.BookIssueRepository;
import com.company.librarymanager.repository.BookRepository;
import com.company.librarymanager.repository.FineRepository;
import com.company.librarymanager.repository.MemberRepository;
import com.company.librarymanager.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lending rules, exercised against a real database.
 *
 * <p>The copy counts are adjusted with a bulk update partway through a
 * transaction. That update used to clear the persistence context, which
 * detached the very loan and member the method still had to describe in its
 * audit line; under {@code open-in-view=false} the read afterwards threw a
 * LazyInitializationException and the desk action returned a 500. These cases
 * read the book and the member after the update so that cannot regress quietly.
 */
@SpringBootTest
@Transactional
class LendingServiceTest {

    @Autowired
    private LendingService lending;

    @Autowired
    private BookRepository books;

    @Autowired
    private BookIssueRepository issues;

    @Autowired
    private FineRepository fines;

    @Autowired
    private MemberRepository members;

    @Autowired
    private UserRepository users;

    @Autowired
    private EntityManager entityManager;

    private final AtomicInteger actors = new AtomicInteger();

    @Test
    @DisplayName("issuing a book takes a copy off the shelf")
    void issuingTakesACopyOffTheShelf() {
        Book book = book(3);
        Member member = member(5);

        BookIssue issue = lending.issueBook(book.getId(), member.getId(),
                LocalDate.now().plusDays(14), null, actor());

        assertEquals(2, copiesLeft(book), "one copy should have left the shelf");
        assertEquals(IssueStatus.ISSUED, issue.getStatus());
        assertNotNull(issue.getId(), "the loan is recorded");
    }

    @Test
    @DisplayName("returning a book puts the copy back and charges nothing when it is on time")
    void returningPutsTheCopyBack() {
        Book book = book(3);
        Member member = member(5);
        BookIssue issue = lending.issueBook(book.getId(), member.getId(),
                LocalDate.now().plusDays(14), null, actor());
        assertEquals(2, copiesLeft(book));

        lending.returnBook(issue.getId(), "on the shelf", actor());

        assertEquals(3, copiesLeft(book), "the copy is back on the shelf");
        assertEquals(0, fines.countByPaidFalse(), "an on-time return raises no fine");
    }

    @Test
    @DisplayName("returning late records a fine, and can still name the book afterwards")
    void returningLateRecordsAFine() {
        Book book = book(1);
        Member member = member(5);
        BookIssue issue = overdueLoan(book, member, 4);

        BookIssue returned = lending.returnBook(issue.getId(), null, actor());

        assertEquals(new BigDecimal("4.00"), returned.getFineAmount(), "four days at 1.00 a day");
        assertEquals(1, copiesLeft(book), "the only copy is back on the shelf");
assertEquals("Clean Code", returned.getBook().getTitle(),
                "the audit line names the title, so the book has to be loaded");
        assertNotNull(returned.getMember().getMemberId(),
                "and the card number, so the member has to be loaded");
        assertNotNull(fines.findByIssueId(returned.getId()).orElseThrow());
    }

    @Test
    @DisplayName("writing a copy off removes it from the stock entirely")
    void writingOffRemovesTheCopy() {
        Book book = book(2);
        Member member = member(5);
        BookIssue issue = lending.issueBook(book.getId(), member.getId(),
                LocalDate.now().plusDays(14), null, actor());
        assertEquals(1, copiesLeft(book));

BookIssue lost = lending.markLost(issue.getId(), "never came back", actor());

        assertEquals(IssueStatus.LOST, lost.getStatus());
        // Read through copiesLeft rather than off the returned entity: the count
        // was moved by a bulk update, so the copy the service handed back still
        // holds the value from before it. What matters is what is in the row.
        assertEquals(1, copiesLeft(book), "one copy of two is left on the shelf");
        assertEquals(1, totalCopies(book), "the lost copy is no longer stock the library holds");
    }

    @Test
    @DisplayName("refuses to issue the last copy twice")
    void refusesToIssueBeyondTheStock() {
        Book book = book(1);
        Member member = member(5);
        lending.issueBook(book.getId(), member.getId(), LocalDate.now().plusDays(14), null, actor());

        LibraryException refused = assertThrows(LibraryException.class,
                () -> lending.issueBook(book.getId(), member.getId(),
                        LocalDate.now().plusDays(14), null, actor()));

        assertTrue(refused.getMessage().contains("Clean Code"), refused.getMessage());
        assertEquals(0, copiesLeft(book), "a refused issue must not move the count");
    }

    @Test
    @DisplayName("refuses to issue past a member's borrowing limit")
    void refusesToIssuePastTheBorrowingLimit() {
        Book book = book(5);
        Member member = member(1);
        lending.issueBook(book.getId(), member.getId(), LocalDate.now().plusDays(14), null, actor());

        LibraryException refused = assertThrows(LibraryException.class,
                () -> lending.issueBook(book.getId(), member.getId(),
                        LocalDate.now().plusDays(14), null, actor()));

        assertTrue(refused.getMessage().contains("limit is 1"), refused.getMessage());
    }

    @Test
    @DisplayName("refuses a member whose membership is not active")
    void refusesAnInactiveMember() {
        Book book = book(3);
        Member member = member(5);
        member.setActive(false);

        LibraryException refused = assertThrows(LibraryException.class,
                () -> lending.issueBook(book.getId(), member.getId(),
                        LocalDate.now().plusDays(14), null, actor()));

        assertTrue(refused.getMessage().contains("not active"), refused.getMessage());
    }

    @Test
    @DisplayName("charges the flat replacement rate for a lost copy")
    void chargesTheReplacementRateForALostCopy() {
        Book book = book(1);
        Member member = member(5);
        BookIssue issue = lending.issueBook(book.getId(), member.getId(),
                LocalDate.now().plusDays(14), null, actor());

        BookIssue lost = lending.markLost(issue.getId(), null, actor());

        assertEquals(new BigDecimal("25.00"), lost.getFineAmount());
    }

    @Test
    @DisplayName("settles a fine once and refuses a second payment")
    void settlesAFineOnlyOnce() {
        Book book = book(1);
        Member member = member(5);
        BookIssue issue = overdueLoan(book, member, 3);
        lending.returnBook(issue.getId(), null, actor());
Fine outstanding = fines.findByIssueId(issue.getId()).orElseThrow();

        lending.payFine(outstanding.getId(), actor());

        // Re-read rather than trusting the returned entity: markPaid is a bulk
        // update, so the instance the service returned predates it.
        assertTrue(reloaded(outstanding).isPaid(), "the fine is marked settled in the row");
        LibraryException refused = assertThrows(LibraryException.class,
                () -> lending.payFine(outstanding.getId(), actor()));
        assertTrue(refused.getMessage().contains("already"), refused.getMessage());
    }

    @Test
    @DisplayName("counts what an overdue loan owes while it is still out")
    void assessesAnOverdueLoan() {
        Book book = book(1);
        Member member = member(5);
        BookIssue issue = overdueLoan(book, member, 6);

        assertEquals(new BigDecimal("6.00"), lending.assessLateFine(issue, LocalDate.now()));
    }

    /**
     * A loan that is already overdue.
     *
     * <p>Written straight to the repository: a loan cannot be issued late, and
     * the rule under test is what happens on the way back, not on the way out.
     */
    private BookIssue overdueLoan(Book book, Member member, long daysLate) {
        BookIssue issue = new BookIssue();
        issue.setBook(book);
        issue.setMember(member);
        issue.setIssuedBy(actor());
        issue.setStatus(IssueStatus.ISSUED);
        issue.setIssueDate(LocalDate.now().minusDays(daysLate + 14));
        issue.setDueDate(LocalDate.now().minusDays(daysLate));
        return issues.saveAndFlush(issue);
    }

    /**
     * Re-reads the count from the row rather than from the copy in memory.
     *
     * <p>The bulk update that moves the count runs behind the persistence
     * context's back, so the context still holds the value from before it. The
     * context is cleared to get the truth from the database, which is what the
     * next request or page view will read.
     */
private int copiesLeft(Book book) {
        return reloaded(book).getAvailableCopies();
    }

    private int totalCopies(Book book) {
        return reloaded(book).getTotalCopies();
    }

    private Book reloaded(Book book) {
        entityManager.flush();
        entityManager.clear();
        return books.findById(book.getId()).orElseThrow();
    }

    private Fine reloaded(Fine fine) {
        entityManager.flush();
        entityManager.clear();
        return fines.findById(fine.getId()).orElseThrow();
    }

    /**
     * A real row: the audit trail references the actor, so it cannot be
     * transient. Each call needs its own address, because a test that settles
     * two loans would otherwise trip the unique constraint on email.
     */
    private User actor() {
        User user = new User();
        user.setEmail("staff-" + actors.incrementAndGet() + "@library.test");
        user.setName("Desk Staff");
        // The column is NOT NULL and these tests never authenticate, so any
        // placeholder will do.
        user.setPasswordHash("{noop}not-used-here");
        user.setRole(Role.LIBRARIAN);
        user.setActive(true);
        return users.saveAndFlush(user);
    }

/**
     * A title with the given number of copies, all on the shelf.
     *
     * <p>The ISBN is counted off the existing rows rather than fixed, because a
     * test that issues and then files a second book would otherwise trip the
     * unique constraint on ISBN.
     */
    private Book book(int copies) {
        Book book = new Book();
        book.setIsbn("9780000000%03d".formatted(100 + books.count()));
        book.setTitle("Clean Code");
        book.setAuthor("An Author");
        book.setTotalCopies(copies);
        book.setAvailableCopies(copies);
        book.setActive(true);
        return books.saveAndFlush(book);
    }

    private Member member(int limit) {
        Member member = new Member();
        member.setMemberId("LIB-%04d".formatted(100 + members.count()));
        member.setDepartment("Engineering");
        member.setMaxBooksAllowed(limit);
        member.setActive(true);
        return members.saveAndFlush(member);
    }
}