package com.company.leavemanager.config;

import com.company.leavemanager.domain.Role;
import com.company.leavemanager.domain.LeaveType;
import com.company.leavemanager.domain.User;
import com.company.leavemanager.repository.LeaveBalanceRepository;
import com.company.leavemanager.repository.LeaveTypeRepository;
import com.company.leavemanager.repository.UserRepository;
import com.company.leavemanager.service.LeaveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Creates the demo accounts, leave types and allowances on first run.
 *
 * <p>Skipped entirely once any user exists, so restarting the app never
 * disturbs data that has been entered since.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository userRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveBalanceRepository balanceRepository;
    private final LeaveService leaveService;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String demoPassword;

    public DataSeeder(UserRepository userRepository,
                      LeaveTypeRepository leaveTypeRepository,
                      LeaveBalanceRepository balanceRepository,
                      LeaveService leaveService,
                      PasswordEncoder passwordEncoder,
                      @Value("${app.seed.enabled:true}") boolean enabled,
                      @Value("${app.seed.password:password123}") String demoPassword) {
        this.userRepository = userRepository;
        this.leaveTypeRepository = leaveTypeRepository;
        this.balanceRepository = balanceRepository;
        this.leaveService = leaveService;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.demoPassword = demoPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (userRepository.count() > 0) {
            log.info("Demo data not seeded: the database already has users.");
            return;
        }

        List<User> users = List.of(
                createUser("Priya Nair", "hr@company.com", Role.HR),
                createUser("Sam Patel", "sam@company.com", Role.EMPLOYEE),
                createUser("Aisha Khan", "aisha@company.com", Role.EMPLOYEE));

        List<LeaveType> leaveTypes = List.of(
                createLeaveType("Casual Leave", "Short personal absences", "15"),
                createLeaveType("Sick Leave", "Illness and medical appointments", "12"),
                createLeaveType("Earned Leave", "Planned vacation and earned time off", "20"));

        int year = java.time.LocalDate.now().getYear();
        for (User user : users) {
            for (LeaveType type : leaveTypes) {
                leaveService.ensureBalance(user.getId(), type.getId(), year);
            }
        }

        log.info("Seeded {} users, {} leave types and {} balances for {}.",
                users.size(), leaveTypes.size(), users.size() * leaveTypes.size(), year);
    }

    private User createUser(String name, String email, Role role) {
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(demoPassword));
        user.setRole(role);
        user.setActive(true);
        return userRepository.save(user);
    }

    private LeaveType createLeaveType(String name, String description, String defaultDays) {
        LeaveType type = new LeaveType();
        type.setName(name);
        type.setDescription(description);
        type.setDefaultDays(new BigDecimal(defaultDays));
        type.setActive(true);
        return leaveTypeRepository.save(type);
    }
}
