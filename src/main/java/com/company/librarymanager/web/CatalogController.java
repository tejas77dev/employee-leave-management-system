package com.company.librarymanager.web;

import com.company.librarymanager.domain.Book;
import com.company.librarymanager.domain.BookIssue;
import com.company.librarymanager.domain.BookRequest;
import com.company.librarymanager.domain.IssueStatus;
import com.company.librarymanager.domain.Member;
import com.company.librarymanager.domain.RequestStatus;
import com.company.librarymanager.repository.BookIssueRepository;
import com.company.librarymanager.repository.BookRepository;
import com.company.librarymanager.repository.BookRequestRepository;
import com.company.librarymanager.repository.CategoryRepository;
import com.company.librarymanager.repository.MemberRepository;
import com.company.librarymanager.security.AppUserPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;

/**
 * Browsing and searching the catalogue, open to anyone signed in.
 *
 * <p>Searching is done in SQL rather than by filtering a list in Java, because
 * the result is paged: filtering after the fact would drop rows off the end of a
 * page and leave it showing fewer than a page of matches.
 */
@Controller
public class CatalogController {

    private static final int PAGE_SIZE = 24;

    private final BookRepository bookRepository;
    private final BookIssueRepository issueRepository;
    private final CategoryRepository categoryRepository;
    private final MemberRepository memberRepository;
    private final BookRequestRepository requestRepository;

    public CatalogController(BookRepository bookRepository,
                             BookIssueRepository issueRepository,
                             CategoryRepository categoryRepository,
                             MemberRepository memberRepository,
                             BookRequestRepository requestRepository) {
        this.bookRepository = bookRepository;
        this.issueRepository = issueRepository;
        this.categoryRepository = categoryRepository;
        this.memberRepository = memberRepository;
        this.requestRepository = requestRepository;
    }

    @GetMapping("/catalog")
    public String catalog(@RequestParam(required = false) String q,
                          @RequestParam(required = false) String category,
                          @RequestParam(required = false, defaultValue = "0") int page,
                          Model model) {
        String query = blankToNull(q);
        String categoryId = blankToNull(category);
        int safePage = Math.max(0, page);

        model.addAttribute("books", bookRepository.search(query, categoryId, true,
                PageRequest.of(safePage, PAGE_SIZE)));
        model.addAttribute("query", query);
        model.addAttribute("category", categoryId);
        model.addAttribute("categories", categoryRepository.findAllByActiveTrueOrderByNameAsc());
        model.addAttribute("total", bookRepository.countSearch(query, categoryId, true));
        model.addAttribute("page", safePage);
        model.addAttribute("pageSize", PAGE_SIZE);
        model.addAttribute("hasNext", (long) (safePage + 1) * PAGE_SIZE
                < bookRepository.countSearch(query, categoryId, true));
        return "catalog";
    }

    /**
     * A single title.
     *
     * <p>Shows the copies currently on loan, which is the only way a member can
     * find out a book is out before crossing the desk to ask. Open loans are the
     * newest first and capped: the list answers "is it out", not "who has had
     * it", and naming current borrowers to another member would not be right.
     *
     * <p>The viewer's own membership and any request they already have for this
     * title are resolved here rather than in the view, because "can this person
     * ask for it" is a question about the reader as well as the shelf: an
     * account with no library card has nothing to hold a book against.
     */
    @GetMapping("/books/{id}")
    public String book(@PathVariable String id,
                       @AuthenticationPrincipal AppUserPrincipal principal,
                       Model model) {
        Book book = bookRepository.findById(id)
                .orElseThrow(() -> new LibraryNotFoundException("That book is not in the catalogue."));
        if (!book.isActive()) {
            throw new LibraryNotFoundException("That book has been retired from the catalogue.");
        }

        Member member = memberRepository.findByUserId(principal.id()).orElse(null);
        // Only the live request is loaded: a member who was turned down can ask
        // again, and the page must offer the button rather than a dead end.
        BookRequest live = member == null ? null
                : requestRepository.findLiveForMemberAndBook(
                        member.getId(), id, RequestStatus.PENDING).stream().findFirst().orElse(null);

        model.addAttribute("book", book);
        model.addAttribute("out", issueRepository.findByBookIdAndStatusOrderByIssueDateDesc(
                id, IssueStatus.ISSUED));
        // Whether a copy can actually be handed over, which is a fact about the
        // shelf rather than about the reader's role: staff borrow from the same
        // shelf as everyone else. A retired title is never borrowable, however
        // many copies its row still claims.
        model.addAttribute("canBorrow", book.isActive() && book.isAvailable());
        model.addAttribute("viewerMember", member);
        model.addAttribute("myRequest", live);
        // How many others are queued for this title. A count, never a list of
        // names: a member is owed the fact that there is competition for the
        // last copy, not who the other readers are.
        model.addAttribute("waitingCount", requestRepository.countByBookIdAndStatus(
                id, RequestStatus.PENDING));
        model.addAttribute("today", LocalDate.now());
        return "book";
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** Raised when a catalogue entry has been retired or never existed. */
    static class LibraryNotFoundException extends RuntimeException {
        LibraryNotFoundException(String message) {
            super(message);
        }
    }
}
