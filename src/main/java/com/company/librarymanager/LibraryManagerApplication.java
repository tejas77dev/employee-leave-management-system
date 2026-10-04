package com.company.librarymanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Library book management, lending, returns, and fines.
 *
 * <p>Runs on MySQL, with the schema owned by Flyway and validated by Hibernate
 * on startup, so a mismatch between an entity and a migration fails fast
 * rather than at the first request that touches the affected table.
 */
@SpringBootApplication
public class LibraryManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LibraryManagerApplication.class, args);
    }
}
