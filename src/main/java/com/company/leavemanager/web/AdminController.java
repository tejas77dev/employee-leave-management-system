package com.company.leavemanager.web;

import com.company.leavemanager.domain.LeaveBalance;
import com.company.leavemanager.domain.LeaveType;
import com.company.leavemanager.domain.RequestStatus;
import com.company.leavemanager.domain.Role;
import com.company.leavemanager.repository.LeaveBalanceRepository;
import com.company.leavemanager.repository.LeaveRequestRepository;
import com.company.leavemanager.repository.LeaveTypeRepository;
import com.company.leavemanager.repository.UserRepository;
import com.company.leavemanager.security.AppUserPrincipal;
import com.company.leavemanager.service.AdminService;
import com.company.leavemanager.service.LeaveCalculator;
import com.company.leavemanager.service.LeaveException;
import com.company.leavemanager.service.LeaveService;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The HR area.
 *
 * <p>Every mapping here sits under {@code /admin/**}, which the security config
 * restricts to the HR role, so an employee cannot reach any of it. The role
 * check is therefore not repeated per method.
 */
@Controller
public class AdminController {

    private static final int DECIDED_TAKE = 25;

    private final LeaveService leaveService;
    private final AdminService adminService;
    private final LeaveRequestRepository requestRepository;
    private final UserRepository userRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveBalanceRepository balanceRepository;

    public AdminController(LeaveService leaveService,
                           AdminService adminService,
                           LeaveRequestRepository requestRepository,
                           UserRepository userRepository,
                           LeaveTypeRepository leaveTypeRepository,
                           LeaveBalanceRepository balanceRepository) {
        this.leaveService = leaveService;
        this.adminService = adminService;
        this.requestRepository = requestRepository;
        this.userRepository = userRepository;
        this.leaveTypeRepository = leaveTypeRepository;
        this.balanceRepository = balanceRepository;
    }

    @GetMapping("/admin/requests")
    public String approvals(@RequestParam(required = false) String status,
                            @RequestParam(required = false) String type,
                            @RequestParam(required = false) String q,
                            @AuthenticationPrincipal AppUserPrincipal principal,
                            Model model) {
        RequestStatus statusFilter = parseStatus(status);
        String typeFilter = (type == null || type.isBlank()) ? null : type;
        String query = (q == null || q.isBlank()) ? null : q.trim();

        model.addAttribute("status", statusFilter);
        model.addAttribute("type", typeFilter);
        model.addAttribute("query", query);
        model.addAttribute("leaveTypes", leaveTypeRepository.findAllByOrderByNameAsc());
        model.addAttribute("pendingCount", requestRepository.countByStatus(RequestStatus.PENDING));
        model.addAttribute("approvedCount", requestRepository.countByStatus(RequestStatus.APPROVED));
        model.addAttribute("rejectedCount", requestRepository.countByStatus(RequestStatus.REJECTED));

        // The status filter also decides which sections can show anything, so
        // choosing "Rejected" does not also list the pending queue.
        boolean showPending = statusFilter == null || statusFilter == RequestStatus.PENDING;
        boolean showDecided = statusFilter == null || statusFilter != RequestStatus.PENDING;
        model.addAttribute("showPending", showPending);
        model.addAttribute("showDecided", showDecided);

        model.addAttribute("pending", showPending
                ? requestRepository.search(RequestStatus.PENDING, typeFilter, query, PageRequest.of(0, DECIDED_TAKE))
                : List.of());
        model.addAttribute("decided", showDecided
                ? requestRepository.search(statusFilter == null ? RequestStatus.APPROVED : statusFilter,
                        typeFilter, query, PageRequest.of(0, DECIDED_TAKE))
                : List.of());
        return "admin/requests";
    }

    private RequestStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return RequestStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @PostMapping("/admin/requests/review")
    public String review(@RequestParam String requestId,
                         @RequestParam String decision,
                         @RequestParam(required = false) String reviewNote,
                         @AuthenticationPrincipal AppUserPrincipal principal,
                         Model model) {
        RequestStatus parsed = parseStatus(decision);
        if (parsed == null || parsed == RequestStatus.PENDING) {
            model.addAttribute("error", "That request has already been reviewed.");
            return "redirect:/admin/requests";
        }
        try {
            leaveService.reviewRequest(requestId, parsed, reviewNote, currentUser(principal));
        } catch (LeaveException ex) {
            // Kept as a flash-style redirect message so a decision made in a
            // long list does not lose the view's scroll position and filters.
            return "redirect:/admin/requests?error=" + java.net.URLEncoder.encode(ex.getMessage(),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        return "redirect:/admin/requests";
    }

    @GetMapping("/admin/employees")
    public String employees(@RequestParam(required = false) String q,
                            @AuthenticationPrincipal AppUserPrincipal principal,
                            Model model) {
        int year = LeaveCalculator.currentYear();
        String query = (q == null || q.isBlank()) ? null : q.trim();

        List<com.company.leavemanager.domain.User> all = userRepository.findAllByOrderByRoleDescNameAsc();
        List<com.company.leavemanager.domain.User> filtered = query == null
                ? all
                : all.stream()
                        .filter(user -> user.getName().toLowerCase().contains(query.toLowerCase())
                                || user.getEmail().toLowerCase().contains(query.toLowerCase()))
                        .toList();

        Map<String, List<LeaveBalance>> balancesByUser = new LinkedHashMap<>();
        Map<String, Long> requestCounts = new LinkedHashMap<>();
        for (var user : filtered) {
            balancesByUser.put(user.getId(), balanceRepository.findByUserIdAndYear(user.getId(), year));
            requestCounts.put(user.getId(), requestRepository.countByUserId(user.getId()));
        }

        model.addAttribute("employees", filtered);
        model.addAttribute("balancesByUser", balancesByUser);
        model.addAttribute("requestCounts", requestCounts);
        model.addAttribute("query", query);
        model.addAttribute("year", year);
        model.addAttribute("totalAccounts", all.size());
        model.addAttribute("isSelf", principal.id());
        return "admin/employees";
    }

    @PostMapping("/admin/employees/create")
    public String createEmployee(@RequestParam String name,
                                 @RequestParam String email,
                                 @RequestParam String password,
                                 @RequestParam(defaultValue = "EMPLOYEE") String role,
                                 @AuthenticationPrincipal AppUserPrincipal principal,
                                 Model model) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (name == null || name.isBlank()) {
            errors.put("name", "Enter a name");
        } else if (name.length() > 120) {
            errors.put("name", "Keep the name under 120 characters");
        }
        if (email == null || email.isBlank() || !email.contains("@")) {
            errors.put("email", "Enter a valid email address");
        }
        if (password == null || password.length() < 8) {
            errors.put("password", "Use at least 8 characters");
        }
        if (!errors.isEmpty()) {
            return "redirect:/admin/employees?error=" + encode(firstValue(errors));
        }

        try {
            adminService.createEmployee(name, email, password, parseRole(role), currentUser(principal));
        } catch (AdminService.FieldErrorException ex) {
            return "redirect:/admin/employees?error=" + encode(ex.getMessage());
        } catch (LeaveException ex) {
            return "redirect:/admin/employees?error=" + encode(ex.getMessage());
        }
        return "redirect:/admin/employees";
    }

    @PostMapping("/admin/employees/update")
    public String updateEmployee(@RequestParam(required = false) String userId,
                                 @RequestParam(required = false) String name,
                                 @RequestParam(defaultValue = "EMPLOYEE") String role,
                                 @RequestParam(required = false, defaultValue = "false") boolean active,
                                 @AuthenticationPrincipal AppUserPrincipal principal) {
        if (userId == null || userId.isBlank()) {
            return "redirect:/admin/employees?error=" + encode("Missing employee reference.");
        }
        if (name == null || name.isBlank() || name.length() > 120) {
            return "redirect:/admin/employees?error=" + encode("Enter a name under 120 characters.");
        }
        try {
            adminService.updateEmployee(userId, name, parseRole(role), active, currentUser(principal));
        } catch (LeaveException ex) {
            return "redirect:/admin/employees?error=" + encode(ex.getMessage());
        }
        return "redirect:/admin/employees";
    }

    private Role parseRole(String value) {
        try {
            return Role.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return Role.EMPLOYEE;
        }
    }

    @GetMapping("/admin/leave-types")
    public String leaveTypes(Model model) {
        model.addAttribute("leaveTypes", leaveTypeRepository.findAllByOrderByNameAsc());
        return "admin/leave-types";
    }

    @PostMapping("/admin/leave-types/save")
    public String saveLeaveType(@RequestParam(required = false) String id,
                                @RequestParam String name,
                                @RequestParam(required = false) String description,
                                @RequestParam String defaultDays,
                                @RequestParam(required = false, defaultValue = "true") boolean active,
                                @AuthenticationPrincipal AppUserPrincipal principal) {
        BigDecimal days = parseDays(defaultDays);
        if (name == null || name.isBlank() || name.length() > 60) {
            return "redirect:/admin/leave-types?error=" + encode("Enter a name under 60 characters.");
        }
        if (days == null) {
            // An empty field must not silently become a zero-day allowance.
            return "redirect:/admin/leave-types?error=" + encode("Enter a default number of days.");
        }
        if (days.signum() < 0) {
            return "redirect:/admin/leave-types?error=" + encode("Cannot be negative.");
        }
        if (days.compareTo(BigDecimal.valueOf(365)) > 0) {
            return "redirect:/admin/leave-types?error=" + encode("That seems too high.");
        }
        try {
            adminService.saveLeaveType(id, name, description, days, active, currentUser(principal));
        } catch (AdminService.FieldErrorException ex) {
            return "redirect:/admin/leave-types?error=" + encode(ex.getMessage());
        } catch (LeaveException ex) {
            return "redirect:/admin/leave-types?error=" + encode(ex.getMessage());
        }
        return "redirect:/admin/leave-types";
    }

    private BigDecimal parseDays(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @GetMapping("/admin/balances")
    public String balances(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        int year = LeaveCalculator.currentYear();
        List<LeaveType> leaveTypes = leaveTypeRepository.findAllByOrderByNameAsc();
        List<com.company.leavemanager.domain.User> employees = userRepository.findAllByOrderByRoleDescNameAsc();

        // One row per employee, each holding that year's balance per type.
        Map<String, Map<String, LeaveBalance>> matrix = new LinkedHashMap<>();
        for (var employee : employees) {
            Map<String, LeaveBalance> byType = new LinkedHashMap<>();
            for (LeaveBalance balance : balanceRepository.findByUserIdAndYear(employee.getId(), year)) {
                byType.put(balance.getLeaveType().getId(), balance);
            }
            matrix.put(employee.getId(), byType);
        }

        model.addAttribute("leaveTypes", leaveTypes);
        model.addAttribute("employees", employees);
        model.addAttribute("matrix", matrix);
        model.addAttribute("year", year);
        return "admin/balances";
    }

    @PostMapping("/admin/balances/set")
    public String setEntitlement(@RequestParam String userId,
                                 @RequestParam String leaveTypeId,
                                 @RequestParam String year,
                                 @RequestParam String entitled,
                                 @AuthenticationPrincipal AppUserPrincipal principal) {
        BigDecimal value = parseDays(entitled);
        if (value == null) {
            return "redirect:/admin/balances?error=" + encode("Enter a number of days.");
        }
        if (value.signum() < 0) {
            return "redirect:/admin/balances?error=" + encode("Cannot be negative.");
        }
        int yearValue;
        try {
            yearValue = Integer.parseInt(year);
        } catch (NumberFormatException ex) {
            return "redirect:/admin/balances?error=" + encode("Invalid year.");
        }
        try {
            adminService.setEntitlement(userId, leaveTypeId, yearValue, value, currentUser(principal));
        } catch (LeaveException ex) {
            return "redirect:/admin/balances?error=" + encode(ex.getMessage());
        }
        return "redirect:/admin/balances";
    }

    private com.company.leavemanager.domain.User currentUser(AppUserPrincipal principal) {
        com.company.leavemanager.domain.User user = new com.company.leavemanager.domain.User();
        user.setId(principal.id());
        user.setName(principal.name());
        user.setEmail(principal.email());
        user.setRole(principal.role());
        user.setActive(principal.isEnabled());
        return user;
    }

    private static String encode(String message) {
        return java.net.URLEncoder.encode(message, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String firstValue(Map<String, String> errors) {
        return errors.values().iterator().next();
    }
}
