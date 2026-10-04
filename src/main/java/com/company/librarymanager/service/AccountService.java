package com.company.librarymanager.service;

import com.company.librarymanager.domain.AuditAction;
import com.company.librarymanager.domain.Role;
import com.company.librarymanager.domain.User;
import com.company.librarymanager.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Staff accounts and their roles.
 *
 * <p>Three roles, with two separate powers. An {@code ADMIN} manages accounts,
 * the catalogue and the member list. A {@code LIBRARIAN} runs the issue desk
 * and edits books but cannot create accounts or alter anyone's role. A
 * {@code MEMBER} is an ordinary borrower.
 *
 * <p>Two guards keep the system administrable: an administrator cannot remove
 * their own access, and the last active administrator cannot be demoted or
 * deactivated. Either would otherwise leave nobody able to fix it.
 */
@Service
public class AccountService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AccountService(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional
    public User createUser(String name, String email, String password, Role role, User actor) {
        String normalisedEmail = email.trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(normalisedEmail)) {
            throw new CatalogService.FieldErrorException("email", "That email is already in use");
        }

        User user = new User();
        user.setName(name.trim());
        user.setEmail(normalisedEmail);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(role);
        user.setActive(true);
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // The unique index is the backstop for a concurrent insert.
            throw new CatalogService.FieldErrorException("email", "That email is already in use");
        }

        auditService.record(actor, AuditAction.USER_CREATED, "User", user.getId(),
                "Added %s (%s) as %s".formatted(user.getName(), user.getEmail(), describeRole(role)),
                Map.of("email", user.getEmail(), "role", role.name()));
        return user;
    }

    /**
     * Updates an account.
     *
     * <p>An audit row is written only when something actually changed, so
     * re-saving an unchanged form leaves no trace in the activity log.
     */
    @Transactional
    public void updateUser(String userId, String name, Role role, boolean active, User actor) {
        if (userId == null || userId.isBlank()) {
            throw new LibraryException("Missing account reference.");
        }
        if (userId.equals(actor.getId()) && role != Role.ADMIN) {
            throw new LibraryException("You cannot remove your own administrator access.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new LibraryException("That account no longer exists."));

        boolean losingAdmin = user.getRole() == Role.ADMIN && (role != Role.ADMIN || !active);
        if (losingAdmin && userRepository.countOtherActiveAdmins(userId) == 0) {
            throw new LibraryException("At least one active administrator account is required.");
        }

        List<String> changes = new ArrayList<>();
        String trimmedName = name.trim();
        if (!trimmedName.equals(user.getName())) {
            changes.add("name \"%s\" -> \"%s\"".formatted(user.getName(), trimmedName));
            user.setName(trimmedName);
        }
        if (role != user.getRole()) {
            changes.add("role %s -> %s".formatted(user.getRole(), role));
            user.setRole(role);
        }
        if (active != user.isActive()) {
            changes.add("active %s -> %s".formatted(user.isActive(), active));
            user.setActive(active);
        }
        if (changes.isEmpty()) {
            return;
        }

        userRepository.save(user);
        auditService.record(actor, AuditAction.USER_UPDATED, "User", user.getId(),
                "Updated %s".formatted(user.getName()), Map.of("changes", changes));
    }

    /**
     * Changes a password, either by the account holder or by an administrator
     * resetting it. The current password is only required when someone is
     * changing their own.
     */
    @Transactional
    public void changePassword(User user, String currentPassword, String newPassword, User actor) {
        boolean selfService = actor != null && actor.getId().equals(user.getId());
        if (selfService && (currentPassword == null
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash()))) {
            throw new CatalogService.FieldErrorException("currentPassword",
                    "That is not your current password");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new CatalogService.FieldErrorException("newPassword", "Use at least 8 characters");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        auditService.record(actor == null ? user : actor, AuditAction.PASSWORD_CHANGED,
                "User", user.getId(),
                selfService
                        ? "%s changed their password".formatted(user.getName())
                        : "Reset the password for %s".formatted(user.getName()),
                null);
    }

    /** Human-readable role, for audit summaries and the account list. */
    public static String describeRole(Role role) {
        if (role == null) {
            return "a member";
        }
        return switch (role) {
            case ADMIN -> "an administrator";
            case LIBRARIAN -> "a librarian";
            case MEMBER -> "a member";
        };
    }
}
