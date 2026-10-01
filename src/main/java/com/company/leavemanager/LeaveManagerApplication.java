package com.company.leavemanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Employee leave requests, balances and approvals.
 *
 * <p>Runs on MySQL, with the schema owned by Flyway and validated by Hibernate
 * on startup, so a mismatch between an entity and a migration fails fast
 * rather than at the first request that touches the affected table.
 */
@SpringBootApplication
public class LeaveManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LeaveManagerApplication.class, args);
    }
}
