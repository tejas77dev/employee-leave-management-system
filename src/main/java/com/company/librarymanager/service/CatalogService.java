package com.company.librarymanager.service;

import com.company.librarymanager.domain.AuditAction;
import com.company.librarymanager.domain.Book;
import com.company.librarymanager.domain.Category;
import com.company.librarymanager.domain.User;
import com.company.librarymanager.repository.BookRepository;
import com.company.librarymanager.repository.CategoryRepository;
import com.company.librarymanager.validation.Isbn;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalogue: titles, their copies, and the categories they sit in.
 *
 * <p>Copy counts are the subtle part. {@code availableCopies} is never set from
 * a submitted form, because the form cannot know how many copies are out on
 * loan. It is always derived: copies out is {@code totalCopies -
 * availableCopies}, and available is recomputed as {@code newTotal - issued}.
 * A librarian therefore edits how many copies the library holds and never the
 * availability, which is not theirs to set.
 */
@Service
public class CatalogService {

    private final BookRepository bookRepository;
    private final CategoryRepository categoryRepository;
    private final AuditService auditService;

    public CatalogService(BookRepository bookRepository,
                          CategoryRepository categoryRepository,
                          AuditService auditService) {
        this.bookRepository = bookRepository;
        this.categoryRepository = categoryRepository;
        this.auditService = auditService;
    }

    /**
     * Creates or updates a title, decided by whether an id was supplied.
     *
     * <p>A new title starts with every copy on the shelf. An existing one may not
     * be reduced below the number currently on loan, because that would silently
     * write off books that are in someone's hands.
     */
    @Transactional
    public Book saveBook(String id,
                         String isbn,
                         String title,
                         String author,
                         String publisher,
                         String categoryId,
                         Integer totalCopies,
                         Integer publishedYear,
                         String rackNo,
                         User actor) {
        // The column is NOT NULL and the copy arithmetic below unboxes this, so
        // a missing count has to be refused here rather than surfacing as a
        // constraint violation or a NullPointerException deeper in.
        if (totalCopies == null) {
            throw new FieldErrorException("totalCopies", "Enter how many copies are held");
        }
        if (id == null || id.isBlank()) {
            return createBook(isbn, title, author, publisher, categoryId, totalCopies, publishedYear, rackNo, actor);
        }
        return updateBook(id, isbn, title, author, publisher, categoryId, totalCopies, publishedYear, rackNo, actor);
    }

    private Book createBook(String isbn, String title, String author, String publisher, String categoryId,
                            Integer totalCopies, Integer publishedYear, String rackNo, User actor) {
        String normalisedIsbn = Isbn.normalise(isbn);
        if (normalisedIsbn == null || bookRepository.existsByIsbn(normalisedIsbn)) {
            throw new FieldErrorException("isbn", "A book with that ISBN already exists");
        }

        Book book = new Book();
        book.setIsbn(normalisedIsbn);
        applyFields(book, title, author, publisher, categoryId, publishedYear, rackNo);
        // Nothing is on loan yet, so every copy is on the shelf.
        book.setTotalCopies(totalCopies);
        book.setAvailableCopies(totalCopies);
        book.setActive(true);

        try {
            book = bookRepository.saveAndFlush(book);
        } catch (DataIntegrityViolationException ex) {
            // The unique index is the backstop for a concurrent insert.
            throw new FieldErrorException("isbn", "A book with that ISBN already exists");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("isbn", book.getIsbn());
        metadata.put("copies", book.getTotalCopies());
        auditService.record(actor, AuditAction.BOOK_CREATED, "Book", book.getId(),
                "Added %s by %s (%d %s)".formatted(book.getTitle(), book.getAuthor(),
                        book.getTotalCopies(), book.getTotalCopies() == 1 ? "copy" : "copies"),
                metadata);
        return book;
    }

    private Book updateBook(String id, String isbn, String title, String author, String publisher,
                            String categoryId, Integer totalCopies, Integer publishedYear,
                            String rackNo, User actor) {
        Book book = bookRepository.findById(id)
                .orElseThrow(() -> new LibraryException("That book no longer exists."));

        String normalisedIsbn = Isbn.normalise(isbn);
        if (normalisedIsbn == null) {
            throw new FieldErrorException("isbn", Isbn.describe());
        }
        bookRepository.findByIsbn(normalisedIsbn)
                .filter(existing -> !existing.getId().equals(book.getId()))
                .ifPresent(existing -> {
                    throw new FieldErrorException("isbn", "A book with that ISBN already exists");
                });

        // Read the outstanding loans from the row as it stands now, not from the
        // copy held in memory, which may predate a concurrent issue.
        int issued = bookRepository.findById(id)
                .map(current -> current.getTotalCopies() - current.getAvailableCopies())
                .orElse(0);
        if (totalCopies < issued) {
            throw new LibraryException("%d %s of this title %s on loan. Take the copies back first, "
                            .formatted(issued, issued == 1 ? "copy is" : "copies are",
                                    issued == 1 ? "is" : "are")
                    + "or write them off as lost before reducing the total.");
        }

        List<String> changes = new ArrayList<>();
        if (!normalisedIsbn.equals(book.getIsbn())) {
            changes.add("ISBN %s -> %s".formatted(book.getIsbn(), normalisedIsbn));
            book.setIsbn(normalisedIsbn);
        }
        String trimmedTitle = title.trim();
        if (!trimmedTitle.equals(book.getTitle())) {
            changes.add("title \"%s\" -> \"%s\"".formatted(book.getTitle(), trimmedTitle));
            book.setTitle(trimmedTitle);
        }
        String trimmedAuthor = author.trim();
        if (!trimmedAuthor.equals(book.getAuthor())) {
            changes.add("author \"%s\" -> \"%s\"".formatted(book.getAuthor(), trimmedAuthor));
            book.setAuthor(trimmedAuthor);
        }
        String trimmedPublisher = blankToNull(publisher);
        if (!java.util.Objects.equals(trimmedPublisher, book.getPublisher())) {
            book.setPublisher(trimmedPublisher);
        }
        if (!java.util.Objects.equals(publishedYear, book.getPublishedYear())) {
            book.setPublishedYear(publishedYear);
        }
        if (!java.util.Objects.equals(blankToNull(rackNo), book.getRackNo())) {
            book.setRackNo(blankToNull(rackNo));
        }

        Category category = resolveCategory(categoryId);
        if (category != null && !category.getId().equals(book.getCategory() == null
                ? null : book.getCategory().getId())) {
            changes.add("category \"%s\"".formatted(category.getName()));
        }
        book.setCategory(category);

        if (totalCopies != book.getTotalCopies()) {
            changes.add("copies %d -> %d".formatted(book.getTotalCopies(), totalCopies));
            book.setTotalCopies(totalCopies);
            // Availability follows from what is out, never from the form.
            book.setAvailableCopies(totalCopies - issued);
        }

        if (changes.isEmpty()) {
            return book;
        }
        bookRepository.save(book);
        auditService.record(actor, AuditAction.BOOK_UPDATED, "Book", book.getId(),
                "Updated %s".formatted(book.getTitle()), Map.of("changes", changes));
        return book;
    }

    private void applyFields(Book book, String title, String author, String publisher,
                             String categoryId, Integer publishedYear, String rackNo) {
        book.setTitle(title.trim());
        book.setAuthor(author.trim());
        book.setPublisher(blankToNull(publisher));
        book.setPublishedYear(publishedYear);
        book.setRackNo(blankToNull(rackNo));
        book.setCategory(resolveCategory(categoryId));
    }

    private Category resolveCategory(String categoryId) {
        if (categoryId == null || categoryId.isBlank()) {
            return null;
        }
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new FieldErrorException("categoryId", "That category no longer exists"));
    }

