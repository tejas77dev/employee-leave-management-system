package com.company.librarymanager.web;

import com.company.librarymanager.domain.BookIssue;
import com.company.librarymanager.domain.BookRequest;
import com.company.librarymanager.domain.Fine;
import com.company.librarymanager.domain.IssueStatus;
import com.company.librarymanager.domain.Member;
import com.company.librarymanager.domain.RequestStatus;
import com.company.librarymanager.domain.Role;
import com.company.librarymanager.repository.BookIssueRepository;
import com.company.librarymanager.repository.BookRepository;
import com.company.librarymanager.repository.BookRequestRepository;
import com.company.librarymanager.repository.FineRepository;
import com.company.librarymanager.repository.MemberRepository;
import com.company.librarymanager.security.AppUserPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The landing page.
 *
 * <p>Shows two different things depending on who is signed in. Staff get the
 * desk counters, because that is the work in front of them. A borrower gets
 * their own loans, requests and fines, because that is what they came for. Both
 * get the catalogue size, which is the one figure everyone finds interesting.
 */
@Controller
public class DashboardController {

    private static final int RECENT_LIMIT = 6;

    /**
     * How much of a member's request history the dashboard shows. A member who
     * has asked for a hundred books over the years wants the recent ones; the
     * rest is on the desk's queue.
     */
    private static final int REQUEST_LIMIT = 8;

    private final BookIssueRepository issueRepository;
    private final BookRepository bookRepository;
    private final MemberRepository memberRepository;
    private final FineRepository fineRepository;
    private final BookRequestRepository requestRepository;

    public DashboardController(BookIssueRepository issueRepository,
                               BookRepository bookRepository,
                               MemberRepository memberRepository,
                               FineRepository fineRepository,
                               BookRequestRepository requestRepository) {
        this.issueRepository = issueRepository;
        this.bookRepository = bookRepository;
        this.memberRepository = memberRepository;
        this.fineRepository = fineRepository;
        this.requestRepository = requestRepository;
    }

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        LocalDate today = LocalDate.now();
        boolean staff = principal.role() == Role.ADMIN || principal.role() == Role.LIBRARIAN;

        model.addAttribute("isStaff", staff);
        model.addAttribute("titleCount", bookRepository.countByActiveTrue());

        if (staff) {
            model.addAttribute("memberCount", memberRepository.countByActiveTrue());
            model.addAttribute("outCount", issueRepository.countByStatus(IssueStatus.ISSUED));
            model.addAttribute("overdueCount", issueRepository.countOverdue(today));
            model.addAttribute("outstandingFines", fineRepository.sumUnpaid());
            model.addAttribute("unpaidFineCount", fineRepository.countByPaidFalse());
            model.addAttribute("pendingRequests", requestRepository.countByStatus(RequestStatus.PENDING));
            model.addAttribute("issuedToday", issueRepository.findIssuedOn(today));
            model.addAttribute("recent", issueRepository.findAllByOrderByCreatedAtDesc(
                    PageRequest.of(0, RECENT_LIMIT)));
            return "dashboard";
        }

        Member member = memberRepository.findByUserId(principal.id()).orElse(null);
        model.addAttribute("member", member);
        if (member == null) {
            // A signed-in account with no member record cannot borrow: there is
            // no card to charge loans against. Said plainly rather than shown as
            // an empty list that looks like nothing is on loan.
            model.addAttribute("needsRegistration", true);
            return "dashboard";
        }

        List<BookIssue> open = issueRepository.findByMemberIdAndStatusOrderByIssueDateAsc(
                member.getId(), IssueStatus.ISSUED);
        List<Fine> unpaid = unpaidFinesFor(member);
        List<BookRequest> requests =
                requestRepository.findByMemberIdOrderByCreatedAtDesc(member.getId());

        model.addAttribute("needsRegistration", false);
        model.addAttribute("openLoans", open);
        model.addAttribute("limit", member.getMaxBooksAllowed());
        model.addAttribute("remainingSlots", Math.max(0, member.getMaxBooksAllowed() - open.size()));
        model.addAttribute("myOverdue", open.stream().filter(issue -> issue.isOverdue(today)).toList());
        model.addAttribute("myFines", unpaid);
        model.addAttribute("myRequests", requests.stream().limit(REQUEST_LIMIT).toList());
        model.addAttribute("openRequestCount", requests.stream()
                .filter(BookRequest::isPending).count());
        model.addAttribute("totalOutstanding", unpaid.stream()
                .map(Fine::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        model.addAttribute("recent", open.reversed().stream().limit(RECENT_LIMIT).toList());
        return "dashboard";
    }

    /**
     * Unsettled fines on this member's loans.
     *
     * <p>Matched on the card number rather than through the fine repository,
     * because there is no direct fine-to-member link: a fine belongs to a loan,
     * and it is the loan that names the member. The page size is generous rather
     * than exact; a member with hundreds of unsettled fines is a collections
     * problem, not a reason for this page to miss rows silently.
     */
    private List<Fine> unpaidFinesFor(Member member) {
        return issueRepository.search(null, member.getMemberId(), PageRequest.of(0, 500)).stream()
                .map(BookIssue::getFine)
                .filter(fine -> fine != null && !fine.isPaid())
                .toList();
    }
}
