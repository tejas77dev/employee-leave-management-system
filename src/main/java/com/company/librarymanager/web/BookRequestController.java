package com.company.librarymanager.web;

import com.company.librarymanager.domain.BookRequest;
import com.company.librarymanager.domain.Member;
import com.company.librarymanager.domain.RequestStatus;
import com.company.librarymanager.repository.BookRequestRepository;
import com.company.librarymanager.repository.MemberRepository;
import com.company.librarymanager.security.AppUserPrincipal;
import com.company.librarymanager.service.Dates;
import com.company.librarymanager.service.LendingService;
import com.company.librarymanager.service.LibraryException;
import com.company.librarymanager.service.RequestService;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * The two ends of a request: a member raising one from a book page, and the
 * desk working through the queue.
 *
 * <p>The desk mappings sit under {@code /desk/**}, which the security config
 * restricts to librarians and administrators, so the role check is not repeated
 * here. Raising one does not: it is under {@code /books/**}, where any signed-in
 * user is allowed, and the service refuses anyone without an active membership
 * because there would be no card to hold the book against.
 *
 * <p>Every action redirects rather than re-rendering, and says so in the query
 * string. A shared desk means one browser tab is not tied to one librarian, and
 * a refresh after a successful approval would otherwise post the same decision
 * a second time.
 */
@Controller
public class BookRequestController {

    private static final int QUEUE_PAGE_SIZE = 50;
    private static final int DECIDED_LIMIT = 10;

    private final RequestService requestService;
    private final LendingService lendingService;
    private final BookRequestRepository requestRepository;
    private final MemberRepository memberRepository;

    public BookRequestController(RequestService requestService,
                                 LendingService lendingService,
                                 BookRequestRepository requestRepository,
                                 MemberRepository memberRepository) {
        this.requestService = requestService;
        this.lendingService = lendingService;
        this.requestRepository = requestRepository;
        this.memberRepository = memberRepository;
    }

    /**
     * A member asks for a title.
     *
     * <p>An account with no membership is redirected back with the reason rather
     * than being refused: that is what the desk has to fix, and saying so is
     * more use to the member than a bare error.
     */
    @PostMapping("/books/{id}/request")
    public String raise(@PathVariable String id,
                        @RequestParam(required = false) String note,
                        @AuthenticationPrincipal AppUserPrincipal principal) {
        String back = "/books/" + id;

        Member member = memberRepository.findByUserId(principal.id()).orElse(null);
        if (member == null) {
            return "redirect:" + back + "?requestError=" + encode(
                    "Your account is not linked to a library card, so there is nothing to hold the book "
                            + "against. Ask at the desk to be registered.");
        }

        try {
            requestService.raise(id, member.getId(), note, Actors.of(principal));
        } catch (LibraryException ex) {
            return "redirect:" + back + "?requestError=" + encode(ex.getMessage());
        }
        return "redirect:" + back + "?requested=true";
    }

    /**
     * The desk's queue of requests still waiting, oldest first.
     *
     * <p>The recently-decided strip below it is not decoration. A librarian
     * working a queue needs to see that the request they are looking at has
     * already been dealt with by whoever was on the desk before them.
     */
    @GetMapping("/desk/requests")
    public String queue(@RequestParam(required = false, defaultValue = "0") int page, Model model) {
        int safePage = Math.max(0, page);
        long pending = requestRepository.countByStatus(RequestStatus.PENDING);

        model.addAttribute("pending", requestRepository.findByStatusOrderByCreatedAtAsc(
                RequestStatus.PENDING, PageRequest.of(safePage, QUEUE_PAGE_SIZE)));
        model.addAttribute("pendingCount", pending);
        model.addAttribute("approvedCount",
                requestRepository.countByStatus(RequestStatus.APPROVED));
        model.addAttribute("rejectedCount",
                requestRepository.countByStatus(RequestStatus.REJECTED));
        model.addAttribute("decided", requestRepository.findRecentlyDecided(
                RequestStatus.PENDING, PageRequest.of(0, DECIDED_LIMIT)));
        model.addAttribute("defaultDueDate", Dates.format(lendingService.defaultDueDate()));
        model.addAttribute("total", pending);
        model.addAttribute("page", safePage);
        model.addAttribute("pageSize", QUEUE_PAGE_SIZE);
        model.addAttribute("hasNext", (long) (safePage + 1) * QUEUE_PAGE_SIZE < pending);
        return "desk/requests";
    }

    /**
     * Grants a request, which issues the loan and takes a copy off the shelf.
     *
     * <p>A refusal comes back as ?error= with the reason the lending rules gave,
     * which is the case a librarian needs to read carefully: "no copy on the
     * shelf" leaves the request waiting rather than closing it.
     */
    @PostMapping("/desk/requests/{id}/approve")
    public String approve(@PathVariable String id,
                          @RequestParam(required = false) String dueDate,
                          @RequestParam(required = false) String note,
                          @AuthenticationPrincipal AppUserPrincipal principal) {
        LocalDate due;
        try {
            due = dueDate == null || dueDate.isBlank() ? null : Dates.parse(dueDate);
        } catch (IllegalArgumentException ex) {
            return "redirect:/desk/requests?error=" + encode("Enter a real date, as yyyy-mm-dd");
        }

        BookRequest request;
        try {
            request = requestService.approve(id, due, note, Actors.of(principal));
        } catch (LibraryException ex) {
            return "redirect:/desk/requests?error=" + encode(ex.getMessage());
        }
        return "redirect:/desk/requests?approved=true&due=" + encode(Dates.format(request.getIssue().getDueDate()));
    }

    @PostMapping("/desk/requests/{id}/reject")
    public String reject(@PathVariable String id,
                         @RequestParam(required = false) String reason,
                         @AuthenticationPrincipal AppUserPrincipal principal) {
        try {
            requestService.reject(id, reason, Actors.of(principal));
        } catch (LibraryException ex) {
            return "redirect:/desk/requests?error=" + encode(ex.getMessage());
        }
        return "redirect:/desk/requests?rejected=true";
    }

    private static String encode(String message) {
        return URLEncoder.encode(message, StandardCharsets.UTF_8);
    }
}