    /**
     * Retires a title from the catalogue.
     *
     * <p>Soft delete rather than a row removal: the title stays as the target of
     * every loan that referenced it, and the copy counts no longer move because
     * the catalogue stops offering it.
     */
    @Transactional
    public void deactivateBook(String id, User actor) {
        Book book = bookRepository.findById(id)
                .orElseThrow(() -> new LibraryException("That book no longer exists."));
        if (!book.isActive()) {
            return;
        }
        book.setActive(false);
        bookRepository.save(book);
        auditService.record(actor, AuditAction.BOOK_DELETED, "Book", book.getId(),
                "Retired %s from the catalogue".formatted(book.getTitle()),
                Map.of("copiesOnLoan", book.issuedCopies()));
    }

    /** Puts a retired title back on the shelves. */
    @Transactional
    public void reactivateBook(String id, User actor) {
        Book book = bookRepository.findById(id)
                .orElseThrow(() -> new LibraryException("That book no longer exists."));
        if (book.isActive()) {
            return;
        }
        book.setActive(true);
        bookRepository.save(book);
        auditService.record(actor, AuditAction.BOOK_UPDATED, "Book", book.getId(),
                "Returned %s to the catalogue".formatted(book.getTitle()), null);
    }

    /**
     * Creates or updates a category, decided by whether an id was supplied.
     *
     * <p>A category is never deleted while books still point at it, because
     * {@code category_id} is nullable and clearing it would silently sweep every
     * title in that category out of its shelf. Retiring one instead hides it
     * from new books and leaves the existing ones categorised.
     */
    @Transactional
    public Category saveCategory(String id, String name, String description, boolean active, User actor) {
        if (id == null || id.isBlank()) {
            return createCategory(name, description, active, actor);
        }
        return updateCategory(id, name, description, active, actor);
    }

    private Category createCategory(String name, String description, boolean active, User actor) {
        String trimmedName = name.trim();
        if (categoryRepository.existsByNameIgnoreCase(trimmedName)) {
            throw new FieldErrorException("name", "A category with that name exists");
        }
        Category category = new Category();
        category.setName(trimmedName);
        category.setDescription(blankToNull(description));
        category.setActive(active);
        try {
            category = categoryRepository.saveAndFlush(category);
        } catch (DataIntegrityViolationException ex) {
            throw new FieldErrorException("name", "A category with that name exists");
        }
        auditService.record(actor, AuditAction.CATEGORY_CREATED, "Category", category.getId(),
                "Created the %s category".formatted(category.getName()), null);
        return category;
    }

    private Category updateCategory(String id, String name, String description, boolean active, User actor) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new LibraryException("That category no longer exists."));

        List<String> changes = new ArrayList<>();
        String trimmedName = name.trim();
        if (!trimmedName.equals(category.getName())) {
            categoryRepository.findByNameIgnoreCase(trimmedName)
                    .filter(existing -> !existing.getId().equals(category.getId()))
                    .ifPresent(existing -> {
                        throw new FieldErrorException("name", "A category with that name exists");
                    });
            changes.add("renamed to \"%s\"".formatted(trimmedName));
            category.setName(trimmedName);
        }
        if (active != category.isActive()) {
            changes.add(active ? "reactivated" : "retired");
            category.setActive(active);
        }
        category.setDescription(blankToNull(description));

        if (changes.isEmpty()) {
            return category;
        }
        categoryRepository.save(category);
        auditService.record(actor, AuditAction.CATEGORY_UPDATED, "Category", category.getId(),
                "Updated the %s category".formatted(category.getName()), Map.of("changes", changes));
        return category;
    }

    /**
     * Raised when one field's value is wrong in a way the form should point at,
     * such as an ISBN that is already catalogued. The field name lets the
     * message land next to the right input.
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
