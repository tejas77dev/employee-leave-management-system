# Leave Manager

Employee leave management: employees request leave, HR approves or rejects it,
and balances update automatically. Spring Boot, Thymeleaf and MySQL, with the
schema owned by Flyway.

## Requirements

- JDK 21
- MySQL 8 running locally

## Running it

Set the database credentials, then start the app:

```powershell
$env:SPRING_DATASOURCE_USERNAME = "root"
$env:SPRING_DATASOURCE_PASSWORD = "your-password"
.\mvnw.cmd spring-boot:run
```

The app comes up on <http://localhost:8080> and Flyway creates or migrates the
schema on the way. If the database does not exist yet it is created, because
the JDBC URL carries `createDatabaseIfNotExist=true`.

On first run with an empty database, demo data is seeded:

| Email              | Role     |
| ------------------ | -------- |
| `hr@company.com`   | HR       |
| `sam@company.com`  | Employee |
| `aisha@company.com`| Employee |

All three use the password `password123`. Seeding is skipped whenever the
database already has users, so it will never overwrite real data.

## Tests

```powershell
.\mvnw.cmd test
```

Three suites, none of which need a stub server or an in-memory database:

- `LeaveCalculatorTest` — weekday, half-day and overlap rules.
- `LeaveRequestValidatorTest` — form-level validation.
- `FormatsTest` — display formatting.
- `LeaveServiceIntegrationTest` — real MySQL. Covers reservation, approval,
  rejection, double-review refusal and a three-thread race for the last days.
  The concurrency test asserts the balance never goes negative.

## How the leave rules work

**Day counting.** A full-day request counts Monday-to-Friday days in an
inclusive range, so weekends inside a span are free. A half-day is always 0.5
and must fall on a single weekday.

**Overlaps.** Two requests count as overlapping if they share any single day.
Rejected requests never block dates. Dates are charged to the year the request
*starts* in.

**Balances.** Three figures per employee, leave type and year:

- `entitled` — the allowance
- `used` — approved days
- `pending` — days held by requests awaiting a decision

What an employee can request is `entitled - used - pending`.

## Why the balance updates are written the way they are

The obvious implementation is to read a balance, check the figure in Java, and
write it back. That is wrong: two requests arriving together can both read the
same available figure and both proceed, overdrawing the allowance.

Instead each update carries its own sufficiency test in the `WHERE` clause and
reports how many rows it changed:

```sql
update LeaveBalance b
   set b.pending = b.pending + :days
 where b.id = :id
   and (b.entitled - b.used - b.pending) >= :days
```

Zero rows changed means another writer got there first, and the service turns
that into a refusal. The check and the write are one statement, so MySQL
evaluates them under a row lock and two requests can never both consume the same
last day.

On top of that, submission takes a `PESSIMISTIC_WRITE` lock on the employee row
before checking for overlaps. Two overlapping requests can use *different* leave
types and therefore lock different balance rows, so the balance lock alone would
not stop both passing the overlap check.

## Configuration

`application.properties` reads these from the environment, with local defaults:

| Variable                      | Default                                |
| ----------------------------- | -------------------------------------- |
| `SPRING_DATASOURCE_URL`       | `jdbc:mysql://localhost:3306/leave_management?...` |
| `SPRING_DATASOURCE_USERNAME`  | `root`                                 |
| `SPRING_DATASOURCE_PASSWORD`  | `mysqladmin`                           |
| `APP_SEED_ENABLED`            | `true`                                 |

No credential is committed to the repository.

## Deploying

The defaults suit a local sandbox. Before deploying:

1. Set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and
   `SPRING_DATASOURCE_PASSWORD`.
2. Drop `createDatabaseIfNotExist=true` from the URL and provision the database
   and its user yourself, so the application never needs `CREATE` rights.
3. Leave `spring.flyway.clean-disabled=true` alone. It is what stops a stray
   `flyway clean` from dropping a populated database.
4. Build with `./mvnw.cmd clean package` and run the jar.

For a single VM serving an internal team, MySQL on the same host is
appropriate. If you run more than one application instance, use a managed MySQL
instead: sessions live in the `sessions` table, so instances share them, but a
local database does not follow you to a second host.

## Layout

```
src/main/java/com/company/leavemanager/
  domain/         JPA entities and enums
  repository/     Spring Data repositories, including the atomic balance updates
  service/        LeaveCalculator, LeaveService, AdminService, AuditService
  security/       AppUserPrincipal and database-backed sessions
  validation/     form-level checks
  web/            controllers and display formatting
  config/         security, seeding, app entry point
  tools/          DbReset, a destructive development helper
src/main/resources/
  db/migration/   Flyway migrations
  templates/      Thymeleaf views
  static/css/     stylesheet
```