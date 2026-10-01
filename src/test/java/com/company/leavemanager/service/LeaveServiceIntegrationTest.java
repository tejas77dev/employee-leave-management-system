package com.company.leavemanager.service;

import com.company.leavemanager.domain.LeaveType;
import com.company.leavemanager.domain.RequestStatus;
import com.company.leavemanager.domain.Role;
import com.company.leavemanager.domain.User;
import com.company.leavemanager.repository.AuditLogRepository;
import com.company.leavemanager.repository.LeaveBalanceRepository;
import com.company.leavemanager.repository.LeaveRequestRepository;
import com.company.leavemanager.repository.LeaveTypeRepository;
import com.company.leavemanager.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end checks against the real MySQL schema.
 *
 * <p>These exist because the parts of this app most likely to break are the
 * parts that only fail under a real database: the conditional balance updates
 * and the row locks. An in-memory substitute would not reproduce either, so the
 * tests talk to the same MySQL instance the app uses and clean up after
 * themselves.
 *
 * <p>Run with {@code mvn test -Dtest=LeaveServiceIntegrationTest}. These need a
 * reachable MySQL, since that is the behaviour under test; point them at another
 * instance with {@code SPRING_DATASOURCE_URL} and
 * {@code SPRING_DATASOURCE_PASSWORD} if you would rather not use the local one.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.clean-disabled=false",
        "app.seed.enabled=false"
})
@DisplayName("leave submission, approval and concurrency")
class LeaveServiceIntegrationTest {

    @Autowired
    private LeaveService leaveService;

    @Autowired
    private LeaveTypeRepository leaveTypeRepository;

    @Autowired
    private LeaveRequestRepository requestRepository;

    @Autowired
    private LeaveBalanceRepository balanceRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private LeaveType leaveType;
    private User employee;
    private User reviewer;

    @BeforeEach
    void setUp() {
        leaveType = new LeaveType();
        leaveType.setId(UUID.randomUUID().toString());
        leaveType.setName("Test leave " + UUID.randomUUID().toString().substring(0, 8));
        leaveType.setDefaultDays(new BigDecimal("3"));
        leaveType.setActive(true);
        leaveTypeRepository.save(leaveType);

        employee = createUser(Role.EMPLOYEE);
        reviewer = createUser(Role.HR);

        // Create the balance up front. Seeding it here keeps these tests on the
        // reservation and review paths; the lazy creation race is exercised by
        // its own test rather than as a side effect of every other one.
        leaveService.ensureBalance(employee.getId(), leaveType.getId(), LocalDate.now().getYear());
    }

    @AfterEach
    void tearDown() {
        // Order matters: requests and balances both reference the users and the
        // leave type, so they are cleared before those rows go.
        clearFor(employee.getId());
        clearFor(reviewer.getId());
        leaveTypeRepository.deleteById(leaveType.getId());
        userRepository.deleteById(employee.getId());
        userRepository.deleteById(reviewer.getId());
    }

    private User createUser(Role role) {
        User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setName(role == Role.HR ? "Reviewer " + UUID.randomUUID().toString().substring(0, 6) : "Staff");
        user.setEmail("user-" + UUID.randomUUID() + "@example.test");
        user.setRole(role);
        user.setActive(true);
        user.setPasswordHash(passwordEncoder.encode("password123"));
        return userRepository.save(user);
    }

    private LeaveService.CreateRequestCommand request(LocalDate start, LocalDate end) {
        return new LeaveService.CreateRequestCommand(
                employee.getId(), leaveType.getId(), start, end, false, null, "integration test");
    }

    @Test
    @DisplayName("a balance is created from the type default when none exists")
    void balanceIsCreatedFromTheTypeDefault() {
        // A user with no row for this type yet.
        User fresh = createUser(Role.EMPLOYEE);
        try {
            leaveService.createRequest(new LeaveService.CreateRequestCommand(
                    fresh.getId(), leaveType.getId(), monday(), monday().plusDays(1),
                    false, null, "first request"), fresh);

            LeaveService.BalanceView created = leaveService
                    .balancesFor(fresh.getId(), LocalDate.now().getYear()).stream()
                    .filter(view -> view.leaveType().getId().equals(leaveType.getId()))
                    .findFirst()
                    .orElseThrow();
            // Default is three days, two of which are now reserved.
            assertThat(created.entitled()).isEqualByComparingTo("3");
            assertThat(created.pending()).isEqualByComparingTo("2");
        } finally {
            clearFor(fresh.getId());
            userRepository.deleteById(fresh.getId());
        }
    }

    /** Removes the rows a test user owns, in dependency order. */
    private void clearFor(String userId) {
        int year = LocalDate.now().getYear();
        requestRepository.findAll().stream()
                .filter(request -> userId.equals(request.getUser().getId()))
                .forEach(requestRepository::delete);
        balanceRepository.findByYear(year).stream()
                .filter(balance -> userId.equals(balance.getUser().getId())
                        || leaveType.getId().equals(balance.getLeaveType().getId()))
                .forEach(balanceRepository::delete);
        auditLogRepository.findAll().stream()
                .filter(entry -> entry.getActor() != null && userId.equals(entry.getActor().getId()))
                .forEach(auditLogRepository::delete);
    }

