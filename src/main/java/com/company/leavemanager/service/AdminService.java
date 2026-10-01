package com.company.leavemanager.service;

import com.company.leavemanager.domain.AuditAction;
import com.company.leavemanager.domain.LeaveType;
import com.company.leavemanager.domain.Role;
import com.company.leavemanager.domain.User;
import com.company.leavemanager.repository.LeaveBalanceRepository;
import com.company.leavemanager.repository.LeaveTypeRepository;
import com.company.leavemanager.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminService {

    private final UserRepository userRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveBalanceRepository balanceRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final LeaveService leaveService;

    public AdminService(UserRepository userRepository,
                        LeaveTypeRepository leaveTypeRepository,
                        LeaveBalanceRepository balanceRepository,
                        PasswordEncoder passwordEncoder,
                        AuditService auditService,
                        LeaveService leaveService) {
        this.userRepository = userRepository;
        this.leaveTypeRepository = leaveTypeRepository;
        this.balanceRepository = balanceRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.leaveService = leaveService;
    }

    /**
     * Creates an employee and gives them this year's allowance for every
     * active leave type, so a new joiner is not left with nothing allocated.
     */
    @Transactional
    public User createEmployee(String name, String email, String password, Role role, User actor) {
        String normalisedEmail = email.trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(normalisedEmail)) {
            throw new FieldErrorException("email", "That email is already in use");
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
            throw new FieldErrorException("email", "That email is already in use");
        }

        int year = LeaveCalculator.currentYear();
        for (LeaveType type : leaveTypeRepository.findAllByActiveTrueOrderByNameAsc()) {
            leaveService.ensureBalance(user.getId(), type.getId(), year);
        }

        auditService.record(actor, AuditAction.EMPLOYEE_CREATED, "User", user.getId(),
                "Added %s (%s) as %s".formatted(user.getName(), user.getEmail(),
                        role == Role.HR ? "HR" : "an employee"),
                Map.of("email", user.getEmail(), "role", role.name()));
        return user;
    }

    /**
     * Updates an employee.
     *
     * <p>Two guards protect the system from being left unadministrable: HR
     * cannot remove their own access, and the last active HR cannot be
     * demoted or deactivated. An audit row is written only when something
     * actually changed, so re-saving an unchanged form leaves no trace.
     */
    @Transactional
    public void updateEmployee(String userId, String name, Role role, boolean active, User actor) {
        if (userId == null || userId.isBlank()) {
            throw new LeaveException("Missing employee reference.");
        }
        if (userId.equals(actor.getId()) && role != Role.HR) {
            throw new LeaveException("You cannot remove your own HR access.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new LeaveException("That employee no longer exists."));

        boolean losingHr = user.getRole() == Role.HR && (role != Role.HR || !active);
        if (losingHr && userRepository.countOtherActiveHr(userId) == 0) {
            throw new LeaveException("At least one active HR account is required.");
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
        auditService.record(actor, AuditAction.EMPLOYEE_UPDATED, "User", user.getId(),
                "Updated %s".formatted(user.getName()),
                Map.of("changes", changes));
    }

    public void changePassword(User user, String currentPassword, String newPassword) {
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new FieldErrorException("currentPassword", "That is not your current password");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        auditService.record(user, AuditAction.PASSWORD_CHANGED, "User", user.getId(),
                "%s changed their password".formatted(user.getName()), null);
    }

    /**
     * Creates or updates a leave type, decided by whether an id was supplied.
     *
     * <p>When a default changes, balances still sitting at the old default are
     * re-synced to the new one. Any balance with days already used or reserved
     * is left alone, since moving the entitlement under a live request would
     * silently change what someone has already committed.
     */
    @Transactional
    public LeaveType saveLeaveType(String id,
                                   String name,
                                   String description,
                                   BigDecimal defaultDays,
                                   boolean active,
                                   User actor) {
        if (id == null || id.isBlank()) {
            return createLeaveType(name, description, defaultDays, active, actor);
        }
        return updateLeaveType(id, name, description, defaultDays, active, actor);
    }

    private LeaveType createLeaveType(String name, String description, BigDecimal defaultDays,
                                      boolean active, User actor) {
        String trimmedName = name.trim();
        if (leaveTypeRepository.existsByNameIgnoreCase(trimmedName)) {
            throw new FieldErrorException("name", "A leave type with that name exists");
        }

        LeaveType leaveType = new LeaveType();
        leaveType.setName(trimmedName);
        leaveType.setDescription(blankToNull(description));
        leaveType.setDefaultDays(defaultDays);
        leaveType.setActive(active);
        try {
            leaveType = leaveTypeRepository.saveAndFlush(leaveType);
        } catch (DataIntegrityViolationException ex) {
            throw new FieldErrorException("name", "A leave type with that name exists");
        }

        // Every active employee needs a row before they can request this type.
        List<User> users = userRepository.findAllByActiveTrueOrderByNameAsc();
        for (User user : users) {
            leaveService.ensureBalance(user.getId(), leaveType.getId(), LeaveCalculator.currentYear());
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("defaultDays", defaultDays);
        metadata.put("balancesCreated", users.size());
        auditService.record(actor, AuditAction.LEAVE_TYPE_CREATED, "LeaveType", leaveType.getId(),
                "Created %s with a default of %s day(s)".formatted(leaveType.getName(),
                        LeaveCalculator.formatDays(defaultDays)),
                metadata);
        return leaveType;
    }

    private LeaveType updateLeaveType(String id, String name, String description,
                                      BigDecimal defaultDays, boolean active, User actor) {
        LeaveType leaveType = leaveTypeRepository.findById(id)
                .orElseThrow(() -> new LeaveException("That leave type no longer exists."));

        BigDecimal previousDefault = leaveType.getDefaultDays();
        List<String> changes = new ArrayList<>();
        String trimmedName = name.trim();
        if (!trimmedName.equals(leaveType.getName())) {
            changes.add("renamed to \"%s\"".formatted(trimmedName));
            leaveType.setName(trimmedName);
        }
        if (description == null ? leaveType.getDescription() != null
                : !description.trim().equals(leaveType.getDescription())) {
            leaveType.setDescription(blankToNull(description));
        }
        if (defaultDays.compareTo(previousDefault) != 0) {
            changes.add("default %s -> %s day(s)".formatted(
                    LeaveCalculator.formatDays(previousDefault), LeaveCalculator.formatDays(defaultDays)));
            leaveType.setDefaultDays(defaultDays);
        }
        if (active != leaveType.isActive()) {
            changes.add(active ? "reactivated" : "deactivated");
            leaveType.setActive(active);
        }

        if (changes.isEmpty()) {
            return leaveType;
        }
        leaveTypeRepository.save(leaveType);
        auditService.record(actor, AuditAction.LEAVE_TYPE_UPDATED, "LeaveType", leaveType.getId(),
                "Updated %s".formatted(leaveType.getName()), Map.of("changes", changes));

        if (defaultDays.compareTo(previousDefault) != 0) {
            resyncUntouchedBalances(leaveType.getId(), previousDefault, defaultDays);
        }
        return leaveType;
    }

    private void resyncUntouchedBalances(String leaveTypeId, BigDecimal previous, BigDecimal updated) {
        for (var balance : balanceRepository.findByLeaveTypeIdAndYearAndEntitledAndUsedAndPending(
                leaveTypeId, LeaveCalculator.currentYear(), previous, BigDecimal.ZERO, BigDecimal.ZERO)) {
            balance.setEntitled(updated);
        }
    }

    /**
     * Sets an employee's allowance for a type and year.
     *
     * <p>Only {@code entitled} ever changes: days already used or reserved are
     * untouched, and the new value may not drop below them, because doing so
     * would imply a commitment that no longer exists.
     */
    @Transactional
    public void setEntitlement(String userId, String leaveTypeId, int year, BigDecimal entitled, User actor) {
        var balance = balanceRepository.findByUserIdAndLeaveTypeIdAndYear(userId, leaveTypeId, year)
                .orElseThrow(() -> new LeaveException("That balance row does not exist."));

        BigDecimal committed = balance.committed();
        if (entitled.compareTo(committed) < 0) {
            throw new LeaveException("This employee already has %s day(s) used or pending. "
                    .formatted(LeaveCalculator.formatDays(committed))
                    + "Set the allowance to at least that.");
        }

        BigDecimal previous = balance.getEntitled();
        balance.setEntitled(entitled);
        balanceRepository.save(balance);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("userId", userId);
        metadata.put("leaveTypeId", leaveTypeId);
        metadata.put("year", year);
        metadata.put("from", previous);
        metadata.put("to", entitled);
        auditService.record(actor, AuditAction.ENTITLEMENT_UPDATED, "LeaveBalance", balance.getId(),
                "%s's %s allowance for %d changed from %s to %s day(s)".formatted(
                        balance.getUser().getName(), balance.getLeaveType().getName(), year,
                        LeaveCalculator.formatDays(previous), LeaveCalculator.formatDays(entitled)),
                metadata);
    }

    /**
     * Raised when one field's value is wrong in a way the form should point at,
     * such as an email that is taken or a password that does not match. The
     * field name lets the message land next to the right input.
     */
    public static class FieldErrorException extends RuntimeException {
        private final String field;

        public FieldErrorException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
