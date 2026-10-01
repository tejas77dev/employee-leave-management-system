package com.company.leavemanager.tools;

import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Drops the development database so the next start rebuilds it from the
 * Flyway migrations.
 *
 * <p>Only for a throwaway local database. Run it deliberately, never as part
 * of application startup. Credentials come from the environment, so nothing
 * is committed here.
 */
public final class DbReset {

    private static final String SERVER_URL =
            "jdbc:mysql://localhost:3306/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    private DbReset() {
    }

    public static void main(String[] args) throws SQLException {
        String database = args.length > 0 ? args[0] : "leave_management";
        String username = required("SPRING_DATASOURCE_USERNAME");
        String password = System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "");

        try (var connection = DriverManager.getConnection(SERVER_URL, username, password);
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            System.out.println("Dropped `" + database
                    + "`. It is recreated on the next application start.");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Set " + name + " before running DbReset; it refuses to guess a credential.");
        }
        return value;
    }
}