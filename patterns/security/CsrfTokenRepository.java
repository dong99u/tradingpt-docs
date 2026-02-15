package com.example.tradingpt.global.security.csrf;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Decorator Pattern: Cookie + Header CSRF Token Repository
 *
 * Wraps Spring's CookieCsrfTokenRepository to deliver CSRF tokens via
 * both cookie AND response header simultaneously.
 *
 * Why both delivery methods?
 * - Cookie: Persistent across page reloads, standard browser behavior
 * - Header: Immediately available to SPA (React/Vue) without cookie parsing
 *
 * Flow:
 * 1. Server generates token → Set-Cookie + Response Header
 * 2. Client reads header (SPA) or cookie (traditional)
 * 3. Client sends back via X-CSRF-TOKEN request header
 */
public class HeaderAndCookieCsrfTokenRepository implements CsrfTokenRepository {

    private static final String DEFAULT_HEADER_NAME = "XSRF-TOKEN";

    private final CookieCsrfTokenRepository delegate;  // Decorator Pattern
    private String headerName = DEFAULT_HEADER_NAME;

    public HeaderAndCookieCsrfTokenRepository() {
        this.delegate = CookieCsrfTokenRepository.withHttpOnlyFalse();
        this.delegate.setCookiePath("/");
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return delegate.generateToken(request);
    }

    /**
     * Save token to both cookie (via delegate) and response header.
     * The header enables SPAs to read the token immediately.
     */
    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request,
                          HttpServletResponse response) {
        delegate.saveToken(token, request, response);

        if (token == null) {
            response.setHeader(headerName, "");
            return;
        }

        response.setHeader(headerName, token.getToken());
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        return delegate.loadToken(request);
    }

    // Configuration delegation methods
    public void setHeaderName(String headerName) { this.headerName = headerName; }
    public void setCookieDomain(String domain) { delegate.setCookieDomain(domain); }
    public void setCookieHttpOnly(boolean httpOnly) { delegate.setCookieHttpOnly(httpOnly); }
    public void setCookiePath(String path) { delegate.setCookiePath(path); }
}
