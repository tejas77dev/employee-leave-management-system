package com.company.librarymanager.service;

import com.company.librarymanager.domain.AuditAction;
import com.company.librarymanager.domain.Book;
import com.company.librarymanager.domain.BookRequest;
import com.company.librarymanager.domain.IssueStatus;
import com.company.librarymanager.domain.Member;
import com.company.librarymanager.domain.RequestStatus;
import com.company.librarymanager.domain.Role;
import com.company.librarymanager.domain.User;
import com.company.librarymanager.repository.AuditLogRepository;
import com.company.librarymanager.repository.BookIssueRepository;
import com.company.librarymanager.repository.BookRepository;
import com.company.librarymanager.repository.BookRequestRepository;
import com.company.librarymanager.repository.MemberRepository;
import com.company.librarymanager.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request workflow, against a real database.
 *
 * <p>Three things are worth testing here and they are all about what happens
 * when something goes wrong. A request is not a loan, so granting one has to
 * fail in exactly the same places an issue does — no copy on the shelf, member
 * over their limit — and in every one of those cases the request has to survive
 * as still pending rather than being half-decided. The second is that deciding
 * is one-way: two librarians cannot both grant one request, and neither can
 * turn down one that was already granted.
 *
 * <p>The first of those is the reason approval delegates to
 * {@link LendingService#issueBook} rather than repeating its rules. If a copy
 * were taken some other way, "no copy on the shelf" would quietly produce a
 * loan for a book nobody has.
 */
@SpringBootTest
@Transactional
class RequestServiceTest {

    @Autowired
    private RequestService requests;

    @Autowired
    private LendingService lending;

    @Autowired
    private BookRequestRepository bookRequests;

    @Autowired
    private BookRepository books;

    @Autowired
    private BookIssueRepository issues;

    @Autowired
    private MemberRepository members;

    @Autowired
    private UserRepository users;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private EntityManager entityManager;

    private final AtomicInteger actors = new AtomicInteger();

    @Test
    @DisplayName("a request is recorded as waiting, and takes nothing off the shelf")
    void raisingARecordsAWaitingRequest() {
        Book book = book(2);
        Member member = member(3);

        BookRequest request = requests.raise(book.getId(), member.getId(), "For the project",
                actor());

        assertEquals(RequestStatus.PENDING, request.getStatus());
        assertNotNull(request.getId(), "the request is recorded");
        assertEquals(2, copiesLeft(book), "asking for a book is not borrowing it");
        assertEquals(0, issues.countByStatus(IssueStatus.ISSUED), "and creates no loan");
        assertTrue(audited(AuditAction.BOOK_REQUESTED, request.getId()),
                "raising a request is on the record");
    }

    @Test
    @DisplayName("a request is accepted for a title with no copy on the shelf")
    void acceptsARequestWhenNothingIsOnTheShelf() {
        Book book = book(1);
        // A different member holds the only copy: this is the case a request
        // exists for, and the member asking for it does not have it out.
        lending.issueBook(book.getId(), member(3).getId(), LocalDate.now().plusDays(14), null, actor());
        assertEquals(0, copiesLeft(book));

        BookRequest request = requests.raise(book.getId(), member(3).getId(), null, actor());

        assertEquals(RequestStatus.PENDING, request.getStatus(),
                "this is the case a request exists for");
    }

    @Test
    @DisplayName("the same title cannot be queued twice")
    void refusesASecondRequestForTheSameTitle() {
        Book book = book(3);
        Member member = member(3);
        requests.raise(book.getId(), member.getId(), null, actor());

        LibraryException refused = assertThrows(LibraryException.class,
                () -> requests.raise(book.getId(), member.getId(), null, actor()));

        assertTrue(refused.getMessage().contains("already waiting"), refused.getMessage());
        assertEquals(1, bookRequests.countByStatus(RequestStatus.PENDING),
                "only one request is left on the list");
    }

    @Test
    @DisplayName("a member cannot request a title they already have out")
    void refusesARequestForATitleAlreadyOnLoan() {
        Book book = book(3);
        Member member = member(3);
        lending.issueBook(book.getId(), member.getId(), LocalDate.now().plusDays(14), null, actor());

        LibraryException refused = assertThrows(LibraryException.class,
                () -> requests.raise(book.getId(), member.getId(), null, actor()));

        assertTrue(refused.getMessage().contains("already out"), refused.getMessage());
    }

    @Test
    @DisplayName("a member whose membership is not active cannot request anything")
    void refusesARequestFromAnInactiveMember() {
        Book book = book(3);
        Member member = member(3);
        member.setActive(false);

        LibraryException refused = assertThrows(LibraryException.class,
                () -> requests.raise(book.getId(), member.getId(), null, actor()));

        assertTrue(refused.getMessage().contains("not active"), refused.getMessage());
    }

    @Test
    @DisplayName("granting a request takes a copy off the shelf and issues the loan")
    void grantingIssuesTheLoan() {
        Book book = book(3);
        Member member = member(3);
        BookRequest request = requests.raise(book.getId(), member.getId(), "For the project",
                actor());

        BookRequest granted = requests.approve(request.getId(), null, null, actor());

        assertEquals(RequestStatus.APPROVED, granted.getStatus());
        assertEquals(2, copiesLeft(book), "the copy went out with the approval");
        assertEquals(1, issues.countByStatus(IssueStatus.ISSUED));
        assertNotNull(granted.getIssue(), "the request names the loan it became");
        assertNotNull(granted.getDecidedAt(), "and when it was decided");
        assertEquals(book.getTitle(), granted.getBook().getTitle(),
                "the summary names the title, so the book has to be loaded");
        assertNotNull(granted.getMember().getMemberId(),
                "and the card number, so the member has to be loaded");
    }

    @Test
    @DisplayName("granting a request takes the due date the desk chose")
    void grantingUsesTheChosenDueDate() {
        Book book = book(1);
        Member member = member(3);
        BookRequest request = requests.raise(book.getId(), member.getId(), null, actor());
        LocalDate due = LocalDate.now().plusDays(21);

        requests.approve(request.getId(), due, null, actor());

        assertEquals(due, reloaded(request).getIssue().getDueDate());
    }

    @Test
    @DisplayName("a request cannot be granted when no copy is on the shelf, and stays waiting")
    void refusesToGrantWithNothingOnTheShelf() {
        Book book = book(1);
        Member member = member(3);
        BookRequest request = requests.raise(book.getId(), member.getId(), null, actor());
        lending.issueBook(book.getId(), member.getId(), LocalDate.now().plusDays(14), null, actor());
        assertEquals(0, copiesLeft(book));

        LibraryException refused = assertThrows(LibraryException.class,
                () -> requests.approve(request.getId(), null, null, actor()));

        assertTrue(refused.getMessage().contains("Clean Code"), refused.getMessage());
        // The refusal has to leave the queue exactly as it was: a librarian who
        // pressed Grant on an empty shelf must find the request still waiting,
        // not a closed request and no loan.
        assertEquals(RequestStatus.PENDING, reloaded(request).getStatus(),
                "an impossible approval must not half-close the request");
        assertNull(reloaded(request).getIssue(), "and must not attach a loan to it");
        assertEquals(1, issues.countByStatus(IssueStatus.ISSUED), "still just the one loan");
    }

    @Test
    @DisplayName("a request cannot be granted past the borrowing limit, and stays waiting")
    void refusesToGrantPastTheBorrowingLimit() {
        Book book = book(5);
        Member member = member(1);
        BookRequest request = requests.raise(book.getId(), member.getId(), null, actor());
        lending.issueBook(book.getId(), member.getId(), LocalDate.now().plusDays(14), null, actor());

        LibraryException refused = assertThrows(LibraryException.class,
                () -> requests.approve(request.getId(), null, null, actor()));

        assertTrue(refused.getMessage().contains("limit is 1"), refused.getMessage());
        assertEquals(RequestStatus.PENDING, reloaded(request).getStatus());
        assertEquals(1, issues.countByStatus(IssueStatus.ISSUED), "no second loan was created");
    }

    @Test
    @DisplayName("the same request cannot be granted twice")
    void refusesToGrantTheSameRequestTwice() {
        Book book = book(4);
        Member member = member(3);
        BookRequest request = requests.raise(book.getId(), member.getId(), null, actor());
        requests.approve(request.getId(), null, null, actor());

        LibraryException refused = assertThrows(LibraryException.class,
                () -> requests.approve(request.getId(), null, null, actor()));

        assertTrue(refused.getMessage().contains("already approved"), refused.getMessage());
        assertEquals(3, copiesLeft(book),
                "one approval takes one copy, and the second attempt takes none");
        assertEquals(1, issues.countByStatus(IssueStatus.ISSUED), "and only one loan exists");
    }

    @Test
    @DisplayName("turning a request down records the reason and issues nothing")
    void turningDownRecordsTheReason() {
        Book book = book(3);
        Member member = member(3);
        BookRequest request = requests.raise(book.getId(), member.getId(), null, actor());

        BookRequest rejected = requests.reject(request.getId(), "Both copies are out until the 12th",
                actor());

        assertEquals(RequestStatus.REJECTED, rejected.getStatus());
        assertEquals("Both copies are out until the 12th", rejected.getDecisionNote());
        assertNull(rejected.getIssue(), "nothing was lent");
        assertEquals(3, copiesLeft(book), "and nothing left the shelf");
        assertEquals(0, issues.countByStatus(IssueStatus.ISSUED));
    }

    @Test
    @DisplayName("a request cannot be turned down without saying why")
    void refusesToTurnDownWithNoReason() {
        Book book = book(3);
        Member member = member(3);
        BookRequest request = requests.raise(book.getId(), member.getId(), null, actor());

        LibraryException refused = assertThrows(LibraryException.class,
                () -> requests.reject(request.getId(), "   ", actor()));

        assertTrue(refused.getMessage().contains("why"), refused.getMessage());
        assertEquals(RequestStatus.PENDING, reloaded(request).getStatus());
    }

    @Test
    @DisplayName("a request that was granted cannot then be turned down")
    void refusesToTurnDownAnApprovedRequest() {
        Book book = book(2);
        Member member = member(3);
        BookRequest request = requests.raise(book.getId(), member.getId(), null, actor());
        requests.approve(request.getId(), null, null, actor());

        LibraryException refused = assertThrows(LibraryException.class,
                () -> requests.reject(request.getId(), "changed our minds", actor()));

        assertTrue(refused.getMessage().contains("already approved"), refused.getMessage());
        assertEquals(1, issues.countByStatus(IssueStatus.ISSUED), "the loan stands");
    }

    @Test
    @DisplayName("each step of the workflow is on the record")
    void writesAnAuditLineForEveryStep() {
        Book book = book(2);
        Member member = member(3);

        BookRequest raised = requests.raise(book.getId(), member.getId(), "For the project", actor());
        requests.approve(raised.getId(), null, null, actor());

        assertTrue(audited(AuditAction.BOOK_REQUESTED, raised.getId()), "raising it is on the record");
        assertTrue(audited(AuditAction.BOOK_REQUEST_APPROVED, raised.getId()), "and granting it");
        assertTrue(audited(AuditAction.BOOK_ISSUED, null),
                "so is the loan the grant produced, under its own action");
    }

    @Test
    @DisplayName("the queue is ordered oldest first")
    void readsTheQueueOldestFirst() {
        Book book = book(4);
        Member first = member(3);
        Member second = member(3);
        BookRequest earlier = requests.raise(book.getId(), first.getId(), null, actor());
        BookRequest later = requests.raise(book.getId(), second.getId(), null, actor());

        var queue = bookRequests.findByStatusOrderByCreatedAtAsc(RequestStatus.PENDING,
                PageRequest.of(0, 10));

        assertEquals(2, queue.size());
        assertTrue(queue.get(0).getCreatedAt().compareTo(queue.get(1).getCreatedAt()) <= 0,
                "the queue reads oldest first, or the earliest request is starved by later ones");
        assertTrue(queue.stream().anyMatch(row -> row.getId().equals(earlier.getId())));
        assertTrue(queue.stream().anyMatch(row -> row.getId().equals(later.getId())));
    }

    private boolean audited(AuditAction action, String entityId) {
        return auditLogs.findAll().stream()
                .filter(entry -> entry.getAction() == action)
                .anyMatch(entry -> entityId == null || entityId.equals(entry.getEntityId()));
    }

    /**
     * Re-reads a request from the row.
     *
     * <p>The copy count and any bulk update behind these services run behind the
     * persistence context, so the instance the test is holding can predate what
     * actually happened. The row is the truth.
     */
    private BookRequest reloaded(BookRequest request) {
        entityManager.flush();
        entityManager.clear();
        return bookRequests.findByIdWithDetails(request.getId()).orElseThrow();
    }

    private int copiesLeft(Book book) {
        entityManager.flush();
        entityManager.clear();
        return books.findById(book.getId()).orElseThrow().getAvailableCopies();
    }

    private User actor() {
        User user = new User();
        user.setEmail("requester-" + actors.incrementAndGet() + "@library.test");
        user.setName("Desk Staff");
        user.setPasswordHash("{noop}not-used-here");
        user.setRole(Role.LIBRARIAN);
        user.setActive(true);
        return users.saveAndFlush(user);
    }

    private Book book(int copies) {
        Book book = new Book();
        book.setIsbn("9781000000%03d".formatted(100 + books.count()));
        book.setTitle("Clean Code");
        book.setAuthor("An Author");
        book.setTotalCopies(copies);
        book.setAvailableCopies(copies);
        book.setActive(true);
        return books.saveAndFlush(book);
    }

    private Member member(int limit) {
        Member member = new Member();
        member.setMemberId("REQ-%04d".formatted(100 + members.count()));
        member.setDepartment("Engineering");
        member.setMaxBooksAllowed(limit);
        member.setActive(true);
        return members.saveAndFlush(member);
    }
}