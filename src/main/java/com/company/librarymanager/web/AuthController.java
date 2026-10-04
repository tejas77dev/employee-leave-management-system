package com.company.librarymanager.web;

import com.company.librarymanager.domain.User;
import com.company.librarymanager.repository.UserRepository;
import com.company.librarymanager.security.AppUserPrincipal;
import com.company.librarymanager.security.SessionService;
import com.company.librarymanager.service.AccountService;
import com.company.librarymanager.service.CatalogService;
import com.company.librarymanager.service.LibraryException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.Map;

/** Sign in, sign out, and the account settings between them. */
@Controller
public class AuthController {

    private final UserRepository userRepository;
    private final AccountService accountService;

    public AuthController(UserRepository userRepository, AccountService accountService) {
        this.userRepository = userRepository;
        this.accountService = accountService;
    }

    @GetMapping("/")
    public String root() {
        return SessionService.current() == null ? "redirect:/login" : "redirect:/dashboard";
    }

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error,
                        @RequestParam(required = false) String logout,
                        @RequestParam(required = false) String message,
                        Model model) {
        if (SessionService.current() != null) {
            return "redirect:/dashboard";
        }
        // One message for a wrong password and an unknown account alike, so the
        // form cannot be used to find out which addresses exist.
        model.addAttribute("error", error != null
                ? (message != null ? message : "Incorrect email or password.")
                : null);
        model.addAttribute("loggedOut", logout != null);
        return "login";
    }

    @GetMapping("/account/password")
    public String passwordPage() {
        return "account/password";
    }

    /**
     * Changes the signed-in user's own password.
     *
     * <p>Re-rendered on failure rather than redirected, so the typed password is
     * not thrown away over a single wrong character. Success is only flagged
     * after a real change, so a fresh visit never claims the password was
     * already updated.
     */
    @PostMapping("/account/password")
    public String changePassword(@AuthenticationPrincipal AppUserPrincipal principal,
                                 @RequestParam(required = false) String currentPassword,
                                 @RequestParam(required = false) String newPassword,
                                 @RequestParam(required = false) String confirmPassword,
                                 Model model) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (currentPassword == null || currentPassword.isEmpty()) {
            errors.put("currentPassword", "Enter your current password");
        }
        if (newPassword == null || newPassword.length() < 8) {
            errors.put("newPassword", "Use at least 8 characters");
        }
        if (confirmPassword == null || confirmPassword.isEmpty()) {
            errors.put("confirmPassword", "Confirm your new password");
        } else if (!newPassword.equals(confirmPassword)) {
            errors.put("confirmPassword", "Passwords do not match");
        }
        model.addAttribute("fieldErrors", errors);
        if (!errors.isEmpty()) {
            return "account/password";
        }

        User user = userRepository.findById(principal.id()).orElseThrow();
        try {
            accountService.changePassword(user, currentPassword, newPassword, user);
        } catch (CatalogService.FieldErrorException ex) {
            Map<String, String> fieldErrors = new LinkedHashMap<>();
            fieldErrors.put(ex.field(), ex.getMessage());
            model.addAttribute("fieldErrors", fieldErrors);
            return "account/password";
        } catch (LibraryException ex) {
            model.addAttribute("error", ex.getMessage());
            return "account/password";
        }

        model.addAttribute("success", true);
        model.addAttribute("fieldErrors", new LinkedHashMap<String, String>());
        return "account/password";
    }
}
