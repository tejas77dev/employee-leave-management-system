package com.company.librarymanager.service;

import com.company.librarymanager.domain.AuditAction;
import com.company.librarymanager.domain.Book;
import com.company.librarymanager.domain.BookIssue;
import com.company.librarymanager.domain.Fine;
import com.company.librarymanager.domain.IssueStatus;
import com.company.librarymanager.domain.Member;
import com.company.librarymanager.domain.User;
import com.company.librarymanager.repository.BookIssueRepository;
import com.company.librarymanager.repository.BookRepository;
import com.company.librarymanager.repository.FineRepository;
import com.company.librarymanager.repository.MemberRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Issuing, returning and writing off books, and the fines that follow.
 *
 * <p>The rules that matter are the ones about concurrency. Two librarians
 * pressing "Issue" on the last copy at the same moment both see
 * {@code availableCopies = 1}, and a check made in Java before the write would
 * let both hand out a book that exists once. The same applies to a member at
 * their borrowing limit, where the two loans name different books and therefore
 * different rows.
 *
 * <p>So the copy count is changed by a conditional update that tests
 * {@code available_copies > 0} inside the same statement, and the member row is
 * locked before their limit is counted. Both are enforced by MySQL rather than
 * by this code, which means they hold no matter how many application instances
 * are running.
 */
@Service
public class LendingService {

    private final BookRepository bookRepository;
    private final MemberRepository memberRepository;
    private final BookIssueRepository issueRepository;
    private final FineRepository fineRepository;
    private final AuditService auditService;

    private final int defaultLoanDays;
    private final BigDecimal dailyFineRate;
    private final BigDecimal maximumFine;
    private final BigDecimal lostBookCharge;

    public LendingService(BookRepository bookRepository,
                          MemberRepository memberRepository,
                          BookIssueRepository issueRepository,
                          FineRepository fineRepository,
                          AuditService auditService,
                          @Value("${app.lending.loan-days:14}") int defaultLoanDays,
                          @Value("${app.lending.daily-fine:1.00}") BigDecimal dailyFineRate,
                          @Value("${app.lending.max-fine:50.00}") BigDecimal maximumFine,
                          @Value("${app.lending.lost-book-charge:25.00}") BigDecimal lostBookCharge) {
        this.bookRepository = bookRepository;
        this.memberRepository = memberRepository;
        this.issueRepository = issueRepository;
        this.fineRepository = fineRepository;
        this.auditService = auditService;
        this.defaultLoanDays = defaultLoanDays;
        this.dailyFineRate = dailyFineRate;
        this.maximumFine = maximumFine;
        this.lostBookCharge = lostBookCharge;
    }

    public int defaultLoanDays() {
        return defaultLoanDays;
    }

    public BigDecimal lostBookCharge() {
        return lostBookCharge;
    }

    /** The date a book handed over today would be due back. */
    public LocalDate defaultDueDate() {
        return LocalDate.now().plusDays(defaultLoanDays);
    }

