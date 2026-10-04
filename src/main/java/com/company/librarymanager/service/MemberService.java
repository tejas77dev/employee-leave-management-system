package com.company.librarymanager.service;

import com.company.librarymanager.domain.AuditAction;
import com.company.librarymanager.domain.IssueStatus;
import com.company.librarymanager.domain.Member;
import com.company.librarymanager.domain.Role;
import com.company.librarymanager.domain.User;
import com.company.librarymanager.repository.BookIssueRepository;
import com.company.librarymanager.repository.MemberRepository;
import com.company.librarymanager.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Member records and their borrowing limits.
 *
 * <p>A member may exist without a login, so nothing here requires a
 * {@link User}. Where a login is supplied, the account is created in the same
 * transaction as the card, so a member cannot end up half-registered.
 */
@Service
public class MemberService {

    private static final String PREFIX = "LIB-";
    private static final int CARD_DIGITS = 4;

    public static final int DEFAULT_MAX_BOOKS = 3;
    public static final int MAX_MAX_BOOKS = 50;

    private final MemberRepository memberRepository;
    private final UserRepository userRepository;
    private final BookIssueRepository issueRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public MemberService(MemberRepository memberRepository,
                         UserRepository userRepository,
                         BookIssueRepository issueRepository,
                         PasswordEncoder passwordEncoder,
                         AuditService auditService) {
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.issueRepository = issueRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    /**
     * Allocates the next card number.
     *
     * <p>Zero-padded to a fixed width, because the highest card number is found
     * with {@code max()}, which on a string column compares lexicographically.
     * Without the padding, {@code LIB-9} would sort above {@code LIB-10} and the
     * numbering would stall at nine. The unique index on {@code member_id} is
     * the real guard against a duplicate; this only keeps the sequence tidy.
     */
    public String nextMemberId() {
        String highest = memberRepository.findHighestMemberId();
        int next = 1;
        if (highest != null && highest.startsWith(PREFIX)) {
            try {
                next = Integer.parseInt(highest.substring(PREFIX.length())) + 1;
            } catch (NumberFormatException ex) {
                // A card number outside the generated series, so start again
                // rather than refusing to register anyone.
                next = 1;
            }
        }
        return PREFIX + String.format("%0" + CARD_DIGITS + "d", next);
    }

    /**
     * Creates a member, and gives them a login when an email is supplied.
     *
     * <p>Registration and account creation are one action on purpose: a member
     * with no login cannot sign in to borrow anything, so requiring the email
     * keeps the card and the account together. A password is only demanded when
     * an account is actually created.
     */
    @Transactional
    public Member createMember(String name, String email, String password, String memberId,
                               String phone, String department, String address,
                               int maxBooksAllowed, User actor) {
        String cardNumber = (memberId == null || memberId.isBlank()) ? nextMemberId() : memberId.trim();
        if (memberRepository.findByMemberIdIgnoreCase(cardNumber).isPresent()) {
            throw new CatalogService.FieldErrorException("memberId", "That card number is already in use");
        }

        User user = null;
        String suppliedEmail = blankToNull(email);
        if (suppliedEmail != null) {
            String normalised = suppliedEmail.toLowerCase();
            if (!normalised.contains("@")) {
                throw new CatalogService.FieldErrorException("email", "Enter a valid email address");
            }
            if (password == null || password.length() < 8) {
                throw new CatalogService.FieldErrorException("password", "Use at least 8 characters");
            }
            if (userRepository.existsByEmailIgnoreCase(normalised)) {
                throw new CatalogService.FieldErrorException("email", "That email is already in use");
            }
            user = createUser(name, suppliedEmail, normalised, password, Role.MEMBER);
        }

        Member member = new Member();
        member.setMemberId(cardNumber);
        member.setPhone(blankToNull(phone));
        member.setDepartment(blankToNull(department));
        member.setAddress(blankToNull(address));
        member.setMaxBooksAllowed(clampLimit(maxBooksAllowed));
        member.setActive(true);
        member.setUser(user);

        try {
            member = memberRepository.saveAndFlush(member);
        } catch (DataIntegrityViolationException ex) {
            throw new CatalogService.FieldErrorException("memberId", "That card number is already in use");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("memberId", member.getMemberId());
        metadata.put("maxBooksAllowed", member.getMaxBooksAllowed());
        metadata.put("hasLogin", user != null);
        auditService.record(actor, AuditAction.MEMBER_CREATED, "Member", member.getId(),
                "Registered %s (%s)".formatted(displayName(member), member.getMemberId()), metadata);
        return member;
    }

    /**
     * Updates a member's details.
     *
     * <p>The borrowing limit can never be set below the loans already out:
     * lowering it would leave the member over their limit with no way to borrow
     * again until they returned something, which reads as a bug rather than a
     * consequence.
     */
    @Transactional
    public void updateMember(String memberId, String phone, String department, String address,
                             int maxBooksAllowed, boolean active, User actor) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new LibraryException("That member no longer exists."));

        long onLoan = issueRepository.countByMemberIdAndStatus(memberId, IssueStatus.ISSUED);
        int limit = clampLimit(maxBooksAllowed);
        if (limit < onLoan) {
            throw new LibraryException("%s currently has %d %s out. Set the limit to at least that."
                    .formatted(displayName(member), onLoan, onLoan == 1 ? "book" : "books"));
        }

        List<String> changes = new ArrayList<>();
        if (!Objects.equals(blankToNull(phone), member.getPhone())) {
            member.setPhone(blankToNull(phone));
        }
        if (!Objects.equals(blankToNull(department), member.getDepartment())) {
            member.setDepartment(blankToNull(department));
        }
        member.setAddress(blankToNull(address));
        if (limit != member.getMaxBooksAllowed()) {
            changes.add("limit %d -> %d".formatted(member.getMaxBooksAllowed(), limit));
            member.setMaxBooksAllowed(limit);
        }
        if (active != member.isActive()) {
            changes.add(active ? "reactivated" : "deactivated");
            member.setActive(active);
        }
        if (changes.isEmpty()) {
            return;
        }
        memberRepository.save(member);
        auditService.record(actor, AuditAction.MEMBER_UPDATED, "Member", member.getId(),
                "Updated %s".formatted(displayName(member)), Map.of("changes", changes));
    }

    /** How many books the member has out right now. */
    public long currentLoanCount(String memberId) {
        return issueRepository.countByMemberIdAndStatus(memberId, IssueStatus.ISSUED);
    }

    /** Resolves the borrowed-to name, preferring the linked login. */
    public static String displayName(Member member) {
        if (member.getUser() != null) {
            return member.getUser().getName();
        }
        if (member.getDepartment() != null && !member.getDepartment().isBlank()) {
            return member.getDepartment() + " member";
        }
        return member.getMemberId();
    }

    private User createUser(String name, String email, String normalisedEmail, String password, Role role) {
        User user = new User();
        user.setName(name == null || name.isBlank() ? email : name.trim());
        user.setEmail(normalisedEmail);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(role);
        user.setActive(true);
        return userRepository.saveAndFlush(user);
    }

    private int clampLimit(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, MAX_MAX_BOOKS);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
