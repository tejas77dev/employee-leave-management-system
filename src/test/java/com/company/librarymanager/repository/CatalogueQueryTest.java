package com.company.librarymanager.repository;

import com.company.librarymanager.domain.Book;
import com.company.librarymanager.domain.BookIssue;
import com.company.librarymanager.domain.Category;
import com.company.librarymanager.domain.IssueStatus;
import com.company.librarymanager.domain.Member;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catalogue and lending-log queries, checked against a real database.
 *
 * <p>These exist because of a specific defect: a book may be filed without a
 * category, the column is nullable, and the queries fetched it with an inner
 * join. That did not merely leave the category blank on the page, it removed the
 * book from the catalogue, the lending log, the overdue count and the member's
 * dashboard altogether. A missing category is a fact to display, not a reason to
 * lose the row.
 */
@DataJpaTest
class CatalogueQueryTest {

    /** Big enough that paging never becomes the thing under test. */
    private static final PageRequest PAGE = PageRequest.of(0, 50);

    @Autowired
    private BookRepository books;

    @Autowired
    private BookIssueRepository issues;

    @Autowired
    private MemberRepository members;

    /**
     * A member row to hang loans on. The column is NOT NULL, so a loan cannot be
     * built without one even when the test does not care who borrowed it.
     */
    private Member member() {
        Member member = new Member();
        member.setMemberId("LIB-0001");
        member.setDepartment("Engineering");
        member.setMaxBooksAllowed(5);
        member.setActive(true);
        return members.saveAndFlush(member);
    }

    @Test
    @DisplayName("finds a book that has no category")
    void findsAnUncategorisedBook() {
        Book book = book("9780132350884", "Clean Code", null);

        List<Book> found = books.search(null, null, true, PAGE);

        assertTrue(found.stream().anyMatch(b -> b.getId().equals(book.getId())),
                "an uncategorised book must still appear in the catalogue");
        assertTrue(found.stream().filter(b -> b.getId().equals(book.getId()))
                .allMatch(b -> b.getCategory() == null), "the category is absent, not invented");
    }

    @Test
    @DisplayName("narrows the catalogue by title and by ISBN")
void searchesByTitleAndIsbn() {
        book("9780132350884", "Clean Code", null);
        book("9780131103627", "Refactoring", null);

        assertEquals(1, books.search("clean", null, true, PAGE).size());
        assertEquals(1, books.search("9780131103627", null, true, PAGE).size());
        assertEquals(0, books.search("nonexistent title", null, true, PAGE).size());
    }

    @Test
    @DisplayName("hides retired titles from the public catalogue but not from the desk")
    void separatesRetiredTitlesByCaller() {
        Book retired = book("9780132350884", "Retired Title", null);
        retired.setActive(false);

        assertFalse(books.search("retired", null, true, PAGE).stream()
                        .anyMatch(b -> b.getId().equals(retired.getId())),
                "the public catalogue must not offer a retired title");
        assertTrue(books.search("retired", null, false, PAGE).stream()
                        .anyMatch(b -> b.getId().equals(retired.getId())),
                "a librarian must be able to find a retired title to restore it");
    }

    @Test
    @DisplayName("counts a page without losing rows to the category join")
void countsTheSameRowsItReturns() {
        book("9780132350884", "Clean Code", null);
        book("9780131103627", "Refactoring", null);

        long counted = books.countSearch(null, null, true);
        long returned = books.search(null, null, true, PAGE).size();

        assertEquals(counted, returned,
                "the pager must agree with the query, or a page silently reads short");
    }

    @Test
    @DisplayName("shows a loan on an uncategorised book in the lending log")
    void showsLoansOnUncategorisedBooks() {
        Book book = book("9780132350884", "Clean Code", null);
        BookIssue issue = issue(book, IssueStatus.ISSUED, LocalDate.now().minusDays(30),
                LocalDate.now().plusDays(1));

        assertTrue(issues.findAllByOrderByCreatedAtDesc(PAGE).stream()
                        .anyMatch(i -> i.getId().equals(issue.getId())),
                "the loan must appear in the log");
        assertTrue(issues.search(null, null, PAGE).stream()
                        .anyMatch(i -> i.getId().equals(issue.getId())),
                "the lending log must show it too");
        assertEquals(1, issues.countSearch(null, null));
    }

    @Test
    @DisplayName("counts an overdue loan on an uncategorised book as overdue")
    void countsOverdueLoansOnUncategorisedBooks() {
        BookIssue overdue = issue(book("9780132350884", "Clean Code", null), IssueStatus.ISSUED,
                LocalDate.now().minusDays(20), LocalDate.now().minusDays(2));

        assertEquals(1, issues.countOverdue(LocalDate.now()));
        assertEquals(1, issues.findOverdue(LocalDate.now()).size());
    }

    @Test
    @DisplayName("does not call a book due back today overdue")
    void doesNotCountTodayAsOverdue() {
issue(book("9780132350884", "Clean Code", null), IssueStatus.ISSUED,
                LocalDate.now().minusDays(5), LocalDate.now());

        assertEquals(0, issues.countOverdue(LocalDate.now()));
    }

    @Test
    @DisplayName("loads a loan with its book and member attached, for use after the transaction")
    void loadsOneLoanWithItsDetails() {
        BookIssue issue = issue(book("9780132350884", "Clean Code", null), IssueStatus.ISSUED,
                LocalDate.now(), LocalDate.now().plusDays(14));

        Optional<BookIssue> loaded = issues.findByIdWithDetails(issue.getId());

        assertTrue(loaded.isPresent());
        // Touching these is the point: under open-in-view=false they have to be
        // initialised already, or reading them after the session closes throws.
        assertEquals("Clean Code", loaded.get().getBook().getTitle());
        org.junit.jupiter.api.Assertions.assertNotNull(loaded.get().getMember());
    }

    /**
     * A saved title, optionally filed under a category.
     *
     * <p>Saving is done here rather than left to the caller because a loan
     * cannot reference a book that has no row yet: the column is NOT NULL, and
     * Hibernate rejects the whole save rather than deferring it.
     */
    private Book book(String isbn, String title, String categoryName) {
        Book book = new Book();
        book.setIsbn(isbn);
        book.setTitle(title);
        book.setAuthor("An Author");
        book.setTotalCopies(2);
        book.setAvailableCopies(2);
        book.setActive(true);
        if (categoryName != null) {
            Category category = new Category();
            category.setName(categoryName);
            category.setActive(true);
            book.setCategory(category);
        }
        return books.saveAndFlush(book);
    }

    private BookIssue issue(Book book, IssueStatus status, LocalDate issued, LocalDate due) {
        BookIssue issue = new BookIssue();
        issue.setBook(book);
        issue.setMember(member());
        issue.setStatus(status);
        issue.setIssueDate(issued);
        issue.setDueDate(due);
        return issues.saveAndFlush(issue);
    }
}