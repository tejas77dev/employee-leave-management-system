package com.company.librarymanager.config;

import com.company.librarymanager.repository.UserRepository;
import com.company.librarymanager.security.AppUserPrincipal;
import com.company.librarymanager.security.SessionAuthenticationFilter;
import com.company.librarymanager.security.SessionService;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Access rules.
 *
 * <p>Three areas, and the URL is the only thing that decides which:
 *
 * <ul>
 *   <li>{@code /catalog}, {@code /dashboard}, {@code /my-loans} — any signed-in
 *       user, member or staff.
 *   <li>{@code /desk/**} — the issue desk: lending, returns, fines, and editing
 *       the catalogue and the member register. Open to librarians and admins.
 *   <li>{@code /admin/**} — accounts, categories and the activity log. Admin
 *       only, because these are the operations that change who can do what.
 * </ul>
 *
 * <p>Because the split is expressed entirely in this chain, controllers under
 * {@code /desk} and {@code /admin} do not repeat a role check per method.
 */
@Configuration
public class SecurityConfig {

    private final SessionService sessionService;

    public SecurityConfig(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepository) {
        return username -> userRepository.findByEmailIgnoreCase(username.trim())
                .map(SessionService::toPrincipal)
                .orElseThrow(() -> new UsernameNotFoundException("No such account"));
    }

    @Bean
    public AuthenticationProvider authenticationProvider(UserDetailsService userDetailsService,
                                                         PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // An unknown account and a wrong password must fail identically, so the
        // login form cannot be used to discover which emails exist.
        provider.setHideUserNotFoundExceptions(true);
        return provider;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           SecurityContextRepository contextRepository,
                                           SessionAuthenticationFilter sessionFilter) throws Exception {
        return http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/login"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/css/**", "/webjars/**", "/error").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/desk/**").hasAnyRole("ADMIN", "LIBRARIAN")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .successHandler((request, response, authentication) -> {
                            // Open a database session so the login survives a
                            // restart and deactivation takes effect at once.
                            if (authentication.getPrincipal() instanceof AppUserPrincipal principal) {
                                String token = sessionService.createSession(principal.id());
                                HttpSession session = request.getSession(true);
                                session.setAttribute(SessionService.SESSION_ATTRIBUTE, token);
                            }
                            response.sendRedirect(request.getContextPath() + "/dashboard");
                        })
                        .failureHandler((request, response, exception) -> {
                            String message = URLEncoder.encode("Incorrect email or password.",
                                    StandardCharsets.UTF_8);
                            response.sendRedirect(request.getContextPath() + "/login?error&message=" + message);
                        })
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .addLogoutHandler((request, response, authentication) -> {
                            HttpSession session = request.getSession(false);
                            if (session != null
                                    && session.getAttribute(SessionService.SESSION_ATTRIBUTE) instanceof String token) {
                                sessionService.destroySession(token);
                            }
                        }))
                .exceptionHandling(handling -> handling
                        .accessDeniedHandler((request, response, exception) ->
                                response.sendRedirect(request.getContextPath() + "/dashboard")))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .maximumSessions(5))
                .addFilterBefore(sessionFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
