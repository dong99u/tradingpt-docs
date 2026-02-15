# Security Architecture Patterns

## Overview

This project implements a comprehensive Spring Security architecture with three key patterns:

1. **Dual Authentication System** - Separate filter chains for users and admins
2. **CSRF Decorator Pattern** - Cookie + Header dual token delivery
3. **OAuth2 Social Login** - Kakao/Naver with auto-registration

## 1. Dual SecurityFilterChain Architecture

Two separate `SecurityFilterChain` beans with `@Order` annotations prevent privilege escalation by ensuring users and admins authenticate through completely isolated paths.

```
Request
├── /api/v1/admin/** → @Order(0) Admin Chain
│   └── AdminAuthProvider (ADMIN/TRAINER roles only)
│       └── Rejects CUSTOMER at authentication level
│
└── /api/v1/** → @Order(1) User Chain
    ├── UserAuthProvider (CUSTOMER role only)
    │   └── Rejects ADMIN/TRAINER at authentication level
    └── OAuth2 (Kakao/Naver social login)
```

**Why two chains?**
- Role-based rejection happens at the **AuthenticationProvider** level, before authorization
- Each chain has independent session concurrency limits (Admin: 5, User: 3)
- Each chain can have different CSRF exclusion rules
- Admin endpoints are completely isolated from user authentication flow

## 2. CSRF Decorator Pattern

`HeaderAndCookieCsrfTokenRepository` wraps Spring's `CookieCsrfTokenRepository` to deliver CSRF tokens via **both** cookie and response header simultaneously.

```
Client Request → Server generates CSRF token
  ├── Set-Cookie: XSRF-TOKEN=abc123  (for cookie storage)
  └── Header: XSRF-TOKEN: abc123     (for immediate SPA access)

Client sends back → Header: X-CSRF-TOKEN: abc123
```

**Why both?**
- Cookie: Persistent across page reloads
- Header: Immediately available to SPA without reading cookies
- SPA (React/Vue) can read the response header directly

## 3. OAuth2 Social Login Flow

```
1. User clicks "Login with Kakao"
2. Redirect → GET /oauth2/authorization/kakao
3. Kakao OAuth2 consent screen
4. Callback → CustomOAuth2UserService.loadUser()
   ├── Parse provider response (KakaoResponse / NaverResponse)
   ├── Check existing: findByProviderAndProviderId()
   │   ├── Found → Login with existing account
   │   └── Not found → Auto-create Customer entity
   └── Return CustomOAuth2User (Spring Security principal)
5. Success handler → Create session, redirect to frontend
```

## Key Files

| File | Description |
|------|-------------|
| `DualSecurityFilterChain.java` | Two `@Order` SecurityFilterChain beans with isolated auth |
| `CsrfTokenRepository.java` | Decorator Pattern: Cookie + Header CSRF token delivery |
| `OAuth2UserService.java` | OAuth2 user mapping with auto-registration |
