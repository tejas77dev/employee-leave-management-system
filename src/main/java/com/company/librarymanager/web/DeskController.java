package com.company.librarymanager.web;

import com.company.librarymanager.domain.IssueStatus;
import com.company.librarymanager.domain.RequestStatus;
import com.company.librarymanager.repository.AuditLogRepository;
import com.company.librarymanager.repository.BookIssueRepository;
import com.company.librarymanager.repository.BookRepository;
import com.company.librarymanager.repository.BookRequestRepository;
import com.company.librarymanager.repository.CategoryRepository;
import com.company.librarymanager.repository.FineRepository;
import com.company.librarymanager.repository.MemberRepository;
import com.company.librarymanager.security.AppUserPrincipal;
import com.company.librarymanager.service.Dates;
import com.company.librarymanager.service.LendingService;
import com.company.librarymanager.service.LibraryException;
import com.company.librarymanager.service.MemberService;
import com.company.librarymanager.validation.LendingFormValidator;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.Map;

/**
 * The issue desk: lending books out, taking them back, and collecting fines.
 *
 * <p>Every mapping here sits under {@code /desk/**}, which the security config
 * restricts to librarians and administrators, so a member cannot reach any of it.
 * The role check is therefore not repeated per method.
 *
 * <p>Failures are reported as a redirect carrying a message rather than by
 * re-rendering the page. This is a shared desk, so one browser tab is not tied to
 * one librarian, and a refresh after a successful issue would otherwise post the
 * same loan a second time.
 */
@Controller
public class DeskController {

    private static final int LOG_PAGE_SIZE = 30;
    private static final int FINE_PAGE_SIZE = 40;

    /** Enough to cover a small library in one dropdown, and no more. */
    private static final int PICKER_LIMIT = 200;

    private final LendingService lendingService;
    private final MemberService memberService;
    private final BookIssueRepository issueRepository;
    private final BookRepository bookRepository;
    private final MemberRepository memberRepository;
    private final FineRepository fineRepository;
    private final CategoryRepository categoryRepository;
    private final AuditLogRepository auditLogRepository;
    private final BookRequestRepository requestRepository;

    public DeskController(LendingService lendingService,
                          MemberService memberService,
                          BookIssueRepository issueRepository,
                          BookRepository bookRepository,
                          MemberRepository memberRepository,
                          FineRepository fineRepository,
                          CategoryRepository categoryRepository,
                          AuditLogRepository auditLogRepository,
                          BookRequestRepository requestRepository) {
        this.lendingService = lendingService;
        this.memberService = memberService;
        this.issueRepository = issueRepository;
        this.bookRepository = bookRepository;
        this.memberRepository = memberRepository;
        this.fineRepository = fineRepository;
        this.categoryRepository = categoryRepository;
        this.auditLogRepository = auditLogRepository;
        this.requestRepository = requestRepository;
    }

    /**
     * The desk landing page: the issue form beside the loans that need attention.
     *
     * <p>Overdue loans come first because they are what a member will arrive
     * about. Everything else the desk does is driven from the two pickers on the
     * same form.
     */
    @GetMapping("/desk")
    public String desk(Model model) {
        LocalDate today = LocalDate.now();

        model.addAttribute("defaultDueDate", Dates.format(lendingService.defaultDueDate()));
        model.addAttribute("books", bookRepository.search(null, null, true,
                PageRequest.of(0, PICKER_LIMIT)));
        model.addAttribute("categories", categoryRepository.findAllByActiveTrueOrderByNameAsc());
        model.addAttribute("members", memberRepository.findAllByActiveTrueOrderByMemberIdAsc());
        model.addAttribute("overdue", issueRepository.findOverdue(today));
        model.addAttribute("accrued", lendingService.overdueAssessment(today));
        model.addAttribute("outCount", issueRepository.countByStatus(IssueStatus.ISSUED));
        // The count, not the queue: a desk with one request waiting and a desk
        // with forty want different amounts of attention, and this is the one
        // line that tells them apart without leaving the page.
        model.addAttribute("pendingRequests", requestRepository.countByStatus(RequestStatus.PENDING));
        model.addAttribute("nextMemberId", memberService.nextMemberId());
        return "desk/desk";
    }

    @PostMapping("/desk/issue")
    public String issue(@RequestParam(required = false) String bookId,
                        @RequestParam(required = false) String memberId,
                        @RequestParam(required = false) String dueDate,
                        @RequestParam(required = false) String remarks,
                        @AuthenticationPrincipal AppUserPrincipal principal) {
        Map<String, String> errors = LendingFormValidator.of().validate(bookId, memberId, dueDate);
        if (!errors.isEmpty()) {
            return "redirect:/desk?error=" + encode(firstValue(errors));
        }
        try {
            lendingService.issueBook(bookId, memberId, Dates.parse(dueDate), remarks, Actors.of(principal));
        } catch (LibraryException ex) {
            return "redirect:/desk?error=" + encode(ex.getMessage());
        } catch (IllegalArgumentException ex) {
            // A due date that is not a real calendar day. Reported as a form
            // problem rather than as an internal failure.
            return "redirect:/desk?error=" + encode("Enter a real date, as yyyy-mm-dd");
        }
        return "redirect:/desk?issued=true";
    }

