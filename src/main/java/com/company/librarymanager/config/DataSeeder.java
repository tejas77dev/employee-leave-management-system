package com.company.librarymanager.config;

import com.company.librarymanager.domain.AuditAction;
import com.company.librarymanager.domain.AuditLog;
import com.company.librarymanager.domain.Book;
import com.company.librarymanager.domain.BookIssue;
import com.company.librarymanager.domain.Category;
import com.company.librarymanager.domain.Fine;
import com.company.librarymanager.domain.IssueStatus;
import com.company.librarymanager.domain.Member;
import com.company.librarymanager.domain.Role;
import com.company.librarymanager.domain.User;
import com.company.librarymanager.repository.AuditLogRepository;
import com.company.librarymanager.repository.BookIssueRepository;
import com.company.librarymanager.repository.BookRepository;
import com.company.librarymanager.repository.CategoryRepository;
import com.company.librarymanager.repository.FineRepository;
import com.company.librarymanager.repository.MemberRepository;
import com.company.librarymanager.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates the demo accounts, catalogue and loans on first run.
 *
 * <p>Skipped entirely once any user exists, so restarting the app never
 * disturbs data entered since. That check is on {@code users} rather than on any
 * one table, so a half-populated database is left alone too rather than being
 * topped up into an inconsistent state.
 *
 * <p>Every row written here is real: the ISBNs are valid and the loans are
 * consistent with the copy counts, so the demo can be driven through the actual
 * UI and the rules still hold.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final BookRepository bookRepository;
    private final MemberRepository memberRepository;
    private final BookIssueRepository issueRepository;
    private final FineRepository fineRepository;
    private final AuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String demoPassword;

    public DataSeeder(UserRepository userRepository,
                      CategoryRepository categoryRepository,
                      BookRepository bookRepository,
                      MemberRepository memberRepository,
                      BookIssueRepository issueRepository,
                      FineRepository fineRepository,
                      AuditLogRepository auditLogRepository,
                      PasswordEncoder passwordEncoder,
                      @Value("${app.seed.enabled:true}") boolean enabled,
                      @Value("${app.seed.password:password123}") String demoPassword) {
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.bookRepository = bookRepository;
        this.memberRepository = memberRepository;
        this.issueRepository = issueRepository;
        this.fineRepository = fineRepository;
        this.auditLogRepository = auditLogRepository;
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

        User admin = createUser("Asha Admin", "admin@library.test", Role.ADMIN);
        User librarian = createUser("Ravi Librarian", "librarian@library.test", Role.LIBRARIAN);
        User member = createUser("Priya Member", "member@library.test", Role.MEMBER);

        Map<String, Category> categories = new LinkedHashMap<>();
        for (String name : List.of("Fiction", "Computing", "Science", "History")) {
            Category category = new Category();
            category.setName(name);
            category.setActive(true);
            categories.put(name, categoryRepository.save(category));
        }

        List<Book> books = createCatalogue(categories);

        Member memberRecord = createMemberRecord("Priya Member", "member@library.test", member,
                "Computer Science", "Library-201");
        createMemberRecord("Anil Kumar", "anil@library.test", null,
                "Mechanical", "Library-14");
        createMemberRecord("Sara Thomas", "sara@library.test", null,
                "Chemistry", "Library-27");

        createLoans(books, librarian);

        auditLogRepository.save(audit(admin, AuditAction.MEMBER_CREATED, "Member", null,
                "Seeded the demo catalogue, members and loans"));

        log.info("Seeded 3 accounts, {} categories, {} books, 3 members and 4 loans.",
                categories.size(), books.size());
    }

    private List<Book> createCatalogue(Map<String, Category> categories) {
        record Seed(String isbn, String title, String author, String publisher, String category,
                    int copies, int publishedYear, String rack) {
        }
        List<Seed> seeds = List.of(
                new Seed("9780132350884", "Clean Code", "Robert C. Martin", "Prentice Hall",
                        "Computing", 4, 2008, "C-01"),
                new Seed("9780201633610", "Design Patterns", "Erich Gamma et al.", "Addison-Wesley",
                        "Computing", 3, 1994, "C-02"),
                new Seed("9780134685991", "Effective Java", "Joshua Bloch", "Addison-Wesley",
                        "Computing", 5, 2018, "C-03"),
                new Seed("9780321125217", "Domain-Driven Design", "Eric Evans", "Addison-Wesley",
                        "Computing", 2, 2003, "C-04"),
                new Seed("9780134494166", "Clean Architecture", "Robert C. Martin", "Prentice Hall",
                        "Computing", 3, 2017, "C-05"),
                new Seed("9780262033848", "Introduction to Algorithms", "Cormen et al.",
                        "MIT Press", "Computing", 2, 2009, "C-06"),
                new Seed("9780140449136", "The Odyssey", "Homer", "Penguin Classics",
                        "Fiction", 6, 1996, "F-01"),
                new Seed("9780061120084", "To Kill a Mockingbird", "Harper Lee", "Harper Perennial",
                        "Fiction", 8, 2006, "F-02"),
                new Seed("9780141439518", "Pride and Prejudice", "Jane Austen", "Penguin Classics",
                        "Fiction", 5, 2002, "F-03"),
                new Seed("9780385737951", "The Book Thief", "Markus Zusak", "Knopf",
                        "Fiction", 4, 2005, "F-04"),
                new Seed("9780131103627", "The C Programming Language", "Kernighan and Ritchie",
                        "Prentice Hall", "Computing", 3, 1988, "C-07"),
                new Seed("9780321563842", "The C++ Programming Language", "Stroustrup",
                        "Addison-Wesley", "Computing", 2, 2013, "C-08"),
                new Seed("9780262041997", "Theoretical Neuroscience", "Dayan and Abbott",
                        "MIT Press", "Science", 2, 2001, "S-01"),
                new Seed("9780198788607", "The Selfish Gene", "Richard Dawkins",
                        "Oxford University Press", "Science", 4, 1989, "S-02"),
                new Seed("9780062316097", "Sapiens", "Yuval Noah Harari", "Harper",
                        "History", 5, 2015, "H-01"),
                new Seed("9780307277671", "The Road", "Cormac McCarthy", "Vintage",
                        "Fiction", 3, 2007, "F-05"),
                new Seed("9780684801223", "The Old Man and the Sea", "Ernest Hemingway",
                        "Scribner", "Fiction", 2, 1999, "F-06"),
                new Seed("9780226458120", "The Structure of Scientific Revolutions",
                        "Thomas S. Kuhn", "University of Chicago Press", "Science", 2, 2012, "S-03"),
                new Seed("9780060883287", "One Hundred Years of Solitude", "Gabriel Garcia Marquez",
                        "Harper Perennial", "Fiction", 3, 2006, "F-07"),
                new Seed("9780618649068", "Silent Spring", "Rachel Carson", "Mariner Books",
                        "Science", 4, 2002, "S-04"));

        List<Book> books = new java.util.ArrayList<>();
        for (Seed seed : seeds) {
            Book book = new Book();
            book.setIsbn(seed.isbn());
            book.setTitle(seed.title());
            book.setAuthor(seed.author());
            book.setPublisher(seed.publisher());
            book.setCategory(categories.get(seed.category()));
            book.setTotalCopies(seed.copies());
            book.setAvailableCopies(seed.copies());
            book.setPublishedYear(seed.publishedYear());
            book.setRackNo(seed.rack());
            book.setActive(true);
            books.add(bookRepository.save(book));
        }
        return books;
    }

    private Member createMemberRecord(String name, String email, User user, String department, String phone) {
        Member member = new Member();
        member.setMemberId(nextCardNumber());
        member.setUser(user);
        member.setPhone(phone);
        member.setDepartment(department);
        member.setAddress(null);
        member.setMaxBooksAllowed(4);
        member.setActive(true);
        return memberRepository.save(member);
    }

    /**
     * A few loans in different states, so every desk screen has something real
     * to show: one comfortably in date, one about to fall due, one overdue, and
     * one already returned late with an unsettled fine.
     */
    private void createLoans(List<Book> books, User librarian) {
        if (books.size() < 4) {
            return;
        }
        LocalDate today = LocalDate.now();

        issueOpen(books.get(0), librarian, today.plusDays(3), today.minusDays(11));    // comfortable
        issueOpen(books.get(6), librarian, today.plusDays(1), today.minusDays(13));    // due soon
        issueOpen(books.get(13), librarian, today.minusDays(6), today.minusDays(20));  // overdue
        issueReturnedLate(books.get(8), librarian, today.minusDays(30), today.minusDays(16));
    }

    /**
     * An open loan, with the copy count moved to match so the seeded
     * availability figures agree with the loans.
     */
    private void issueOpen(Book book, User librarian, LocalDate dueDate, LocalDate issueDate) {
        BookIssue issue = newLoan(book, librarian, issueDate, dueDate);
        issue.setStatus(IssueStatus.ISSUED);
        issueRepository.save(issue);

        book.setAvailableCopies(Math.max(0, book.getAvailableCopies() - 1));
        bookRepository.save(book);
    }

    /**
     * A loan already closed by a late return, carrying the fine that followed.
     *
     * <p>Written directly rather than through {@code LendingService} so the
     * seeder does not need an actor for every operation and does not fill the
     * activity log with demo noise. The copy is put back on the shelf, exactly
     * as a real return would, so availability still balances against the loans.
     */
    private void issueReturnedLate(Book book, User librarian, LocalDate issueDate, LocalDate dueDate) {
        LocalDate returnedOn = dueDate.plusDays(4);

        BookIssue issue = newLoan(book, librarian, issueDate, dueDate);
        issue.setStatus(IssueStatus.RETURNED);
        issue.setReturnDate(returnedOn);
        issue.setRemarks("Seeded demo: returned 4 days late");
        issue.setFineAmount(new java.math.BigDecimal("4.00"));
        issue = issueRepository.save(issue);

        Fine fine = new Fine();
        fine.setIssue(issue);
        fine.setAmount(new java.math.BigDecimal("4.00"));
        fine.setPaid(false);
        fineRepository.save(fine);
    }

    private BookIssue newLoan(Book book, User librarian, LocalDate issueDate, LocalDate dueDate) {
        Member member = memberRepository.findAllByActiveTrueOrderByMemberIdAsc().get(0);
        BookIssue issue = new BookIssue();
        issue.setBook(book);
        issue.setMember(member);
        issue.setIssuedBy(librarian);
        issue.setIssueDate(issueDate);
        issue.setDueDate(dueDate);
        return issue;
    }

    private String nextCardNumber() {
        String highest = memberRepository.findHighestMemberId();
        int next = 1;
        if (highest != null && highest.startsWith("LIB-")) {
            try {
                next = Integer.parseInt(highest.substring(4)) + 1;
            } catch (NumberFormatException ex) {
                next = 1;
            }
        }
        return "LIB-" + String.format("%04d", next);
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

    private AuditLog audit(User actor, AuditAction action, String entityType, String entityId,
                           String summary) {
        AuditLog entry = new AuditLog();
        entry.setActor(actor);
        entry.setActorName(actor == null ? "System" : actor.getName());
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setSummary(summary);
        return entry;
    }
}
