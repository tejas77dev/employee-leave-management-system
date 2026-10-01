package com.company.leavemanager.web;

import com.company.leavemanager.domain.LeaveRequest;
import com.company.leavemanager.domain.RequestStatus;
import com.company.leavemanager.repository.LeaveRequestRepository;
import com.company.leavemanager.security.AppUserPrincipal;
import com.company.leavemanager.service.LeaveCalculator;
import com.company.leavemanager.service.LeaveException;
import com.company.leavemanager.service.LeaveService;
import com.company.leavemanager.validation.LeaveRequestValidator;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** The employee side: dashboard, own requests, and submitting a new one. */
@Controller
public class EmployeeController {

    private static final int RECENT_LIMIT = 5;

    private final LeaveService leaveService;
    private final LeaveRequestRepository requestRepository;

    public EmployeeController(LeaveService leaveService, LeaveRequestRepository requestRepository) {
        this.leaveService = leaveService;
        this.requestRepository = requestRepository;
    }

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        int year = LeaveCalculator.currentYear();
        LocalDate today = LocalDate.now();

        model.addAttribute("year", year);
        model.addAttribute("balances", leaveService.balancesFor(principal.id(), year));
        model.addAttribute("recent", requestRepository.findRecentForUser(principal.id(), PageRequest.of(0, RECENT_LIMIT)));
        model.addAttribute("totalRemaining", totalRemaining(principal.id(), year));

        boolean hr = principal.role() == com.company.leavemanager.domain.Role.HR;
        model.addAttribute("isHr", hr);
        if (hr) {
            // Admin counts are global, not the viewer's own.
            model.addAttribute("pendingCount", requestRepository.countByStatus(RequestStatus.PENDING));
            model.addAttribute("approvedCount", requestRepository.countByStatus(RequestStatus.APPROVED));
            model.addAttribute("offToday", requestRepository.findApprovedCovering(today));
        }
        return "dashboard";
    }

    private BigDecimal totalRemaining(String userId, int year) {
        return leaveService.balancesFor(userId, year).stream()
                .map(LeaveService.BalanceView::remaining)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @GetMapping("/requests")
    public String myRequests(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        int year = LeaveCalculator.currentYear();
        // A year of slack at the front, so a request starting just before this
        // year but running into it is still listed.
        List<LeaveRequest> requests = requestRepository.findForUserInWindow(principal.id(),
                LeaveCalculator.startOfYear(year).minusYears(1),
                LeaveCalculator.endOfYear(year));

        model.addAttribute("requests", requests);
        model.addAttribute("year", year);
        model.addAttribute("approvedDays", sumDays(requests, RequestStatus.APPROVED));
        model.addAttribute("pendingDays", sumDays(requests, RequestStatus.PENDING));
        model.addAttribute("rejectedDays", sumDays(requests, RequestStatus.REJECTED));
        return "requests";
    }

    private BigDecimal sumDays(List<LeaveRequest> requests, RequestStatus status) {
        return requests.stream()
                .filter(request -> request.getStatus() == status)
                .map(LeaveRequest::getDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @GetMapping("/requests/new")
    public String newRequest(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute("balances", leaveService.balancesFor(principal.id(), LeaveCalculator.currentYear()));
        return "requests/new";
    }

    @PostMapping("/requests/new")
    public String submitRequest(@AuthenticationPrincipal AppUserPrincipal principal,
                                @RequestParam(required = false) String leaveTypeId,
                                @RequestParam(required = false) String startDate,
                                @RequestParam(required = false) String endDate,
                                @RequestParam(required = false, defaultValue = "false") boolean isHalfDay,
                                @RequestParam(required = false) String partOfDay,
                                @RequestParam(required = false) String reason,
                                Model model) {
        Map<String, String> errors = LeaveRequestValidator.of()
                .validate(leaveTypeId, startDate, endDate, isHalfDay, partOfDay, reason);
        if (!errors.isEmpty()) {
            model.addAttribute("fieldErrors", errors);
            model.addAttribute("balances", leaveService.balancesFor(principal.id(), LeaveCalculator.currentYear()));
            // Repopulate so a rejected form does not lose what was typed.
            model.addAttribute("form", new LeaveRequestForm(leaveTypeId, startDate, endDate, isHalfDay, partOfDay, reason));
            return "requests/new";
        }

        try {
            leaveService.createRequest(new LeaveService.CreateRequestCommand(
                    principal.id(),
                    leaveTypeId,
                    LeaveCalculator.parse(startDate),
                    LeaveCalculator.parse(endDate),
                    isHalfDay,
                    isHalfDay && partOfDay != null
                            ? com.company.leavemanager.domain.PartOfDay.valueOf(partOfDay)
                            : null,
                    reason), currentUser(principal));
        } catch (LeaveException ex) {
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("balances", leaveService.balancesFor(principal.id(), LeaveCalculator.currentYear()));
            model.addAttribute("form", new LeaveRequestForm(leaveTypeId, startDate, endDate, isHalfDay, partOfDay, reason));
            return "requests/new";
        } catch (Exception ex) {
            // Anything unexpected is reported without its detail, so an
            // internal failure never leaks database text to a user.
            model.addAttribute("error", "We could not create that request. Check the dates and try again.");
            model.addAttribute("balances", leaveService.balancesFor(principal.id(), LeaveCalculator.currentYear()));
            model.addAttribute("form", new LeaveRequestForm(leaveTypeId, startDate, endDate, isHalfDay, partOfDay, reason));
            return "requests/new";
        }
        return "redirect:/requests";
    }

    public record LeaveRequestForm(String leaveTypeId, String startDate, String endDate,
                                   boolean isHalfDay, String partOfDay, String reason) {
    }

    /**
     * A detached {@code User} carrying the actor's identity, so the audit row
     * records their name without a write of a real user record.
     */
    private com.company.leavemanager.domain.User currentUser(AppUserPrincipal principal) {
        com.company.leavemanager.domain.User user = new com.company.leavemanager.domain.User();
        user.setId(principal.id());
        user.setName(principal.name());
        user.setEmail(principal.email());
        user.setRole(principal.role());
        user.setActive(principal.isEnabled());
        return user;
    }
}
