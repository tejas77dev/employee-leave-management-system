package com.company.leavemanager.web;

import com.company.leavemanager.security.AppUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Model attributes every view needs.
 *
 * <p>Thymeleaf's {@code principal} resolves to Spring Security's
 * {@link Authentication}, not to the application principal, so templates use
 * {@code me} to reach the typed identity. Thymeleaf 3.1 also dropped the
 * {@code #httpServletRequest} expression object, so the request path is passed
 * in explicitly for the sidebar's active-nav highlighting.
 */
@ControllerAdvice
public class ViewModelAdvice {

    /** Model attribute name for the current request path. */
    public static final String CURRENT_PATH = "currentPath";

    /** Model attribute name for the signed-in identity. */
    public static final String ME = "me";

    /** Model attribute name for the display helper. */
    public static final String FMT = "fmt";

    @ModelAttribute(CURRENT_PATH)
    public String currentPath(jakarta.servlet.http.HttpServletRequest request) {
        return request.getRequestURI();
    }

    /**
     * Exposes the formatting helper to every view as {@code ${fmt}}.
     *
     * <p>Spring Boot does not publish context beans as Thymeleaf expression
     * objects, so a model attribute is the way to make the helper reachable.
     */
    @ModelAttribute(FMT)
    public Formats fmt() {
        return new Formats();
    }

    @ModelAttribute(ME)
    public AppUserPrincipal me(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserPrincipal user)) {
            return null;
        }
        return user;
    }
}