    @Test
    @DisplayName("a submitted request reserves its days")
    void submittingReservesDays() {
        LeaveService.BalanceView before = balance();
        assertThat(before.remaining()).isEqualByComparingTo("3");

        leaveService.createRequest(request(monday(), monday().plusDays(1)), employee);

        LeaveService.BalanceView after = balance();
        assertThat(after.remaining()).isEqualByComparingTo("1");
        assertThat(after.pending()).isEqualByComparingTo("2");
        assertThat(after.used()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a request beyond the remaining balance is refused")
    void overdrawingIsRefused() {
        LeaveException refused = org.junit.jupiter.api.Assertions.assertThrows(LeaveException.class,
                () -> leaveService.createRequest(request(monday(), monday().plusDays(9)), employee));
        assertThat(refused.getMessage()).isNotBlank();
        assertThat(balance().pending()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("weekends cost nothing, so a weekend-only range is refused as empty")
    void weekendOnlyRangeIsRefused() {
        LocalDate saturday = next(DayOfWeek.SATURDAY);
        org.junit.jupiter.api.Assertions.assertThrows(LeaveException.class,
                () -> leaveService.createRequest(request(saturday, saturday.plusDays(1)), employee));
    }

    @Test
    @DisplayName("overlapping dates are refused while the first request is pending")
    void overlappingDatesAreRefused() {
        leaveService.createRequest(request(monday(), monday().plusDays(1)), employee);
        org.junit.jupiter.api.Assertions.assertThrows(LeaveException.class,
                () -> leaveService.createRequest(request(monday().plusDays(1), monday().plusDays(2)), employee));
    }

    @Test
    @DisplayName("approving moves the days from pending to used")
    void approvalMovesDaysFromPendingToUsed() {
        var created = leaveService.createRequest(request(monday(), monday().plusDays(1)), employee);
        assertThat(balance().pending()).isEqualByComparingTo("2");

        leaveService.reviewRequest(created.getId(), RequestStatus.APPROVED, "ok", reviewer);

        LeaveService.BalanceView after = balance();
        assertThat(after.pending()).isEqualByComparingTo("0");
        assertThat(after.used()).isEqualByComparingTo("2");
        assertThat(after.remaining()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("rejecting frees the reserved days")
    void rejectionReleasesTheReservation() {
        var created = leaveService.createRequest(request(monday(), monday().plusDays(1)), employee);

        leaveService.reviewRequest(created.getId(), RequestStatus.REJECTED, "no", reviewer);

        LeaveService.BalanceView after = balance();
        assertThat(after.pending()).isEqualByComparingTo("0");
        assertThat(after.used()).isEqualByComparingTo("0");
        assertThat(after.remaining()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("the requester cannot review their own request")
    void selfReviewIsRefused() {
        var created = leaveService.createRequest(request(monday(), monday()), employee);
        org.junit.jupiter.api.Assertions.assertThrows(LeaveException.class,
                () -> leaveService.reviewRequest(created.getId(), RequestStatus.APPROVED, "", employee));
    }

    @Test
    @DisplayName("a second review of the same request is refused")
    void doubleReviewIsRefused() {
        var created = leaveService.createRequest(request(monday(), monday()), employee);
        leaveService.reviewRequest(created.getId(), RequestStatus.APPROVED, "", reviewer);
        org.junit.jupiter.api.Assertions.assertThrows(LeaveException.class,
                () -> leaveService.reviewRequest(created.getId(), RequestStatus.REJECTED, "", reviewer));
        // The balance must not have moved twice.
        assertThat(balance().used()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("concurrent submissions cannot overdraw the last days")
    void concurrentSubmissionsCannotOverdraw() throws Exception {
        // Three days available, three simultaneous requests for two days each.
        // The date ranges are spaced apart on purpose: they must not overlap one
        // another, or the overlap check would reject the losers and the balance
        // race would never be reached. Only the sufficiency check can refuse
        // here, and exactly one request can win against three available days.
        int threads = 3;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int offset = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        leaveService.createRequest(new LeaveService.CreateRequestCommand(
                                employee.getId(), leaveType.getId(),
                                monday().plusDays(offset * 14), monday().plusDays(offset * 14 + 1),
                                false, null, "race"), employee);
                        accepted.incrementAndGet();
                    } catch (LeaveException expected) {
                        // Refused because the balance was already spoken for.
                        refused.incrementAndGet();
                    } catch (PessimisticLockingFailureException deadlock) {
                        // InnoDB picked this thread as the deadlock victim. The
                        // balance is untouched, so it counts as a refusal.
                        refused.incrementAndGet();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // Exactly one of the three can be honoured against three days. The
        // others must be refused, whether by the sufficiency test or by losing
        // a lock race, and no outcome may drive the balance negative.
        LeaveService.BalanceView after = balance();
        assertThat(accepted.get()).isEqualTo(1);
        assertThat(refused.get()).isEqualTo(threads - 1);
        assertThat(after.pending()).isEqualByComparingTo("2");
        assertThat(after.used()).isEqualByComparingTo("0");
        assertThat(after.remaining()).isEqualByComparingTo("1");
        assertThat(after.remaining()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    private LeaveService.BalanceView balance() {
        return leaveService.balancesFor(employee.getId(), LocalDate.now().getYear()).stream()
                .filter(view -> view.leaveType().getId().equals(leaveType.getId()))
                .findFirst()
                .orElseThrow();
    }

    /** A Monday at least a week out, so requests stay in the current year. */
    private LocalDate monday() {
        return next(DayOfWeek.MONDAY).plusWeeks(1);
    }

    private LocalDate next(DayOfWeek day) {
        LocalDate cursor = LocalDate.now().plusDays(1);
        while (cursor.getDayOfWeek() != day) {
            cursor = cursor.plusDays(1);
        }
        return cursor;
    }
}