    /**
     * Issues a copy to a member.
     *
     * <p>Order matters here and is not interchangeable. The member row is locked
     * first, so that the limit count and the loan it is about to create cannot be
     * interleaved with another issue to the same member. The copy is then taken
     * with the conditional update, and only if that succeeds is the loan row
     * written: creating the loan first and then failing to take a copy would
     * leave a loan for a book nobody has, and the audit trail would claim it was
     * handed over.
     */
    @Transactional
    public BookIssue issueBook(String bookId, String memberId, LocalDate dueDate, String remarks, User actor) {
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

        long onLoan = issueRepository.countByMemberIdAndStatus(memberId, IssueStatus.ISSUED);
        if (onLoan >= member.getMaxBooksAllowed()) {
            throw new LibraryException("%s already has %d %s out and the limit is %d."
                    .formatted(MemberService.displayName(member), onLoan,
                            onLoan == 1 ? "book" : "books", member.getMaxBooksAllowed()));
        }

        LocalDate due = dueDate == null ? defaultDueDate() : dueDate;
        LocalDate today = LocalDate.now();
        if (due.isBefore(today)) {
            throw new LibraryException("The due date cannot be in the past.");
        }

        if (bookRepository.takeAvailableCopy(bookId, Instant.now()) == 0) {
            // Either the last copy went out between the read above and now, or
            // there was never one. The statement decides, not this check.
            throw new LibraryException("No copy of %s is on the shelf.".formatted(book.getTitle()));
        }

        BookIssue issue = new BookIssue();
        issue.setBook(book);
        issue.setMember(member);
        issue.setIssuedBy(actor);
        issue.setIssueDate(today);
        issue.setDueDate(due);
        issue.setStatus(IssueStatus.ISSUED);
        issue.setRemarks(blankToNull(remarks));
        issue = issueRepository.saveAndFlush(issue);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("bookId", bookId);
        metadata.put("isbn", book.getIsbn());
        metadata.put("memberId", member.getMemberId());
        metadata.put("dueDate", Dates.format(due));
        auditService.record(actor, AuditAction.BOOK_ISSUED, "BookIssue", issue.getId(),
                "Issued %s to %s, due %s".formatted(book.getTitle(),
                        MemberService.displayName(member), Dates.format(due)),
                metadata);
        return issue;
    }

