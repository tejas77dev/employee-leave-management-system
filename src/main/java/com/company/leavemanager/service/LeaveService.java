package com.company.leavemanager.service;

import com.company.leavemanager.domain.AuditAction;
import com.company.leavemanager.domain.LeaveBalance;
import com.company.leavemanager.domain.LeaveRequest;
import com.company.leavemanager.domain.LeaveType;
import com.company.leavemanager.domain.PartOfDay;
import com.company.leavemanager.domain.RequestStatus;
import com.company.leavemanager.domain.User;
import com.company.leavemanager.repository.LeaveBalanceRepository;
import com.company.leavemanager.repository.LeaveRequestRepository;
import com.company.leavemanager.repository.LeaveTypeRepository;
import com.company.leavemanager.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class LeaveService {

    private static final Set<RequestStatus> BLOCKING_STATUSES =
            EnumSet.of(RequestStatus.PENDING, RequestStatus.APPROVED);

    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveBalanceRepository balanceRepository;
    private final LeaveRequestRepository requestRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public LeaveService(LeaveTypeRepository leaveTypeRepository,
                        LeaveBalanceRepository balanceRepository,
                        LeaveRequestRepository requestRepository,
                        UserRepository userRepository,
                        AuditService auditService) {
        this.leaveTypeRepository = leaveTypeRepository;
        this.balanceRepository = balanceRepository;
        this.requestRepository = requestRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    public record CreateRequestCommand(String userId,
                                       String leaveTypeId,
                                       LocalDate startDate,
                                       LocalDate endDate,
                                       boolean halfDay,
                                       PartOfDay partOfDay,
                                       String reason) {
    }

    /**
     * Submits a leave request and reserves its days from the balance.
     *
     * <p>The reservation is a conditional update, so the check and the write are
     * a single statement that InnoDB evaluates under a row lock. Two requests
     * arriving together cannot both pass the sufficiency test, which is what
     * stops a balance being overdrawn.
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public LeaveRequest createRequest(CreateRequestCommand command, User actor) {
        LeaveType leaveType = leaveTypeRepository.findById(command.leaveTypeId())
                .filter(LeaveType::isActive)
                .orElseThrow(() -> new LeaveException("That leave type is not available."));

        BigDecimal days;
        try {
            days = LeaveCalculator.calculateDays(command.startDate(), command.endDate(), command.halfDay());
        } catch (IllegalArgumentException ex) {
            throw new LeaveException(ex.getMessage());
        }
        if (days.signum() <= 0) {
            throw new LeaveException("That range contains no working days.");
        }

        // A request is charged to the year it starts in.
        int year = command.startDate().getYear();
        LeaveBalance balance = ensureBalance(command.userId(), leaveType.getId(), year);

        // Serialise everything below per employee. Two overlapping requests can
        // use different leave types and so lock different balance rows, which
        // means the balance lock alone would not stop both passing the overlap
        // check below.
        userRepository.lockUser(command.userId());
        LeaveBalance locked = balanceRepository.findByIdForUpdate(balance.getId())
                .orElseThrow(() -> new LeaveException("No balance exists for that request."));

        Optional<LeaveRequest> overlap = requestRepository
                .findOverlapping(command.userId(), command.startDate(), command.endDate(),
                        BLOCKING_STATUSES, null)
                .stream()
                .findFirst();
        if (overlap.isPresent()) {
            LeaveRequest other = overlap.get();
            throw new LeaveException("These dates overlap your existing %s request (%s to %s)."
                    .formatted(other.getLeaveType().getName(),
                            LeaveCalculator.format(other.getStartDate()),
                            LeaveCalculator.format(other.getEndDate())));
        }

        reserve(locked, days, leaveType);

        LeaveRequest request = new LeaveRequest();
        request.setUser(userRepository.getReferenceById(command.userId()));
        request.setLeaveType(leaveType);
        request.setStartDate(command.startDate());
        request.setEndDate(command.endDate());
        request.setDays(days);
        request.setHalfDay(command.halfDay());
        request.setPartOfDay(command.halfDay() ? command.partOfDay() : null);
        request.setReason(blankToNull(command.reason()));
        request.setStatus(RequestStatus.PENDING);
        request = requestRepository.save(request);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("leaveType", leaveType.getName());
        metadata.put("days", days);
        metadata.put("isHalfDay", command.halfDay());
        auditService.record(actor, AuditAction.REQUEST_SUBMITTED, "LeaveRequest", request.getId(),
                "Requested %s day(s) of %s (%s%s)".formatted(
                        LeaveCalculator.formatDays(days),
                        leaveType.getName(),
                        LeaveCalculator.format(command.startDate()),
                        command.halfDay() ? "" : " to " + LeaveCalculator.format(command.endDate())),
                metadata);

        return request;
    }

    /**
     * Approves or rejects a request.
     *
     * <p>Both decisions release the pending reservation first; approval then
     * moves the days from {@code pending} to {@code used}. Each step is a
     * conditional update, so a double submission cannot release the same days
     * twice or approve against a balance that has since been reduced.
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public LeaveRequest reviewRequest(String requestId, RequestStatus decision, String reviewNote, User reviewer) {
        if (decision != RequestStatus.APPROVED && decision != RequestStatus.REJECTED) {
            throw new LeaveException("That request has already been reviewed.");
        }

        LeaveRequest request = requestRepository.findByIdWithDetails(requestId)
                .orElseThrow(() -> new LeaveException("That request no longer exists."));
        if (request.getStatus() != RequestStatus.PENDING) {
            throw new LeaveException("That request has already been reviewed.");
        }
        if (request.getUser().getId().equals(reviewer.getId())) {
            throw new LeaveException("You cannot review your own leave request.");
        }

        int year = request.getStartDate().getYear();
        LeaveBalance existing = balanceRepository
                .findByUserIdAndLeaveTypeIdAndYear(request.getUser().getId(), request.getLeaveType().getId(), year)
                .orElseThrow(() -> new LeaveException("No balance exists for that request."));

        userRepository.lockUser(request.getUser().getId());
        LeaveBalance balance = balanceRepository.findByIdForUpdate(existing.getId())
                .orElseThrow(() -> new LeaveException("No balance exists for that request."));

        release(balance, request.getDays());

        if (decision == RequestStatus.APPROVED) {
            consume(balance, request.getDays());
        }

        request.setStatus(decision);
        request.setReviewedBy(reviewer);
        request.setReviewNote(blankToNull(reviewNote));
        request.setReviewedAt(Instant.now());
        requestRepository.save(request);

        String verb = decision == RequestStatus.APPROVED ? "Approved" : "Rejected";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("employee", request.getUser().getName());
        metadata.put("leaveType", request.getLeaveType().getName());
        metadata.put("days", request.getDays());
        metadata.put("note", blankToNull(reviewNote));
        auditService.record(reviewer,
                decision == RequestStatus.APPROVED ? AuditAction.REQUEST_APPROVED : AuditAction.REQUEST_REJECTED,
                "LeaveRequest", request.getId(),
                "%s %s's %s day(s) of %s".formatted(verb, request.getUser().getName(),
                        LeaveCalculator.formatDays(request.getDays()), request.getLeaveType().getName()),
                metadata);

        return request;
    }

    /**
     * Finds or creates this year's balance row for a type, seeded from the
     * type's default allowance.
     *
     * <p>Joins the caller's transaction rather than opening its own, so it can
     * see leave types and users the caller created but has not yet committed.
     * A concurrent caller that inserts the same row first loses the unique-key
     * race, and its own row is used instead.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public LeaveBalance ensureBalance(String userId, String leaveTypeId, int year) {
        return balanceRepository.findByUserIdAndLeaveTypeIdAndYear(userId, leaveTypeId, year)
                .orElseGet(() -> {
                    LeaveType leaveType = leaveTypeRepository.findById(leaveTypeId)
                            .orElseThrow(() -> new LeaveException("That leave type no longer exists."));
                    LeaveBalance balance = new LeaveBalance();
                    balance.setUser(userRepository.getReferenceById(userId));
                    balance.setLeaveType(leaveType);
                    balance.setYear(year);
                    balance.setEntitled(leaveType.getDefaultDays());
                    balance.setUsed(BigDecimal.ZERO);
                    balance.setPending(BigDecimal.ZERO);
                    try {
                        return balanceRepository.saveAndFlush(balance);
                    } catch (DataIntegrityViolationException ex) {
                        // A concurrent submission created the row first. Its
                        // row is the correct one, so use it rather than fail.
                        return balanceRepository
                                .findByUserIdAndLeaveTypeIdAndYear(userId, leaveTypeId, year)
                                .orElseThrow(() -> ex);
                    }
                });
    }

    /**
     * Adds days to {@code pending}, but only if enough remain.
     *
     * <p>The condition lives in the SQL rather than in Java. Checking in Java
     * and then writing would let two requests both read the same available
     * figure and both proceed, which is the overdraw this prevents.
     */
    private void reserve(LeaveBalance balance, BigDecimal days, LeaveType leaveType) {
        int updated = balanceRepository.reserveDays(balance.getId(), days);
        if (updated == 0) {
            BigDecimal available = currentRemaining(balance.getId());
            throw new LeaveException("Not enough %s balance. You need %s day(s) but only have %s available."
                    .formatted(leaveType.getName(), LeaveCalculator.formatDays(days),
                            LeaveCalculator.formatDays(available)));
        }
    }

    /**
     * Removes days from {@code pending}, but only if the reservation is
     * actually still there, so a double submission cannot release twice.
     */
    private void release(LeaveBalance balance, BigDecimal days) {
        int updated = balanceRepository.releaseDays(balance.getId(), days);
        if (updated == 0) {
            throw new LeaveException("That request's balance reservation is no longer available. "
                    + "Reject the pending request instead.");
        }
    }

    /**
     * Moves days from {@code pending} into {@code used}.
     *
     * <p>{@code pending} is not part of the test: this request's own
     * reservation was already released, so the check is simply that the
     * entitlement covers what is already used plus these days.
     */
    private void consume(LeaveBalance balance, BigDecimal days) {
        int updated = balanceRepository.consumeDays(balance.getId(), days);
        if (updated == 0) {
            BigDecimal available = currentRemaining(balance.getId());
            throw new LeaveException("Cannot approve: %s has only %s day(s) left, but this request is %s."
                    .formatted(balance.getUser().getName(), LeaveCalculator.formatDays(available),
                            LeaveCalculator.formatDays(days)));
        }
    }

    /** Re-reads a balance, so an error message shows what is genuinely left. */
    private BigDecimal currentRemaining(String balanceId) {
        return balanceRepository.findById(balanceId)
                .map(LeaveBalance::remaining)
                .orElse(BigDecimal.ZERO);
    }

    /** Every active leave type, with this employee's balance or zeroes. */
    @Transactional(readOnly = true)
    public List<BalanceView> balancesFor(String userId, int year) {
        return leaveTypeRepository.findAllByActiveTrueOrderByNameAsc().stream()
                .map(type -> toView(userId, year, type))
                .toList();
    }

    private BalanceView toView(String userId, int year, LeaveType type) {
        return balanceRepository.findByUserIdAndLeaveTypeIdAndYear(userId, type.getId(), year)
                .map(balance -> new BalanceView(type, balance.getEntitled(),
                        balance.getUsed(), balance.getPending()))
                // An employee with no row yet for this type simply has nothing
                // allocated, which is different from being allocated zero.
                .orElseGet(() -> new BalanceView(type, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    public record BalanceView(LeaveType leaveType,
                              BigDecimal entitled,
                              BigDecimal used,
                              BigDecimal pending) {
        public BigDecimal remaining() {
            return LeaveCalculator.remaining(entitled, used, pending);
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
