package com.company.librarymanager.web;

import com.company.librarymanager.domain.IssueStatus;
import com.company.librarymanager.repository.BookIssueRepository;
import com.company.librarymanager.repository.MemberRepository;
import com.company.librarymanager.security.AppUserPrincipal;
import com.company.librarymanager.service.CatalogService;
import com.company.librarymanager.service.LibraryException;
import com.company.librarymanager.service.MemberService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The member register.
 *
 * <p>Under {@code /desk/**}: registering someone and changing their borrowing
 * limit is everyday counter work, so librarians can do it too.
 */
@Controller
public class DeskMemberController {

    private final MemberService memberService;
    private final MemberRepository memberRepository;
    private final BookIssueRepository issueRepository;

    public DeskMemberController(MemberService memberService,
                                MemberRepository memberRepository,
                                BookIssueRepository issueRepository) {
        this.memberService = memberService;
        this.memberRepository = memberRepository;
        this.issueRepository = issueRepository;
    }

    /**
     * The register, with each member's live loan count beside their limit.
     *
     * <p>Filtered in Java over the full list rather than in SQL: the register is
     * bounded by the number of cardholders, which a library desk can count on
     * one screen, and the loan counts are needed per row anyway. Paging this would
     * mean a count query per row, which is worse than reading the list once.
     */
    @GetMapping("/desk/members")
    public String members(@RequestParam(required = false) String q, Model model) {
        String query = (q == null || q.isBlank()) ? null : q.trim().toLowerCase();

        var all = memberRepository.findAllByOrderByMemberIdAsc();
        var filtered = query == null ? all : all.stream()
                .filter(member -> contains(member.getMemberId(), query)
                        || contains(MemberService.displayName(member), query)
                        || contains(member.getDepartment(), query)
                        || contains(member.getUser() == null ? null : member.getUser().getEmail(), query))
                .toList();

        Map<String, Long> onLoan = new LinkedHashMap<>();
        Map<String, Long> overdue = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        for (var member : filtered) {
            var open = issueRepository.findByMemberIdAndStatusOrderByIssueDateAsc(
                    member.getId(), IssueStatus.ISSUED);
            onLoan.put(member.getId(), (long) open.size());
            overdue.put(member.getId(), open.stream().filter(issue -> issue.isOverdue(today)).count());
        }

        model.addAttribute("members", filtered);
        model.addAttribute("onLoan", onLoan);
        model.addAttribute("overdue", overdue);
        model.addAttribute("query", q);
        model.addAttribute("totalMembers", all.size());
        model.addAttribute("activeCount", memberRepository.countByActiveTrue());
        model.addAttribute("nextMemberId", memberService.nextMemberId());
        return "desk/members";
    }

    @PostMapping("/desk/members/create")
    public String createMember(@RequestParam(required = false) String name,
                               @RequestParam(required = false) String email,
                               @RequestParam(required = false) String password,
                               @RequestParam(required = false) String memberId,
                               @RequestParam(required = false) String phone,
                               @RequestParam(required = false) String department,
                               @RequestParam(required = false) String address,
                               @RequestParam(required = false) String maxBooks,
                               @AuthenticationPrincipal AppUserPrincipal principal) {
        int limit = parseLimit(maxBooks, MemberService.DEFAULT_MAX_BOOKS);
        if (limit == Integer.MIN_VALUE) {
            return "redirect:/desk/members?error=" + encode("Enter a whole number for the borrowing limit.");
        }
        try {
            memberService.createMember(name, email, password, memberId, phone, department, address,
                    limit, Actors.of(principal));
        } catch (CatalogService.FieldErrorException ex) {
            return "redirect:/desk/members?error=" + encode(ex.getMessage());
        } catch (LibraryException ex) {
            return "redirect:/desk/members?error=" + encode(ex.getMessage());
        }
        return "redirect:/desk/members?created=true";
    }

    @PostMapping("/desk/members/update")
    public String updateMember(@RequestParam(required = false) String memberRowId,
                               @RequestParam(required = false) String phone,
                               @RequestParam(required = false) String department,
                               @RequestParam(required = false) String address,
                               @RequestParam(required = false) String maxBooks,
                               @RequestParam(required = false, defaultValue = "false") boolean active,
                               @AuthenticationPrincipal AppUserPrincipal principal) {
        if (memberRowId == null || memberRowId.isBlank()) {
            return "redirect:/desk/members?error=" + encode("Missing member reference.");
        }
        int limit = parseLimit(maxBooks, 0);
        if (limit == Integer.MIN_VALUE) {
            return "redirect:/desk/members?error=" + encode("Enter a whole number for the borrowing limit.");
        }
        try {
            memberService.updateMember(memberRowId, phone, department, address, limit, active,
                    Actors.of(principal));
        } catch (LibraryException ex) {
            return "redirect:/desk/members?error=" + encode(ex.getMessage());
        }
        return "redirect:/desk/members?updated=true";
    }

    /**
     * The borrowing limit from a form field, falling back when it is blank.
     *
     * @return {@link Integer#MIN_VALUE} to mean the text was not a number
     */
    private static int parseLimit(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed < 0 ? Integer.MIN_VALUE : Math.min(parsed, MemberService.MAX_MAX_BOOKS);
        } catch (NumberFormatException ex) {
            return Integer.MIN_VALUE;
        }
    }

    private static boolean contains(String value, String lowerQuery) {
        return value != null && value.toLowerCase().contains(lowerQuery);
    }

    private static String encode(String message) {
        return java.net.URLEncoder.encode(message, java.nio.charset.StandardCharsets.UTF_8);
    }
}
