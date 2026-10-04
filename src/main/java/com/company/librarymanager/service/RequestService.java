package com.company.librarymanager.service;

import com.company.librarymanager.domain.AuditAction;
import com.company.librarymanager.domain.Book;
import com.company.librarymanager.domain.BookIssue;
import com.company.librarymanager.domain.BookRequest;
import com.company.librarymanager.domain.IssueStatus;
import com.company.librarymanager.domain.Member;
import com.company.librarymanager.domain.RequestStatus;
import com.company.librarymanager.domain.User;
import com.company.librarymanager.repository.BookIssueRepository;
import com.company.librarymanager.repository.BookRepository;
import com.company.librarymanager.repository.BookRequestRepository;
import com.company.librarymanager.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Requests: a member asks for a title, the desk decides.
 *
 * <p>A request is not a loan and this class never pretends otherwise. Raising
 * one writes a row and an audit line and nothing else, which is why a request
 * is accepted for a title with no copy on the shelf — that is the case it exists
 * for. Approval is where a copy leaves the shelf, and it does that by calling
 * {@link LendingService#issueBook} rather than by copying the rules out: the
 * copy is taken by the same conditional update, the borrowing limit is counted
 * against the same locked member row, and the loan carries the same audit line,
 * so there is one place where a book can be handed over and not two that could
 * drift apart.
 *
 * <p>The ordering inside each method is the part worth knowing. Raising locks
 * the member before counting their requests, so two clicks of the same button
 * cannot both pass the "no duplicate" check. Deciding locks the request before
 * touching anything else, so two librarians cannot both approve one request and
 * hand out two copies for it. The locks are taken request-then-member-then-book
 * in every path, so the three cannot deadlock against each other.
 */
@Service
public class RequestService {

    private final BookRequestRepository requestRepository;
    private final MemberRepository memberRepository;
    private final BookRepository bookRepository;
    private final BookIssueRepository issueRepository;
    private final LendingService lendingService;
    private final AuditService auditService;

    public RequestService(BookRequestRepository requestRepository,
                          MemberRepository memberRepository,
                          BookRepository bookRepository,
                          BookIssueRepository issueRepository,
                          LendingService lendingService,
                          AuditService auditService) {
        this.requestRepository = requestRepository;
        this.memberRepository = memberRepository;
        this.bookRepository = bookRepository;
        this.issueRepository = issueRepository;
        this.lendingService = lendingService;
        this.auditService = auditService;
    }

    /**
     * Records a member's request for a title.
     *
     * <p>Deliberately does not require a copy to be free. Refusing here when the
     * shelf is empty would leave the member with no way to ask for the one book
     * they actually want, and the desk with no record of who is waiting.
     *
     * <p>The member row is locked first. Two requests for the same title name
     * different rows in this table, so nothing else would stop both from passing
     * the duplicate check; every request by that member contends on its member
     * row instead.
     */
    @Transactional
    public BookRequest raise(String bookId, String memberId, String note, User actor) {
        Member member = memberRepository.lockMember(memberId)
                .orElseThrow(() -> new LibraryException("That member no longer exists."));
        if (!member.isActive()) {
            throw new LibraryException("%s's membership is not active.".formatted(
                    MemberService.displayName(member)));
        }

        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new LibraryException("That book no longer exists."));
        if (!book.isActive()) {
            throw new LibraryException("%s has been retired from the catalogue.".formatted(book.getTitle()));
        }

        if (!requestRepository.findLiveForMemberAndBook(memberId, bookId, RequestStatus.PENDING).isEmpty()) {
            throw new LibraryException("A request for %s is already waiting on your list.".formatted(
                    book.getTitle()));
        }
        if (issueRepository.countByMemberIdAndBookIdAndStatus(memberId, bookId, IssueStatus.ISSUED) > 0) {
            throw new LibraryException("%s is already out on your loan.".formatted(book.getTitle()));
        }

        BookRequest request = new BookRequest();
        request.setBook(book);
        request.setMember(member);
        request.setRequestedBy(actor);
        request.setStatus(RequestStatus.PENDING);
        request.setNote(blankToNull(note));
        request = requestRepository.saveAndFlush(request);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("bookId", bookId);
        metadata.put("isbn", book.getIsbn());
        metadata.put("memberId", memberId);
        auditService.record(actor, AuditAction.BOOK_REQUESTED, "BookRequest", request.getId(),
                "%s asked for %s".formatted(MemberService.displayName(member), book.getTitle()),
                metadata);
        return request;
    }

    /**
     * Grants a request, which takes a copy off the shelf and issues the loan.
     *
     * <p>The due date is the member's choice to leave alone: null means the
     * standard loan period, exactly as it does at the desk.
     *
     * <p>Everything that can refuse this is refused by the lending rules, and
     * refusing is the interesting case. If no copy is on the shelf the desk gets
     * "No copy of X is on the shelf" and the request stays pending, which is
     * the right outcome: the member asked, the title exists, and there is
     * nothing to lend yet. A member over their borrowing limit is refused for
     * the same reason. Because both throw, the whole transaction rolls back and
     * the request is left exactly as it was.
     */
    @Transactional
    public BookRequest approve(String requestId, LocalDate dueDate, String decisionNote, User actor) {
        BookRequest request = requestRepository.lockRequest(requestId)
                .orElseThrow(() -> new LibraryException("That request no longer exists."));
        requirePending(request);

        if (dueDate != null && dueDate.isBefore(LocalDate.now())) {
            throw new LibraryException("The due date cannot be in the past.");
        }

        String note = blankToNull(decisionNote);
        // issueBook locks the member itself and counts against the same locked
        // row it always uses, so the borrowing limit cannot be slipped past by
        // approving a request rather than typing the loan in by hand.
        BookIssue loan = lendingService.issueBook(request.getBook().getId(),
                request.getMember().getId(), dueDate,
                note != null ? note : request.getNote(), actor);

        request.setStatus(RequestStatus.APPROVED);
        request.setDecidedBy(actor);
        request.setDecidedAt(Instant.now());
        request.setDecisionNote(note);
        request.setIssue(loan);
        requestRepository.saveAndFlush(request);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("bookId", request.getBook().getId());
        metadata.put("isbn", request.getBook().getIsbn());
        metadata.put("memberId", request.getMember().getId());
        metadata.put("issueId", loan.getId());
        metadata.put("dueDate", Dates.format(loan.getDueDate()));
        auditService.record(actor, AuditAction.BOOK_REQUEST_APPROVED, "BookRequest", request.getId(),
                "Granted %s's request for %s, due %s".formatted(
                        MemberService.displayName(loan.getMember()), request.getBook().getTitle(),
                        Dates.format(loan.getDueDate())),
                metadata);
        return request;
    }

    /**
     * Turns a request down.
     *
     * <p>A reason is required. The member is going to look at this row and be
     * told no without being told anything, and the desk would rather say "both
     * copies are out until the 12th" than leave it blank.
     */
    @Transactional
    public BookRequest reject(String requestId, String reason, User actor) {
        BookRequest request = requestRepository.lockRequest(requestId)
                .orElseThrow(() -> new LibraryException("That request no longer exists."));
        requirePending(request);

        String trimmed = blankToNull(reason);
        if (trimmed == null) {
            throw new LibraryException("Say why the request is being turned down; the member will see it.");
        }

        request.setStatus(RequestStatus.REJECTED);
        request.setDecidedBy(actor);
        request.setDecidedAt(Instant.now());
        request.setDecisionNote(trimmed);
        requestRepository.saveAndFlush(request);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("bookId", request.getBook().getId());
        metadata.put("isbn", request.getBook().getIsbn());
        metadata.put("memberId", request.getMember().getId());
        metadata.put("reason", trimmed);
        auditService.record(actor, AuditAction.BOOK_REQUEST_REJECTED, "BookRequest", request.getId(),
                "Turned down %s's request for %s".formatted(
                        MemberService.displayName(request.getMember()), request.getBook().getTitle()),
                metadata);
        return request;
    }

    private void requirePending(BookRequest request) {
        if (!request.isPending()) {
            throw new LibraryException("That request was already %s.".formatted(
                    request.getStatus() == RequestStatus.APPROVED ? "approved" : "turned down"));
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}