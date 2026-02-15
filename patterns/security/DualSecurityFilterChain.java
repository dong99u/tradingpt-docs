package com.example.tradingpt.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;

import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.List;

/**
 * Dual SecurityFilterChain Architecture
 *
 * Demonstrates:
 * 1. Two separate SecurityFilterChain beans with @Order for isolation
 * 2. Role-based AuthenticationProvider - rejects wrong roles at auth level
 * 3. Independent session concurrency per chain (Admin: 5, User: 3)
 * 4. CSRF configuration with custom token repository
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;

    // ========================
    // Authentication Providers
    // ========================

    /**
     * User-only provider: Rejects ADMIN/TRAINER at authentication level.
     * This prevents privilege escalation through the user login endpoint.
     */
    @Bean(name = "userAuthProvider")
    public AuthenticationProvider userAuthProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(username -> {
            UserDetails user = userDetailsService.loadUserByUsername(username);
            for (GrantedAuthority auth : user.getAuthorities()) {
                String role = auth.getAuthority();
                if ("ROLE_ADMIN".equals(role) || "ROLE_TRAINER".equals(role)) {
                    throw new UsernameNotFoundException("User-only login endpoint");
                }
            }
            return user;
        });
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    /**
     * Admin-only provider: Rejects non-admin/trainer at authentication level.
     */
    @Bean(name = "adminAuthProvider")
    public AuthenticationProvider adminAuthProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(username -> {
            UserDetails user = userDetailsService.loadUserByUsername(username);
            if (!hasAdminOrTrainer(user.getAuthorities())) {
                throw new UsernameNotFoundException("Admin-only login endpoint");
            }
            return user;
        });
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    private boolean hasAdminOrTrainer(Collection<? extends GrantedAuthority> auths) {
        for (GrantedAuthority a : auths) {
            String role = a.getAuthority();
            if ("ROLE_ADMIN".equals(role) || "ROLE_TRAINER".equals(role)) return true;
        }
        return false;
    }

    // ========================
    // Security Filter Chains
    // ========================

    /**
     * Admin SecurityFilterChain - @Order(0) takes priority.
     * Matches: /api/v1/admin/**
     */
    @Bean
    @Order(0)
    public SecurityFilterChain adminSecurityFilterChain(
        HttpSecurity http,
        SessionRegistry sessionRegistry,
        HeaderAndCookieCsrfTokenRepository csrfTokenRepository
    ) throws Exception {

        var requestHandler = new CsrfTokenRequestAttributeHandler();
        var adminMatcher = new RegexRequestMatcher("^/api/v1/admin(?:/.*)?$", null);

        http.securityMatcher(adminMatcher)
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/v1/admin/login")
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(requestHandler)
            )
            .sessionManagement(sm -> sm
                .sessionCreationPolicy(SessionCreationPolicy.NEVER)
                .sessionFixation(sf -> sf.migrateSession())
                .sessionConcurrency(sc -> sc.maximumSessions(5).sessionRegistry(sessionRegistry))
            )
            // endpoint authorization rules omitted for security
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable);

        return http.build();
    }

    /**
     * User SecurityFilterChain - @Order(1) handles remaining requests.
     * Matches: /api/v1/** (except admin), /oauth2/**, /login/oauth2/**
     */
    @Bean
    @Order(1)
    public SecurityFilterChain userSecurityFilterChain(
        HttpSecurity http,
        SessionRegistry sessionRegistry,
        HeaderAndCookieCsrfTokenRepository csrfTokenRepository
    ) throws Exception {

        var requestHandler = new CsrfTokenRequestAttributeHandler();

        http.cors(Customizer.withDefaults())
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/v1/auth/**", "/oauth2/**", "/login/**")
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(requestHandler)
            )
            .sessionManagement(sm -> sm
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(sf -> sf.migrateSession())
                .sessionConcurrency(sc -> sc.maximumSessions(3).sessionRegistry(sessionRegistry))
            )
            // endpoint authorization rules omitted for security
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable);

        return http.build();
    }
}
