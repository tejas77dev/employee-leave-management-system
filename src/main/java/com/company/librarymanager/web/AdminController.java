package com.company.librarymanager.web;

import com.company.librarymanager.domain.AuditAction;
import com.company.librarymanager.domain.IssueStatus;
import com.company.librarymanager.domain.Role;
import com.company.librarymanager.domain.User;
import com.company.librarymanager.repository.AuditLogRepository;
import com.company.librarymanager.repository.BookIssueRepository;
import com.company.librarymanager.repository.BookRepository;
import com.company.librarymanager.repository.CategoryRepository;
import com.company.librarymanager.repository.MemberRepository;
import com.company.librarymanager.repository.UserRepository;
import com.company.librarymanager.security.AppUserPrincipal;
import com.company.librarymanager.service.AccountService;
import com.company.librarymanager.service.CatalogService;
import com.company.librarymanager.service.LibraryException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The administrator's area.
 *
 * <p>Every mapping sits under {@code /admin/**}, which the security config
 * restricts to the ADMIN role, so a librarian cannot reach any of it. The role
 * check is therefore not repeated per method.
 *
 * <p>What lives here is what changes who can do what: accounts and their roles,
 * and the categories that organise the catalogue. Lending sits on the issue desk
 * instead, because that is where the work actually happens.
 */
@Controller
public class AdminController {

    private static final int AUDIT_PAGE_SIZE = 50;
    private static final Duration RECENT_WINDOW = Duration.ofDays(7);
    private static final int MAX_NAME = 191;

    private final AccountService accountService;
    private final CatalogService catalogService;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final BookRepository bookRepository;
    private final MemberRepository memberRepository;
    private final BookIssueRepository issueRepository;
    private final CategoryRepository categoryRepository;

    public AdminController(AccountService accountService,
                           CatalogService catalogService,
                           UserRepository userRepository,
                           AuditLogRepository auditLogRepository,
                           BookRepository bookRepository,
                           MemberRepository memberRepository,
                           BookIssueRepository issueRepository,
                           CategoryRepository categoryRepository) {
        this.accountService = accountService;
        this.catalogService = catalogService;
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
        this.bookRepository = bookRepository;
        this.memberRepository = memberRepository;
        this.issueRepository = issueRepository;
        this.categoryRepository = categoryRepository;
    }

    /**
     * Staff and member accounts.
     *
     * <p>Filtered in Java over the full list: accounts are few, and each row
     * needs a member lookup that a page of SQL would still have to make.
     */
    @GetMapping("/admin/accounts")
    public String accounts(@RequestParam(required = false) String q,
                           @AuthenticationPrincipal AppUserPrincipal principal,
                           Model model) {
        String query = (q == null || q.isBlank()) ? null : q.trim().toLowerCase();

        List<User> all = userRepository.findAllByOrderByRoleDescNameAsc();
        List<User> filtered = query == null ? all : all.stream()
                .filter(user -> contains(user.getName(), query) || contains(user.getEmail(), query))
                .toList();

        Map<String, Long> loanCounts = new LinkedHashMap<>();
        for (User user : filtered) {
            memberRepository.findByUserId(user.getId())
                    .ifPresent(member -> loanCounts.put(user.getId(),
                            issueRepository.countByMemberIdAndStatus(member.getId(), IssueStatus.ISSUED)));
        }

        model.addAttribute("accounts", filtered);
        model.addAttribute("loanCounts", loanCounts);
        model.addAttribute("query", q);
        model.addAttribute("roles", Role.values());
        model.addAttribute("totalAccounts", all.size());
        model.addAttribute("activeCount", userRepository.countByActiveTrue());
        model.addAttribute("adminCount", all.stream()
                .filter(user -> user.getRole() == Role.ADMIN && user.isActive())
                .count());
        model.addAttribute("memberCount", memberRepository.countByActiveTrue());
        // The view uses this to stop an administrator locking themselves out by
        // offering themselves a demotion they cannot take.
        model.addAttribute("isSelf", principal.id());
        return "admin/accounts";
    }

    @PostMapping("/admin/accounts/create")
    public String createAccount(@RequestParam(required = false) String name,
                                @RequestParam(required = false) String email,
                                @RequestParam(required = false) String password,
                                @RequestParam(required = false, defaultValue = "MEMBER") String role,
                                @AuthenticationPrincipal AppUserPrincipal principal) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (name == null || name.isBlank()) {
            errors.put("name", "Enter a name");
        } else if (name.length() > MAX_NAME) {
            errors.put("name", "Keep the name under %d characters".formatted(MAX_NAME));
        }
        if (email == null || email.isBlank() || !email.contains("@")) {
            errors.put("email", "Enter a valid email address");
        }
        if (password == null || password.length() < 8) {
            errors.put("password", "Use at least 8 characters");
        }
        if (!errors.isEmpty()) {
            return "redirect:/admin/accounts?error=" + encode(firstValue(errors));
        }

        try {
            accountService.createUser(name, email, password, parseRole(role), Actors.of(principal));
        } catch (CatalogService.FieldErrorException | LibraryException ex) {
            return "redirect:/admin/accounts?error=" + encode(ex.getMessage());
        }
        return "redirect:/admin/accounts?created=true";
    }

    @PostMapping("/admin/accounts/update")
    public String updateAccount(@RequestParam(required = false) String userId,
                                @RequestParam(required = false) String name,
                                @RequestParam(required = false, defaultValue = "MEMBER") String role,
                                @RequestParam(required = false, defaultValue = "false") boolean active,
                                @AuthenticationPrincipal AppUserPrincipal principal) {
        if (userId == null || userId.isBlank()) {
            return "redirect:/admin/accounts?error=" + encode("Missing account reference.");
        }
        if (name == null || name.isBlank() || name.length() > MAX_NAME) {
            return "redirect:/admin/accounts?error="
                    + encode("Enter a name under %d characters.".formatted(MAX_NAME));
        }
        try {
            accountService.updateUser(userId, name, parseRole(role), active, Actors.of(principal));
        } catch (LibraryException ex) {
            return "redirect:/admin/accounts?error=" + encode(ex.getMessage());
        }
        return "redirect:/admin/accounts?updated=true";
    }

    /**
     * Resets someone's password.
     *
     * <p>An administrator resetting a password does not need the old one, which
     * is the whole point of the operation; the account holder changing their own
     * does, and that path is {@link AuthController}.
     */
    @PostMapping("/admin/accounts/{id}/reset-password")
    public String resetPassword(@PathVariable("id") String userId,
                                @RequestParam(required = false) String newPassword,
                                @AuthenticationPrincipal AppUserPrincipal principal) {
        if (newPassword == null || newPassword.length() < 8) {
            return "redirect:/admin/accounts?error=" + encode("Use at least 8 characters.");
        }
        User target = userRepository.findById(userId).orElse(null);
        if (target == null) {
            return "redirect:/admin/accounts?error=" + encode("That account no longer exists.");
        }
        try {
            accountService.changePassword(target, null, newPassword, Actors.of(principal));
        } catch (CatalogService.FieldErrorException | LibraryException ex) {
            return "redirect:/admin/accounts?error=" + encode(ex.getMessage());
        }
        return "redirect:/admin/accounts?reset=true";
    }

    /** The categories that organise the catalogue. */
    @GetMapping("/admin/categories")
    public String categories(Model model) {
        model.addAttribute("categories", categoryRepository.findAllByOrderByNameAsc());
        model.addAttribute("titleCount", bookRepository.countByActiveTrue());
        model.addAttribute("memberCount", memberRepository.countByActiveTrue());
        model.addAttribute("outCount", issueRepository.countByStatus(IssueStatus.ISSUED));
        return "admin/categories";
    }

    @PostMapping("/admin/categories/save")
    public String saveCategory(@RequestParam(required = false) String id,
                               @RequestParam(required = false) String name,
                               @RequestParam(required = false) String description,
                               @RequestParam(required = false, defaultValue = "true") boolean active,
                               @AuthenticationPrincipal AppUserPrincipal principal) {
        if (name == null || name.isBlank() || name.length() > 100) {
            return "redirect:/admin/categories?error=" + encode("Enter a name under 100 characters.");
        }
        try {
            catalogService.saveCategory(id, name, description, active, Actors.of(principal));
        } catch (CatalogService.FieldErrorException | LibraryException ex) {
            return "redirect:/admin/categories?error=" + encode(ex.getMessage());
        }
        return "redirect:/admin/categories?saved=true";
    }

    /** The activity trail, newest first. */
    @GetMapping("/admin/audit")
    public String audit(@RequestParam(required = false) String action,
                        @RequestParam(required = false) String q,
                        Model model) {
        AuditAction actionFilter = parseAction(action);
        String query = (q == null || q.isBlank()) ? null : q.trim();
        // Evaluated per request, not at render time, so "last 7 days" always
        // means the last 7 days rather than the last 7 days after startup.
        Instant since = Instant.now().minus(RECENT_WINDOW);

        model.addAttribute("entries", auditLogRepository.search(actionFilter, query,
                PageRequest.of(0, AUDIT_PAGE_SIZE)));
        model.addAttribute("action", actionFilter);
        model.addAttribute("query", query);
        model.addAttribute("actions", AuditAction.values());
        model.addAttribute("total", auditLogRepository.countMatching(actionFilter, query));
        // The recent count deliberately ignores the filters, so it reads as
        // "how busy has this been" rather than another filtered number.
        model.addAttribute("recentCount", auditLogRepository.countByCreatedAtGreaterThanEqual(since));
        model.addAttribute("actors", auditLogRepository.findDistinctActorNames());
        model.addAttribute("pageSize", AUDIT_PAGE_SIZE);
        return "admin/audit";
    }

    private AuditAction parseAction(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AuditAction.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Defaults an unrecognised role to MEMBER rather than failing the request.
     *
     * <p>The value comes from a select the user can tamper with, and a post
     * carrying a made-up role should end with the least privilege rather than an
     * error page.
     */
    private Role parseRole(String value) {
        try {
            return Role.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            return Role.MEMBER;
        }
    }

    private static boolean contains(String value, String lowerQuery) {
        return value != null && value.toLowerCase().contains(lowerQuery);
    }

    private static String encode(String message) {
        return java.net.URLEncoder.encode(message, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String firstValue(Map<String, String> errors) {
        return errors.values().iterator().next();
    }
}
