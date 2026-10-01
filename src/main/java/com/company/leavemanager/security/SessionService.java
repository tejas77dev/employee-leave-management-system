package com.company.leavemanager.security;

import com.company.leavemanager.domain.Session;
import com.company.leavemanager.domain.User;
import com.company.leavemanager.repository.SessionRepository;
import com.company.leavemanager.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Server-side session records.
 *
 * <p>The session id is stored in the HTTP session and resolved back to a user
 * on each request, so deactivating an account takes effect immediately rather
 * than when a cookie happens to expire.
 */
@Service
public class SessionService {

    public static final String SESSION_ATTRIBUTE = "APP_SESSION_TOKEN";
    private static final Duration SESSION_DURATION = Duration.ofDays(7);

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final SecureRandom random = new SecureRandom();

    public SessionService(SessionRepository sessionRepository, UserRepository userRepository) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    /**
     * Creates a session row and returns its opaque token.
     *
     * <p>Takes an id rather than a detached {@code User} so the row is written
     * against a managed reference instead of a detached entity.
     */
    @Transactional
    public String createSession(String userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);

        Session session = new Session();
        session.setToken(token);
        session.setUser(userRepository.getReferenceById(userId));
        session.setExpiresAt(Instant.now().plus(SESSION_DURATION));
        sessionRepository.save(session);
        return token;
    }

    @Transactional
    public void destroySession(String token) {
        if (token != null && !token.isBlank()) {
            sessionRepository.deleteByToken(token);
        }
    }

    /**
     * Resolves a token, treating an expired row or a deactivated account as no
     * session at all.
     */
    @Transactional(readOnly = true)
    public Optional<AppUserPrincipal> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return sessionRepository.findByToken(token)
                .filter(session -> !session.isExpired(Instant.now()))
                .map(Session::getUser)
                .filter(User::isActive)
                .map(SessionService::toSessionPrincipal);
    }

    @Transactional
    public void purgeExpired() {
        sessionRepository.deleteByExpiresAtBefore(Instant.now());
    }

    public static AppUserPrincipal toPrincipal(User user) {
        return new AppUserPrincipal(user.getId(), user.getEmail(), user.getName(), user.getRole(),
                user.isActive(), user.getPasswordHash());
    }

    /** Session-cached identity, deliberately stripped of the password hash. */
    public static AppUserPrincipal toSessionPrincipal(User user) {
        return toPrincipal(user).withoutPassword();
    }

    /** The signed-in principal, or null when nobody is signed in. */
    public static AppUserPrincipal current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getPrincipal() instanceof AppUserPrincipal principal ? principal : null;
    }

    public static String currentUserId() {
        AppUserPrincipal principal = current();
        return principal == null ? null : principal.id();
    }
}
