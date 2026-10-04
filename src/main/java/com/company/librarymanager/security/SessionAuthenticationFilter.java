package com.company.librarymanager.security;

import com.company.librarymanager.domain.User;
import com.company.librarymanager.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rebuilds the security context from the database session on every request.
 *
 * <p>Spring Security's own session handling would trust whatever it wrote
 * into the HTTP session, so a deactivated account would keep working until
 * its cookie expired. Resolving the token against the database each time
 * means deactivating someone logs them out at once.
 */
@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    private final SessionService sessionService;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public SessionAuthenticationFilter(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession httpSession = request.getSession(false);
        if (httpSession != null) {
            Object token = httpSession.getAttribute(SessionService.SESSION_ATTRIBUTE);
            if (token instanceof String value) {
                sessionService.resolve(value).ifPresentOrElse(principal -> {
                    var authentication = new UsernamePasswordAuthenticationToken(
                            principal, null, principal.getAuthorities());
                    SecurityContext context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(authentication);
                    SecurityContextHolder.setContext(context);
                }, () -> logout(httpSession));
            }
        }
        chain.doFilter(request, response);
    }

    private void logout(HttpSession httpSession) {
        try {
            httpSession.invalidate();
        } catch (IllegalStateException ignored) {
            // Already invalidated by another request; nothing to do.
        }
    }
}
