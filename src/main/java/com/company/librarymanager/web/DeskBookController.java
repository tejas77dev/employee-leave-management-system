package com.company.librarymanager.web;

import com.company.librarymanager.domain.Book;
import com.company.librarymanager.repository.BookRepository;
import com.company.librarymanager.repository.CategoryRepository;
import com.company.librarymanager.security.AppUserPrincipal;
import com.company.librarymanager.service.CatalogService;
import com.company.librarymanager.service.LibraryException;
import com.company.librarymanager.validation.BookFormValidator;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * Editing the catalogue: adding and amending titles.
 *
 * <p>Under {@code /desk/**}, so open to librarians as well as administrators.
 * Unlike the account pages, the catalogue is day-to-day work rather than
 * something that changes who can do what.
 */
@Controller
public class DeskBookController {

    private static final int PAGE_SIZE = 30;

    private final CatalogService catalogService;
    private final BookRepository bookRepository;
    private final CategoryRepository categoryRepository;

    public DeskBookController(CatalogService catalogService,
                              BookRepository bookRepository,
                              CategoryRepository categoryRepository) {
        this.catalogService = catalogService;
        this.bookRepository = bookRepository;
        this.categoryRepository = categoryRepository;
    }

    /**
     * The editable catalogue.
     *
     * <p>Shows retired titles too, unlike the public catalogue. A librarian needs
     * to find a title they took out of circulation in order to put it back; a
     * search that hid them would make that impossible.
     */
    @GetMapping("/desk/books")
    public String books(@RequestParam(required = false) String q,
                        @RequestParam(required = false) String category,
                        @RequestParam(required = false, defaultValue = "0") int page,
                        Model model) {
        String query = blankToNull(q);
        String categoryId = blankToNull(category);
        int safePage = Math.max(0, page);
        // Retired titles are included here, unlike the public catalogue: a
        // librarian has to be able to find a title in order to put it back.
        long total = bookRepository.countSearch(query, categoryId, false);

        model.addAttribute("books", bookRepository.search(query, categoryId, false,
                PageRequest.of(safePage, PAGE_SIZE)));
        model.addAttribute("query", query);
        model.addAttribute("category", categoryId);
        model.addAttribute("categories", categoryRepository.findAllByActiveTrueOrderByNameAsc());
        model.addAttribute("titleCount", bookRepository.countByActiveTrue());
        model.addAttribute("availableCount", bookRepository.countByActiveTrueAndAvailableCopiesGreaterThan(0));
        model.addAttribute("total", total);
        model.addAttribute("page", safePage);
        model.addAttribute("pageSize", PAGE_SIZE);
        model.addAttribute("hasNext", (long) (safePage + 1) * PAGE_SIZE < total);
        return "desk/books";
    }

    @GetMapping("/desk/books/new")
    public String newBook(Model model) {
        model.addAttribute("categories", categoryRepository.findAllByActiveTrueOrderByNameAsc());
        model.addAttribute("editing", false);
        return "desk/book-form";
    }

    @GetMapping("/desk/books/{id}/edit")
    public String editBook(@PathVariable String id, Model model) {
        Book book = bookRepository.findById(id)
                .orElseThrow(() -> new LibraryException("That book no longer exists."));
        model.addAttribute("book", book);
        model.addAttribute("categories", categoryRepository.findAllByActiveTrueOrderByNameAsc());
        model.addAttribute("editing", true);
        return "desk/book-form";
    }

    /**
     * Saves a title.
     *
     * <p>Errors are re-rendered against the form rather than redirected away,
     * because a book form has eight fields and losing them all to fix one is
     * worse than a long URL. Anything unexpected is reported without its detail,
     * so an internal failure never leaks database text to the user.
     */
    @PostMapping("/desk/books/save")
    public String saveBook(@RequestParam(required = false) String id,
                           @RequestParam(required = false) String isbn,
                           @RequestParam(required = false) String title,
                           @RequestParam(required = false) String author,
                           @RequestParam(required = false) String publisher,
                           @RequestParam(required = false) String categoryId,
                           @RequestParam(required = false) String totalCopies,
                           @RequestParam(required = false) String publishedYear,
                           @RequestParam(required = false) String rackNo,
                           @AuthenticationPrincipal AppUserPrincipal principal,
                           Model model) {
        Integer copies = BookFormValidator.of().copies(totalCopies);
        Map<String, String> errors = BookFormValidator.of()
                .validate(id, isbn, title, author, publisher, totalCopies, publishedYear, rackNo);

        if (!errors.isEmpty()) {
            return reRender(id, isbn, title, author, publisher, categoryId, totalCopies,
                    publishedYear, rackNo, errors, null, model);
        }

        try {
            catalogService.saveBook(id, isbn, title, author, publisher, categoryId, copies,
                    parseYear(publishedYear), rackNo, Actors.of(principal));
        } catch (CatalogService.FieldErrorException ex) {
            errors.put(ex.field(), ex.getMessage());
            return reRender(id, isbn, title, author, publisher, categoryId, totalCopies,
                    publishedYear, rackNo, errors, null, model);
        } catch (LibraryException ex) {
            return reRender(id, isbn, title, author, publisher, categoryId, totalCopies,
                    publishedYear, rackNo, new java.util.LinkedHashMap<>(), ex.getMessage(), model);
        } catch (RuntimeException ex) {
            return reRender(id, isbn, title, author, publisher, categoryId, totalCopies,
                    publishedYear, rackNo, new java.util.LinkedHashMap<>(),
                    "That book could not be saved. Check the details and try again.", model);
        }
        return "redirect:/desk/books?saved=true";
    }

    @PostMapping("/desk/books/{id}/retire")
    public String retire(@PathVariable String id, @AuthenticationPrincipal AppUserPrincipal principal) {
        try {
            catalogService.deactivateBook(id, Actors.of(principal));
        } catch (LibraryException ex) {
            return "redirect:/desk/books?error=" + encode(ex.getMessage());
        }
        return "redirect:/desk/books?retired=true";
    }

    @PostMapping("/desk/books/{id}/restore")
    public String restore(@PathVariable String id, @AuthenticationPrincipal AppUserPrincipal principal) {
        try {
            catalogService.reactivateBook(id, Actors.of(principal));
        } catch (LibraryException ex) {
            return "redirect:/desk/books?error=" + encode(ex.getMessage());
        }
        return "redirect:/desk/books?restored=true";
    }

    /**
     * Rebuilds the form with whatever was typed, so a rejected submission does
     * not clear the page.
     */
    private String reRender(String id, String isbn, String title, String author, String publisher,
                            String categoryId, String totalCopies, String publishedYear, String rackNo,
                            Map<String, String> errors, String banner, Model model) {
        model.addAttribute("fieldErrors", errors);
        model.addAttribute("error", banner);
        model.addAttribute("categories", categoryRepository.findAllByActiveTrueOrderByNameAsc());
        model.addAttribute("editing", id != null && !id.isBlank());
        model.addAttribute("form", new BookForm(id, isbn, title, author, publisher,
                categoryId, totalCopies, publishedYear, rackNo));
        if (id != null && !id.isBlank()) {
            bookRepository.findById(id).ifPresent(book -> model.addAttribute("book", book));
        }
        return "desk/book-form";
    }

    /** What the book form binds to when it is re-rendered after a rejection. */
    public record BookForm(String id, String isbn, String title, String author, String publisher,
                           String categoryId, String totalCopies, String publishedYear, String rackNo) {
    }

    private static Integer parseYear(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String encode(String message) {
        return java.net.URLEncoder.encode(message, java.nio.charset.StandardCharsets.UTF_8);
    }
}