    /**
     * The lending log.
     *
     * <p>Filtered by status and a search over title, author and card number. The
     * status counters ignore those filters on purpose, so they read as "how much
     * is out there" rather than three more filtered numbers.
     */
    @GetMapping("/desk/loans")
    public String loans(@RequestParam(required = false) String status,
                        @RequestParam(required = false) String q,
                        @RequestParam(required = false, defaultValue = "0") int page,
                        Model model) {
        IssueStatus statusFilter = parseStatus(status);
        String query = blankToNull(q);
        int safePage = Math.max(0, page);
        long total = issueRepository.countSearch(statusFilter, query);
        LocalDate today = LocalDate.now();

        model.addAttribute("status", statusFilter);
        model.addAttribute("query", query);
        model.addAttribute("statuses", IssueStatus.values());
        model.addAttribute("outCount", issueRepository.countByStatus(IssueStatus.ISSUED));
        model.addAttribute("returnedCount", issueRepository.countByStatus(IssueStatus.RETURNED));
        model.addAttribute("lostCount", issueRepository.countByStatus(IssueStatus.LOST));
        model.addAttribute("overdueCount", issueRepository.countOverdue(today));
        model.addAttribute("loans", issueRepository.search(statusFilter, query,
                PageRequest.of(safePage, LOG_PAGE_SIZE)));
        model.addAttribute("accrued", lendingService.overdueAssessment(today));
        model.addAttribute("total", total);
        model.addAttribute("page", safePage);
        model.addAttribute("pageSize", LOG_PAGE_SIZE);
        model.addAttribute("hasNext", (long) (safePage + 1) * LOG_PAGE_SIZE < total);
        return "desk/loans";
    }

    /**
     * Takes a book back, settling any fine at the same time.
     *
     * <p>The fine is reported back after the return so the desk can tell the
     * member what came out of it, rather than leaving them to find out later.
     */
    @PostMapping("/desk/loans/{id}/return")
    public String returnBook(@PathVariable String id,
                             @RequestParam(required = false) String remarks,
                             @AuthenticationPrincipal AppUserPrincipal principal) {
        try {
            var issue = lendingService.returnBook(id, remarks, Actors.of(principal));
            if (issue.getFineAmount() != null && issue.getFineAmount().signum() > 0) {
                return "redirect:/desk/loans?returned=true&fine=" + issue.getFineAmount().toPlainString();
            }
        } catch (LibraryException ex) {
            return "redirect:/desk/loans?error=" + encode(ex.getMessage());
        }
        return "redirect:/desk/loans?returned=true";
    }

    /**
     * Writes a copy off as lost.
     *
     * <p>Separate from return because the consequences differ: the copy never
     * comes back to the shelf and the charge is a replacement, not a daily rate.
     */
    @PostMapping("/desk/loans/{id}/lost")
    public String markLost(@PathVariable String id,
                           @RequestParam(required = false) String remarks,
                           @AuthenticationPrincipal AppUserPrincipal principal) {
        try {
            var issue = lendingService.markLost(id, remarks, Actors.of(principal));
            return "redirect:/desk/loans?lost=true&fine=" + issue.getFineAmount().toPlainString();
        } catch (LibraryException ex) {
            return "redirect:/desk/loans?error=" + encode(ex.getMessage());
        }
    }

    /** Outstanding fines, largest first. */
    @GetMapping("/desk/fines")
    public String fines(@RequestParam(required = false, defaultValue = "0") int page, Model model) {
        int safePage = Math.max(0, page);
        long unpaid = fineRepository.countByPaidFalse();

        model.addAttribute("fines", fineRepository.findUnpaid(PageRequest.of(safePage, FINE_PAGE_SIZE)));
        model.addAttribute("unpaidCount", unpaid);
        model.addAttribute("totalOutstanding", fineRepository.sumUnpaid());
        model.addAttribute("settledCount", auditLogRepository.countByAction(
                com.company.librarymanager.domain.AuditAction.FINE_PAID));
        model.addAttribute("page", safePage);
        model.addAttribute("pageSize", FINE_PAGE_SIZE);
        model.addAttribute("hasNext", (long) (safePage + 1) * FINE_PAGE_SIZE < unpaid);
        return "desk/fines";
    }

    @PostMapping("/desk/fines/{id}/pay")
    public String payFine(@PathVariable String id,
                          @AuthenticationPrincipal AppUserPrincipal principal) {
        try {
            lendingService.payFine(id, Actors.of(principal));
        } catch (LibraryException ex) {
            return "redirect:/desk/fines?error=" + encode(ex.getMessage());
        }
        return "redirect:/desk/fines?paid=true";
    }

    private IssueStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return IssueStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String encode(String message) {
        return java.net.URLEncoder.encode(message, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String firstValue(Map<String, String> errors) {
        return errors.values().iterator().next();
    }
}
