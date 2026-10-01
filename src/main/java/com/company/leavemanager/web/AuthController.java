package com.company.leavemanager.web;

import com.company.leavemanager.security.AppUserPrincipal;
import com.company.leavemanager.security.SessionService;
import com.company.leavemanager.service.AdminService;
import com.company.leavemanager.service.LeaveCalculator;
import com.company.leavemanager.service.LeaveException;
import com.company.leavemanager.service.LeaveService;
import com.company.leavemanager.repository.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.Map;

/** Sign in, sign out, and the redirects that sit between them. */
@Controller
public class AuthController {

    private final UserRepository userRepository;
    private final AdminService adminService;

    public AuthController(UserRepository userRepository, AdminService adminService) {
        this.userRepository = userRepository;
        this.adminService = adminService;
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
    public String passwordPage(Model model) {
        return "account/password";
    }

    @PostMapping("/account/password")
    public String changePassword(@AuthenticationPrincipal AppUserPrincipal principal,
                                 @RequestParam String currentPassword,
                                 @RequestParam String newPassword,
                                 @RequestParam String confirmPassword,
                                 Model model) {
        Map<String, String> errors = new java.util.LinkedHashMap<>();
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

        var user = userRepository.findById(principal.id()).orElseThrow();
        try {
            adminService.changePassword(user, currentPassword, newPassword);
        } catch (AdminService.FieldErrorException ex) {
            model.addAttribute("fieldErrors", Map.of(ex.field(), ex.getMessage()));
            return "account/password";
        } catch (LeaveException ex) {
            model.addAttribute("error", ex.getMessage());
            return "account/password";
        }

        // Success is only flagged after a real change, so a fresh visit to the
        // form never claims the password was already updated.
        model.addAttribute("success", true);
        model.addAttribute("fieldErrors", Map.of());
        return "account/password";
    }
}
