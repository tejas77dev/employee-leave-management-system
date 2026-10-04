# Library Manager

Circulation desk for a small library: members borrow and return books, staff
issue and take them back, and everything that happens is written to an audit
log. Spring Boot, Thymeleaf and MySQL, with the schema owned by Flyway.

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

The app comes up on <http://localhost:8081> and Flyway creates or migrates the
schema on the way. If the database does not exist yet it is created, because
the JDBC URL carries `createDatabaseIfNotExist=true`.

The port is 8081 rather than the usual 8080 so it can run alongside whatever
else is already on 8080. Set `SERVER_PORT` to move it.

On first run with an empty database, demo data is seeded: 3 accounts, 4
categories, 20 books, 3 members and 4 loans — one already overdue, one returned
late with an unpaid fine.

| Email                 | Role     |
| --------------------- | -------- |
| `admin@library.test`    | Admin    |
| `librarian@library.test`| Librarian|
| `member@library.test`   | Member   |

All three use the password `password123`. Seeding is skipped whenever the
database already has users, so it will never overwrite real data.

## Tests

```powershell
.\mvnw.cmd test
```

63 tests, none of which need a running MySQL. Tests use an in-memory H2 database
and build the schema from the entity mappings; `V1__init.sql` is MySQL dialect,
so Flyway is switched off under `src/test`.

- `IsbnTest` — ISBN-10 and -13 syntax and checksums, including blank input.
- `LendingFormValidatorTest` — what the issue form can check without queries.
- `FineCalculatorTest` — the rate, the cap and the lost-copy charge.
- `CatalogueQueryTest` — the catalogue and lending-log queries against a real
  database, and that a book with no category is still found.
- `LendingServiceTest` — issue, return, loss and fine settlement, and that the
  copy counts in the database actually move.

## How the lending rules work

**Copy counts.** A title has a total and an available count. Issuing takes one
off the shelf, returning puts one back, and reporting a copy lost takes it out of
the total as well, because a lost book is no longer stock the library holds.

**Borrowing limits.** Each member has a maximum number of books out at once.
Enforced per member, not per title.

**Due dates.** Fourteen days by default. A book due back *today* is not late
until tomorrow, so the overdue test is `dueDate < today`.

**Fines.** 1.00 a day late, capped at 50.00 whatever the delay. A lost copy is a
flat 25.00 rather than a per-day rate: there is no due date left to run from, and
a member should not be billed an open-ended daily sum for a copy already
reported missing. Rounding happens once, at the end, so a total does not depend on
which arithmetic route produced it.

## Why the copy updates are written the way they are

The obvious implementation is to read the available count, check it in Java, and
write it back. That is wrong: two librarians issuing the last copy at the same
moment can both read 1 available and hand out two copies.

Instead each update carries its own sufficiency test in the `WHERE` clause and
reports how many rows it changed:

```sql
update Book b
   set b.availableCopies = b.availableCopies - 1
 where b.id = :id
   and b.active = true
   and b.availableCopies > 0
```

Zero rows changed means someone else got there first, and the service turns that
into a refusal. The check and the write are one statement, so MySQL evaluates
them under a row lock.

Two details matter and are easy to get wrong:

- **Bulk updates set `flushAutomatically` but not `clearAutomatically`.** Clearing
  detaches the whole persistence context, and these run mid-transaction in
  methods that still have to read the loan and member afterwards. Any association
  not already loaded becomes an uninitialisable proxy, and with
  `open-in-view=false` the read throws. Flushing is what is actually needed: a
  bulk update bypasses the context, so pending changes must be written first or
  they land on top of it.
- **The timestamp is a parameter, not `current_timestamp`.** That function yields
  a `java.sql.Timestamp`, which a MySQL mapping accepts but which cannot be
  assigned to an `Instant` field under any other database.

Issuing also takes a `PESSIMISTIC_WRITE` lock on the member row before checking
the borrowing limit. Two loans to the same member can be for different titles, so
locking a book row would not stop both passing the limit check; locking the member
row does, because every issue for that member contends on it.

Settling a fine is guarded the same way: the `paid = false` test sits inside the
update, so two clicks of "Mark paid" cannot both record a payment.

## A category is optional, and that shapes the queries

`books.category_id` is nullable and the desk accepts a blank one, so a title can
be filed uncategorised. Every query that fetches the category therefore uses a
**left** join. An inner join does not merely leave the category blank on the
page — it drops the book from the catalogue, the lending log, the overdue count
and the member's dashboard altogether. `CatalogueQueryTest` pins this.

The same reasoning applies to display queries generally: `open-in-view` is off,
so anything a view reads has to be fetched explicitly.

## Configuration

`application.properties` reads these from the environment, with local defaults:

| Variable                      | Default                                |
| ----------------------------- | -------------------------------------- |
| `SPRING_DATASOURCE_URL`       | `jdbc:mysql://localhost:3306/library_management?...` |
| `SPRING_DATASOURCE_USERNAME`  | `root`                                 |
| `SPRING_DATASOURCE_PASSWORD`  | `mysqladmin`                           |
| `SERVER_PORT`                 | `8081`                                 |
| `APP_SEED_ENABLED`            | `true`                                 |
| `APP_SEED_PASSWORD`           | `password123`                          |

Lending terms are `app.lending.loan-days` (14), `app.lending.daily-fine` (1.00),
`app.lending.max-fine` (50.00) and `app.lending.lost-book-charge` (25.00).

No credential is committed to the repository.

## Deploying

The defaults suit a local sandbox. Before deploying:

1. Set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and
   `SPRING_DATASOURCE_PASSWORD`.
2. Set `APP_SEED_ENABLED=false` so demo accounts are never created.
3. Drop `createDatabaseIfNotExist=true` from the URL and provision the database
   and its user yourself, so the application never needs `CREATE` rights.
4. Leave `spring.flyway.clean-disabled=true` alone. It is what stops a stray
   `flyway clean` from dropping a populated database.
5. Build with `.\mvnw.cmd clean package` and run the jar.

For a single VM serving an internal team, MySQL on the same host is
appropriate. If you run more than one application instance, use a managed MySQL
instead: sessions live in the `sessions` table, so instances share them, but a
local database does not follow you to a second host.

## Layout

```
src/main/java/com/company/librarymanager/
  domain/         JPA entities and enums
  repository/     Spring Data repositories, including the atomic copy updates
  service/        FineCalculator, LendingService, CatalogService, MemberService, AuditService
  security/       AppUserPrincipal and database-backed sessions
  validation/     Isbn and form-level checks
  web/            controllers and display formatting
  config/         security, seeding, app entry point
  tools/          DbReset, a destructive development helper
src/main/resources/
  db/migration/   Flyway migrations
  templates/      Thymeleaf views
  static/css/     stylesheet
```

## Development helpers

`DbReset` drops the database so the next start rebuilds it from the migrations
and reseeds. It refuses to guess a credential, so it needs
`SPRING_DATASOURCE_USERNAME` set, and it takes the schema name as its argument:

```powershell
$env:SPRING_DATASOURCE_USERNAME = "root"
$env:SPRING_DATASOURCE_PASSWORD = "your-password"
.\mvnw.cmd compile exec:java "-Dexec.mainClass=com.company.librarymanager.tools.DbReset" "-Dexec.args=library_management"
```

Only ever point it at a throwaway local database.