    /**
     * Closes a loan by taking the book back, and assesses any fine.
     *
     * <p>The copy goes back on the shelf before the fine is recorded, so a
     * failure while writing the fine does not leave the book unavailable with
     * no loan to explain it. Both live in one transaction, so in practice
     * neither can happen alone.
     */
    @Transactional
    public BookIssue returnBook(String issueId, String remarks, User actor) {
        BookIssue issue = requireOpenIssue(issueId);
        LocalDate returned = LocalDate.now();

        issue.setStatus(IssueStatus.RETURNED);
        issue.setReturnDate(returned);
        if (remarks != null && !remarks.isBlank()) {
            issue.setRemarks(blankToNull(remarks));
        }

        long daysLate = issue.daysOverdue(returned);
        BigDecimal fine = FineCalculator.lateFine(daysLate, dailyFineRate, maximumFine);
        issue.setFineAmount(fine);
        issueRepository.save(issue);

        bookRepository.returnAvailableCopy(issue.getBook().getId(), Instant.now());

        if (fine.signum() > 0) {
            recordFine(issue, fine, actor);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("bookId", issue.getBook().getId());
        metadata.put("memberId", issue.getMember().getMemberId());
        metadata.put("daysLate", daysLate);
        metadata.put("fine", fine.toPlainString());
        auditService.record(actor, AuditAction.BOOK_RETURNED, "BookIssue", issue.getId(),
                "Took back %s from %s%s".formatted(issue.getBook().getTitle(),
                        MemberService.displayName(issue.getMember()),
                        fine.signum() > 0 ? ", fine of %s".formatted(FineCalculator.formatMoney(fine)) : ""),
                metadata);
        return issue;
    }

    /**
     * Closes a loan by writing the copy off: the book is gone, so it never
     * returns to the shelf and the member is charged a replacement.
     *
     * <p>Both copy counts drop rather than just availability: a lost copy is not
     * part of the stock the library holds any more.
     */
    @Transactional
    public BookIssue markLost(String issueId, String remarks, User actor) {
        BookIssue issue = requireOpenIssue(issueId);

        issue.setStatus(IssueStatus.LOST);
        issue.setReturnDate(LocalDate.now());
        issue.setRemarks(blankToNull(remarks) == null
                ? "Reported lost" : blankToNull(remarks));

        BigDecimal charge = FineCalculator.lostFine(lostBookCharge);
        issue.setFineAmount(charge);
        issueRepository.save(issue);

        // Takes the copy out of the stock permanently. Guarded on there being a
        // copy out, so a repeated request cannot write the same copy off twice.
        bookRepository.writeOffCopy(issue.getBook().getId(), Instant.now());

        recordFine(issue, charge, actor);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("bookId", issue.getBook().getId());
        metadata.put("isbn", issue.getBook().getIsbn());
        metadata.put("memberId", issue.getMember().getMemberId());
        metadata.put("charge", charge.toPlainString());
        auditService.record(actor, AuditAction.BOOK_MARKED_LOST, "BookIssue", issue.getId(),
                "Wrote off %s as lost, %s charged".formatted(issue.getBook().getTitle(),
                        FineCalculator.formatMoney(charge)),
                metadata);
        return issue;
    }

    /**
     * Settles an outstanding fine.
     *
     * <p>Guarded on {@code paid = false} in the update, so two clicks of
     * "Mark paid" cannot both record a payment: the second finds no row and is
     * told the fine was already settled.
     */
    @Transactional
    public Fine payFine(String fineId, User actor) {
        Fine fine = fineRepository.findById(fineId)
                .orElseThrow(() -> new LibraryException("That fine no longer exists."));
        if (fine.isPaid()) {
            throw new LibraryException("That fine has already been settled.");
        }

        // Read what the audit line needs first: a bulk update bypasses the
        // persistence context, and a lazy reference resolved afterwards is not
        // guaranteed to still be there.
        String bookTitle = fine.getIssue() == null ? "a loan" : fine.getIssue().getBook().getTitle();
        BigDecimal amount = fine.getAmount();

        if (fineRepository.markPaid(fineId, actor, Instant.now()) == 0) {
            // Another desk settled it between the read above and this write.
            throw new LibraryException("That fine has already been settled.");
        }

        auditService.record(actor, AuditAction.FINE_PAID, "Fine", fineId,
                "Settled a fine of %s on %s".formatted(FineCalculator.formatMoney(amount), bookTitle),
                Map.of("amount", amount.toPlainString()));
        return fine;
    }

    /**
     * Books that are out past their due date and not yet back.
     *
     * <p>Open loans carry no stored fine, so what each owes is worked out here
     * against the current rate. The figure is indicative: it becomes a real
     * charge only on return, when the late days stop moving.
     */
    @Transactional(readOnly = true)
    public Map<BookIssue, BigDecimal> overdueAssessment(LocalDate today) {
        java.util.LinkedHashMap<BookIssue, BigDecimal> assessment = new java.util.LinkedHashMap<>();
        for (BookIssue issue : issueRepository.findOverdue(today)) {
            BigDecimal accrued = FineCalculator.lateFine(issue.daysOverdue(today), dailyFineRate, maximumFine);
            if (accrued.signum() > 0) {
                assessment.put(issue, accrued);
            }
        }
        return assessment;
    }

    /** The fine that would be charged for a return today. */
    public BigDecimal assessLateFine(BookIssue issue, LocalDate today) {
        return FineCalculator.lateFine(issue.daysOverdue(today), dailyFineRate, maximumFine);
    }

    /**
     * At most one fine per loan. The unique index on {@code issue_id} is the
     * backstop for a concurrent return, which would otherwise fail the insert
     * and roll back a return that had already been recorded.
     */
    private void recordFine(BookIssue issue, BigDecimal amount, User actor) {
        if (amount.signum() <= 0) {
            return;
        }
        Fine fine = fineRepository.findByIssueId(issue.getId()).orElseGet(Fine::new);
        fine.setIssue(issue);
        fine.setAmount(amount);
        fine.setPaid(false);
        try {
            fineRepository.saveAndFlush(fine);
        } catch (DataIntegrityViolationException ex) {
            // Another transaction assessed this loan first, so its amount is the
            // one already recorded. Reading it back leaves the two in agreement.
            fineRepository.findByIssueId(issue.getId()).orElseThrow(() -> ex);
        }
    }

    private BookIssue requireOpenIssue(String issueId) {
        BookIssue issue = issueRepository.findByIdWithDetails(issueId)
                .orElseThrow(() -> new LibraryException("That loan no longer exists."));
        if (!issue.isOpen()) {
            throw new LibraryException("%s was already closed on %s."
                    .formatted(issue.getBook().getTitle(),
                            issue.getReturnDate() == null ? "an earlier date"
                                    : Dates.format(issue.getReturnDate())));
        }
        return issue;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
