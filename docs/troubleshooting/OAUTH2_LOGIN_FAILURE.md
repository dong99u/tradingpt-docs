# OAuth2 카카오 소셜 로그인 실패 문제 해결

> **Version**: 1.0.0
> **Last Updated**: 2025-11-25
> **Author**: TradingPT Development Team

---

## 📌 기술 키워드 (Technical Keywords)

| 카테고리 | 키워드 |
|---------|--------|
| **문제 유형** | `OAuth2 Authentication`, `Cookie Security`, `SecurityFilterChain`, `404 Error`, `AUTH_401_12` |
| **프레임워크** | `Spring Boot 3.5.5`, `Spring Security 6.x`, `OAuth2 Client`, `Spring Session` |
| **보안** | `HTTPS`, `Secure Cookie`, `X-Forwarded-Proto`, `ALB SSL Termination`, `CSRF` |
| **인프라** | `AWS ALB`, `EC2`, `CloudWatch`, `HTTPS Termination` |
| **패턴** | `SecurityFilterChain`, `OrRequestMatcher`, `Authorization Code Flow`, `Cookie Repository` |
| **진단 도구** | `CloudWatch Logs`, `Browser DevTools`, `Network Inspector`, `Cookie Inspector` |

---

> **작성일**: 2025년 11월
> **프로젝트**: TradingPT API
> **도메인**: Spring Security OAuth2 / Cookie 보안
> **심각도**: Critical (로그인 기능 완전 불가)

## 📋 목차

1. [문제 발견 배경](#1-문제-발견-배경)
2. [문제 분석](#2-문제-분석)
   - [Issue 1: SecurityFilterChain 매칭 실패 (404)](#issue-1-securityfilterchain-매칭-실패-404-에러)
   - [Issue 2: OAuth2 쿠키 Secure 속성 문제 (AUTH_401_12)](#issue-2-oauth2-쿠키-secure-속성-문제-auth_401_12-에러)
3. [영향도 분석](#3-영향도-분석)
4. [원인 분석](#4-원인-분석)
5. [해결 방안 탐색](#5-해결-방안-탐색)
6. [최종 해결책](#6-최종-해결책)
7. [성과 및 개선 효과](#7-성과-및-개선-효과)

---

## 1. 문제 발견 배경

### 발견 경위
- **언제**: Dev 환경 배포 후 카카오 소셜 로그인 테스트 중
- **어떻게**: 직접 테스트 - `https://dev.example.com/oauth2/authorization/kakao` 접근
- **증상**:
  - 1차: 404 에러 (정적 리소스를 찾을 수 없음)
  - 2차: 카카오 로그인 화면은 표시되나, 로그인 완료 후 `AUTH_401_12` 에러

### 환경 정보
- **시스템**: Dev 환경 (`https://dev.example.com`)
- **기술 스택**: Spring Boot 3.5.5, Spring Security 6.x, OAuth2 Client
- **인프라**: AWS ALB → EC2 (HTTPS 종료는 ALB에서 수행)

---

## 2. 문제 분석

이 이슈는 **두 개의 연속적인 문제**로 구성되어 있습니다.

---

### Issue 1: SecurityFilterChain 매칭 실패 (404 에러)

#### 재현 시나리오
```
1. 브라우저에서 https://dev.example.com/oauth2/authorization/kakao 접근
2. 404 에러 발생: "No static resource oauth2/authorization/kakao"
3. 카카오 로그인 화면이 표시되지 않음
```

#### 에러 로그
```
org.springframework.web.servlet.resource.NoResourceFoundException:
No static resource oauth2/authorization/kakao
```

#### 요청 흐름 분석

```
┌─────────────────────────────────────────────────────────────────┐
│                    Spring Security Filter Chain                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   Request: /oauth2/authorization/kakao                          │
│                         │                                        │
│                         ▼                                        │
│   ┌─────────────────────────────────────────┐                   │
│   │ SecurityFilterChain #1 (Order 0)        │                   │
│   │ securityMatcher: ^/api/v1/admin(?:/.*)?$│                   │
│   │                                         │                   │
│   │ /oauth2/authorization/kakao 매칭? ❌ NO │                   │
│   └─────────────────────────────────────────┘                   │
│                         │                                        │
│                         ▼                                        │
│   ┌─────────────────────────────────────────┐                   │
│   │ SecurityFilterChain #2 (Order 1)        │                   │
│   │ securityMatcher: ^/api/(?!v1/admin).*$  │  ← 문제!          │
│   │                                         │                   │
│   │ /oauth2/authorization/kakao 매칭? ❌ NO │                   │
│   └─────────────────────────────────────────┘                   │
│                         │                                        │
│                         ▼                                        │
│   ┌─────────────────────────────────────────┐                   │
│   │ 어떤 SecurityFilterChain에도 매칭 안됨   │                   │
│   │ → Spring Security 필터 BYPASS           │                   │
│   └─────────────────────────────────────────┘                   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│                      DispatcherServlet                           │
├─────────────────────────────────────────────────────────────────┤
│   HandlerMapping 검색...                                         │
│   /oauth2/authorization/kakao → 매핑된 컨트롤러 없음             │
│   → 정적 리소스로 처리 시도                                       │
│   → 404 Not Found!                                               │
└─────────────────────────────────────────────────────────────────┘
```

#### 문제가 있는 코드

```java
// ❌ BAD: SecurityConfig.java (수정 전)
@Bean
@Order(1)
public SecurityFilterChain userSecurityFilterChain(HttpSecurity http, ...) throws Exception {

    // 오직 /api/** 패턴만 매칭 - OAuth2 경로 누락!
    var userApiMatcher = new RegexRequestMatcher("^/api/(?!v1/admin(?:/|$)).*$", null);

    http.securityMatcher(userApiMatcher)  // ← /oauth2/** 경로가 매칭되지 않음!
        .oauth2Login(o -> o
            .authorizationEndpoint(ae -> ae.authorizationRequestRepository(cookieAuthRequestRepository))
            // ...
        );

    return http.build();
}
```

#### 핵심 문제
- `securityMatcher`가 `/api/**` 패턴만 매칭
- `/oauth2/**` 및 `/login/oauth2/**` 경로가 SecurityFilterChain에 포함되지 않음
- OAuth2 관련 필터(`OAuth2AuthorizationRequestRedirectFilter` 등)가 동작하지 않음

---

### Issue 2: OAuth2 쿠키 Secure 속성 문제 (AUTH_401_12 에러)

Issue 1을 해결한 후 발생한 두 번째 문제입니다.

#### 재현 시나리오
```
1. https://dev.example.com/oauth2/authorization/kakao 접근
2. 카카오 로그인 페이지로 정상 리다이렉트 ✅
3. 카카오에서 로그인 완료
4. 콜백 URL로 리다이렉트: https://dev.example.com/login/oauth2/code/kakao?code=...&state=...
5. AUTH_401_12 에러 발생: "소셜 로그인 인증에 실패했습니다"
```

#### 에러 응답
```json
{
  "timestamp": "2025-11-25T13:57:15.157783729",
  "code": "AUTH_401_12",
  "message": "소셜 로그인 인증에 실패했습니다. 다시 시도해 주세요."
}
```

#### OAuth2 Authorization Code Flow 분석

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         OAuth2 Authorization Code Flow                        │
└──────────────────────────────────────────────────────────────────────────────┘

[1] 사용자 → /oauth2/authorization/kakao
                    │
                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ OAuth2AuthorizationRequestRedirectFilter                                     │
│                                                                              │
│ 1. OAuth2AuthorizationRequest 생성 (state, redirect_uri 등)                  │
│ 2. HttpCookieOAuth2AuthorizationRequestRepository.saveAuthorizationRequest() │
│    ┌────────────────────────────────────────────────────────────────────┐   │
│    │ Cookie cookie = new Cookie("OAUTH2_AUTH_REQ", encodedRequest);     │   │
│    │ cookie.setSecure(false);  // ← 문제! HTTPS 환경인데 false           │   │
│    │ cookie.setPath("/");                                                │   │
│    │ response.addCookie(cookie);                                         │   │
│    └────────────────────────────────────────────────────────────────────┘   │
│ 3. 카카오 인증 서버로 리다이렉트                                              │
└─────────────────────────────────────────────────────────────────────────────┘
                    │
                    ▼
[2] 카카오 로그인 페이지 (사용자 로그인)
                    │
                    ▼
[3] 카카오 → https://dev.example.com/login/oauth2/code/kakao?code=...&state=...
                    │
                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ OAuth2LoginAuthenticationFilter                                              │
│                                                                              │
│ 1. HttpCookieOAuth2AuthorizationRequestRepository.loadAuthorizationRequest() │
│    ┌────────────────────────────────────────────────────────────────────┐   │
│    │ Cookie cookie = getCookie(request, "OAUTH2_AUTH_REQ");             │   │
│    │                                                                     │   │
│    │ 🚨 cookie == null !                                                 │   │
│    │                                                                     │   │
│    │ 왜? HTTPS 요청인데 쿠키가 secure=false로 설정되어 있어서            │   │
│    │     브라우저가 쿠키를 전송하지 않거나 보안 정책에 의해 차단됨        │   │
│    └────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│ 2. 저장된 AuthorizationRequest가 없음 → state 검증 불가                      │
│ 3. OAuth2AuthenticationException 발생!                                       │
└─────────────────────────────────────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ CustomFailureHandler.onAuthenticationFailure()                               │
│                                                                              │
│ if (exception instanceof OAuth2AuthenticationException) {                    │
│     errorCode = AuthErrorStatus.OAUTH2_AUTHENTICATION_FAILED;  // AUTH_401_12│
│ }                                                                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### 문제가 있는 코드

```java
// ❌ BAD: HttpCookieOAuth2AuthorizationRequestRepository.java (수정 전)
@Override
public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
    // ...
    Cookie cookie = new Cookie(OAUTH2_AUTH_REQUEST_COOKIE_NAME, enc);
    cookie.setPath("/");
    cookie.setHttpOnly(true);
    cookie.setMaxAge(EXPIRE_SECONDS);
    cookie.setSecure(false);  // ← 문제! HTTPS 환경에서도 항상 false
    response.addCookie(cookie);
}
```

#### 핵심 문제
- Dev/Prod 환경은 HTTPS (`https://dev.example.com`)
- OAuth2 Authorization Request 쿠키가 `secure=false`로 설정됨
- HTTPS 환경에서 `secure=false` 쿠키는 보안 정책에 의해 전송되지 않거나 무시될 수 있음
- 결과: 콜백 시 쿠키가 없어서 state 검증 실패 → 인증 실패

---

## 3. 영향도 분석

### 비즈니스 영향
- **사용자 영향**: 모든 카카오/네이버 소셜 로그인 사용자 (신규 가입 및 기존 로그인 모두 불가)
- **기능 영향**: 소셜 로그인 기능 100% 장애
- **데이터 영향**: 없음 (인증 단계에서 실패하므로 데이터 영향 없음)

### 기술적 영향
- **가용성**: 소셜 로그인 기능 완전 불가
- **보안**: 직접적인 보안 위협은 없으나, 쿠키 보안 설정 미흡

### 심각도 평가
| 항목 | 평가 | 근거 |
|------|------|------|
| **비즈니스 영향** | Critical | 소셜 로그인 100% 불가 |
| **발생 빈도** | 항상 | 모든 소셜 로그인 시도에서 발생 |
| **복구 난이도** | 쉬움 | 코드 수정으로 해결 가능 |

---

## 4. 원인 분석

### Issue 1: Root Cause (SecurityFilterChain 매칭 실패)

- **직접적 원인**: `securityMatcher`에 OAuth2 경로가 포함되지 않음
- **근본 원인**: SecurityFilterChain 설계 시 OAuth2 경로를 고려하지 않음

#### 5 Whys 분석
1. **Why 1**: 왜 404 에러가 발생했는가?
   - **Answer**: DispatcherServlet이 `/oauth2/authorization/kakao`를 정적 리소스로 처리하려 했기 때문
2. **Why 2**: 왜 DispatcherServlet까지 요청이 도달했는가?
   - **Answer**: Spring Security 필터를 거치지 않고 bypass 되었기 때문
3. **Why 3**: 왜 Spring Security 필터를 거치지 않았는가?
   - **Answer**: 어떤 SecurityFilterChain에도 매칭되지 않았기 때문
4. **Why 4**: 왜 SecurityFilterChain에 매칭되지 않았는가?
   - **Answer**: `securityMatcher`가 `/api/**` 패턴만 포함하고 OAuth2 경로를 포함하지 않았기 때문
5. **Why 5**: 왜 OAuth2 경로를 포함하지 않았는가?
   - **Answer**: SecurityFilterChain 설계 시 OAuth2 관련 경로를 명시적으로 고려하지 않았음

---

### Issue 2: Root Cause (쿠키 Secure 속성 문제)

- **직접적 원인**: `cookie.setSecure(false)` 하드코딩
- **근본 원인**: 환경별 HTTPS 설정을 고려하지 않은 쿠키 설정

#### 5 Whys 분석
1. **Why 1**: 왜 AUTH_401_12 에러가 발생했는가?
   - **Answer**: OAuth2AuthenticationException이 발생했기 때문
2. **Why 2**: 왜 OAuth2AuthenticationException이 발생했는가?
   - **Answer**: 저장된 AuthorizationRequest를 찾지 못해 state 검증이 실패했기 때문
3. **Why 3**: 왜 AuthorizationRequest를 찾지 못했는가?
   - **Answer**: 콜백 요청 시 `OAUTH2_AUTH_REQ` 쿠키가 전송되지 않았기 때문
4. **Why 4**: 왜 쿠키가 전송되지 않았는가?
   - **Answer**: HTTPS 환경에서 `secure=false` 쿠키가 보안 정책에 의해 차단되었기 때문
5. **Why 5**: 왜 `secure=false`로 설정되었는가?
   - **Answer**: 개발 초기 로컬 환경(HTTP) 기준으로 하드코딩되었고, 환경별 분기 처리가 없었음

---

## 5. 해결 방안 탐색

### Issue 1: SecurityFilterChain 매칭 문제

| 방안 | 설명 | 장점 | 단점 | 복잡도 | 선택 |
|------|------|------|------|--------|------|
| **방안 1** | 별도의 OAuth2 전용 SecurityFilterChain 추가 | ✅ 관심사 분리<br>✅ 독립적 관리 | ❌ 설정 복잡도 증가<br>❌ 중복 설정 가능성 | ⭐⭐⭐ | ❌ |
| **방안 2** | OrRequestMatcher로 기존 체인에 OAuth2 경로 추가 | ✅ 최소 변경<br>✅ 기존 구조 유지 | ⚠️ 단일 체인 복잡도 증가 | ⭐ | ✅ |
| **방안 3** | 모든 요청을 단일 체인으로 통합 | ✅ 단순한 구조 | ❌ Admin/User 분리 불가<br>❌ 대규모 리팩토링 필요 | ⭐⭐⭐⭐ | ❌ |

**선택**: 방안 2 - 최소 변경으로 문제 해결 가능

---

### Issue 2: 쿠키 Secure 속성 문제

| 방안 | 설명 | 장점 | 단점 | 복잡도 | 선택 |
|------|------|------|------|--------|------|
| **방안 1** | `secure=true` 하드코딩 | ✅ 단순함 | ❌ 로컬 HTTP 환경에서 동작 안함 | ⭐ | ❌ |
| **방안 2** | 환경변수로 secure 값 주입 | ✅ 명시적 제어 | ❌ 배포 시 설정 필요<br>❌ 설정 누락 위험 | ⭐⭐ | ❌ |
| **방안 3** | 요청 프로토콜 자동 감지 | ✅ 환경 자동 적응<br>✅ 설정 불필요 | ⚠️ 프록시 환경 고려 필요 | ⭐⭐ | ✅ |

**선택**: 방안 3 - `request.isSecure()` 및 `X-Forwarded-Proto` 헤더로 HTTPS 자동 감지

---

## 6. 최종 해결책

### Issue 1: SecurityFilterChain 수정

#### Before (문제 코드)
```java
// ❌ BAD: /api/** 패턴만 매칭
@Bean
@Order(1)
public SecurityFilterChain userSecurityFilterChain(HttpSecurity http, ...) throws Exception {

    var userApiMatcher = new RegexRequestMatcher("^/api/(?!v1/admin(?:/|$)).*$", null);

    http.securityMatcher(userApiMatcher)  // OAuth2 경로 누락!
        // ...
}
```

#### After (개선 코드)
```java
// ✅ GOOD: OrRequestMatcher로 OAuth2 경로 포함
@Bean
@Order(1)
public SecurityFilterChain userSecurityFilterChain(HttpSecurity http, ...) throws Exception {

    // OAuth2 경로 포함을 위해 OrRequestMatcher 사용
    var userApiMatcher = new OrRequestMatcher(
        new RegexRequestMatcher("^/api/(?!v1/admin(?:/|$)).*$", null),
        new AntPathRequestMatcher("/oauth2/**"),        // OAuth2 인증 시작
        new AntPathRequestMatcher("/login/oauth2/**")   // OAuth2 콜백
    );

    http.securityMatcher(userApiMatcher)
        // ...
}
```

**변경 파일**: `SecurityConfig.java`

---

### Issue 2: 쿠키 Secure 속성 자동 감지

#### Before (문제 코드)
```java
// ❌ BAD: secure=false 하드코딩
@Override
public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
    // ...
    Cookie cookie = new Cookie(OAUTH2_AUTH_REQUEST_COOKIE_NAME, enc);
    cookie.setSecure(false);  // HTTPS 환경에서도 항상 false
    response.addCookie(cookie);
}
```

#### After (개선 코드)
```java
// ✅ GOOD: HTTPS 환경 자동 감지
@Override
public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
    // ...

    // HTTPS 환경 감지 (X-Forwarded-Proto 헤더 또는 request.isSecure())
    // ALB 뒤에서는 X-Forwarded-Proto 헤더로 원본 프로토콜 확인
    boolean isSecure = request.isSecure()
            || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));

    Cookie cookie = new Cookie(OAUTH2_AUTH_REQUEST_COOKIE_NAME, enc);
    cookie.setPath("/");
    cookie.setHttpOnly(true);
    cookie.setMaxAge(EXPIRE_SECONDS);
    cookie.setSecure(isSecure);  // HTTPS 환경에서는 true
    response.addCookie(cookie);
}
```

**변경 파일**: `HttpCookieOAuth2AuthorizationRequestRepository.java`

---

### 주요 설계 결정

**결정 1**: OrRequestMatcher 사용
- **선택**: 기존 SecurityFilterChain에 `OrRequestMatcher`로 경로 추가
- **이유**: 최소 변경으로 문제 해결, 기존 Admin/User 분리 구조 유지
- **트레이드오프**: 단일 체인의 복잡도가 약간 증가

**결정 2**: HTTPS 자동 감지
- **선택**: `request.isSecure()` + `X-Forwarded-Proto` 헤더 조합
- **이유**: 환경별 수동 설정 없이 자동으로 적절한 보안 설정 적용
- **트레이드오프**: 없음 (프록시 환경까지 고려한 안전한 방식)

---

## 7. 성과 및 개선 효과

### 정량적 성과

| 지표 | Before | After | 개선율 |
|------|--------|-------|--------|
| **OAuth2 로그인 성공률** | 0% | 100% | **↑ 완전 복구** |
| **변경 코드 라인** | - | ~15줄 | 최소 변경 |
| **영향받는 파일** | - | 2개 | 최소 영향 |

### 정성적 성과
- ✅ **기능 복구**: 카카오/네이버 소셜 로그인 정상화
- ✅ **보안 강화**: HTTPS 환경에서 쿠키 보안 속성 자동 적용
- ✅ **환경 독립성**: 로컬(HTTP)/Dev/Prod(HTTPS) 환경 모두에서 동작

### 비즈니스 임팩트
- **사용자 경험**: 소셜 로그인으로 간편하게 서비스 이용 가능
- **신규 가입**: 소셜 로그인을 통한 신규 사용자 유입 경로 복구

---

## 📌 핵심 교훈 (Key Takeaways)

### 1. SecurityFilterChain 설계 시 전체 엔드포인트 고려
- **문제**: OAuth2 관련 경로를 `securityMatcher`에서 누락
- **교훈**: Spring Security OAuth2는 `/oauth2/**`, `/login/oauth2/**` 경로를 사용하므로 반드시 포함해야 함
- **적용**: SecurityFilterChain 설계 시 체크리스트에 OAuth2 경로 포함 여부 확인 추가

### 2. 환경별 설정 하드코딩 지양
- **문제**: `cookie.setSecure(false)` 하드코딩으로 HTTPS 환경에서 문제 발생
- **교훈**: 환경에 따라 달라져야 하는 설정은 동적으로 감지하거나 외부 설정으로 분리
- **적용**: 쿠키, CORS 등 환경 의존적인 설정은 자동 감지 또는 설정 파일로 관리

### 3. 프록시/로드밸런서 환경 고려
- **문제**: ALB 뒤에서 `request.isSecure()`만으로는 HTTPS 감지 불가
- **교훈**: 프록시 환경에서는 `X-Forwarded-Proto` 헤더도 함께 확인해야 함
- **적용**: HTTPS 감지 시 `request.isSecure() || "https".equals(X-Forwarded-Proto)` 패턴 사용

### 4. 디버깅 로그의 중요성
- **문제**: CloudWatch에 OAuth2 관련 로그가 전혀 없어 원인 파악 어려움
- **교훈**: 인증 흐름 같은 중요한 부분에는 적절한 로깅 필수
- **적용**: 인증/인가 관련 코드에 INFO/ERROR 레벨 로그 추가

---

## 🔗 관련 문서

- SecurityConfig.java
- HttpCookieOAuth2AuthorizationRequestRepository.java
- CustomOAuth2UserService.java
- CustomFailureHandler.java

---

## 📸 참고 자료

### Spring Security OAuth2 요청 흐름

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Spring Security OAuth2 Login Flow                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  [1] User → /oauth2/authorization/{provider}                                │
│                    │                                                         │
│                    ▼                                                         │
│  ┌─────────────────────────────────────┐                                    │
│  │ OAuth2AuthorizationRequestRedirect  │                                    │
│  │ Filter                              │                                    │
│  │                                     │                                    │
│  │ • AuthorizationRequest 생성         │                                    │
│  │ • 쿠키에 저장 (state 포함)          │  ← secure=true 필요 (HTTPS)        │
│  │ • Provider 인증 서버로 리다이렉트    │                                    │
│  └─────────────────────────────────────┘                                    │
│                    │                                                         │
│                    ▼                                                         │
│  [2] Provider 로그인 페이지 (카카오/네이버)                                   │
│                    │                                                         │
│                    ▼                                                         │
│  [3] Provider → /login/oauth2/code/{provider}?code=...&state=...            │
│                    │                                                         │
│                    ▼                                                         │
│  ┌─────────────────────────────────────┐                                    │
│  │ OAuth2LoginAuthenticationFilter     │                                    │
│  │                                     │                                    │
│  │ • 쿠키에서 AuthorizationRequest 로드│  ← 쿠키가 없으면 실패!             │
│  │ • state 검증                        │                                    │
│  │ • code → access_token 교환          │                                    │
│  │ • CustomOAuth2UserService.loadUser()│                                    │
│  └─────────────────────────────────────┘                                    │
│                    │                                                         │
│          ┌────────┴────────┐                                                │
│          ▼                 ▼                                                │
│  ┌──────────────┐  ┌──────────────┐                                         │
│  │ 성공         │  │ 실패         │                                         │
│  │ CustomSuccess│  │ CustomFailure│                                         │
│  │ Handler      │  │ Handler      │                                         │
│  └──────────────┘  └──────────────┘                                         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 8. 테스트 검증 결과 (Test Verification)

### 8.1 수정 전 상태 (Before)
```
[Issue 1: SecurityFilterChain 매칭 실패]
1. GET /oauth2/authorization/kakao 요청
2. 결과: 404 Not Found - "No static resource oauth2/authorization/kakao"
3. 원인: OAuth2 경로가 SecurityFilterChain에 매칭되지 않음

[Issue 2: OAuth2 쿠키 Secure 속성 문제]
1. 카카오 로그인 화면으로 정상 리다이렉트
2. 카카오 로그인 완료 후 콜백 요청
3. 결과: AUTH_401_12 에러 - "소셜 로그인 인증에 실패했습니다"
4. 원인: secure=false 쿠키가 HTTPS 환경에서 전송되지 않음
```

### 8.2 수정 후 상태 (After)
```
[Issue 1 해결]
1. GET /oauth2/authorization/kakao 요청
2. 결과: 302 Redirect → 카카오 로그인 페이지
3. SecurityFilterChain에 OrRequestMatcher로 OAuth2 경로 포함

[Issue 2 해결]
1. 카카오 로그인 화면으로 정상 리다이렉트
2. 카카오 로그인 완료 후 콜백 요청
3. 결과: 200 OK - 로그인 성공, 세션 생성
4. 쿠키 secure 속성이 HTTPS 환경에서 자동으로 true 설정
```

### 8.3 테스트 커버리지
| 테스트 유형 | 테스트 케이스 | 결과 | 비고 |
|------------|--------------|------|------|
| 통합 테스트 | OAuth2 인증 시작 (카카오) | ✅ Pass | 302 → 카카오 로그인 |
| 통합 테스트 | OAuth2 인증 시작 (네이버) | ✅ Pass | 302 → 네이버 로그인 |
| 통합 테스트 | OAuth2 콜백 처리 | ✅ Pass | 세션 생성 확인 |
| E2E 테스트 | 전체 소셜 로그인 플로우 | ✅ Pass | Dev 환경 검증 |
| 환경 테스트 | Local (HTTP) 환경 | ✅ Pass | secure=false |
| 환경 테스트 | Dev/Prod (HTTPS) 환경 | ✅ Pass | secure=true |
| 보안 테스트 | Cookie 속성 검증 | ✅ Pass | HttpOnly, Secure, Path |
| 회귀 테스트 | 기존 API 인증 영향 없음 | ✅ Pass | - |

### 8.4 검증 결과
```
[Dev 환경 테스트 결과]
- OAuth2 로그인 성공률: 0% → 100% (완전 복구)
- 응답 시간: < 500ms (카카오 인증 제외)
- 세션 생성: 정상
- 쿠키 속성: secure=true, httpOnly=true, path=/

[브라우저 DevTools 검증]
- Set-Cookie 헤더: OAUTH2_AUTH_REQ=...; Secure; HttpOnly; Path=/
- 콜백 요청 시 쿠키 전송: 확인됨
```

---

## 9. 면접 Q&A (Interview Questions)

### Q1. OAuth2 소셜 로그인에서 발생한 문제의 근본 원인은 무엇이었나요?
**A**: 두 가지 연속적인 문제가 있었습니다. 첫 번째는 SecurityFilterChain의 `securityMatcher`가 `/api/**` 패턴만 포함하여 OAuth2 관련 경로(`/oauth2/**`, `/login/oauth2/**`)가 매칭되지 않아 Spring Security 필터를 bypass하고 404 에러가 발생했습니다. 두 번째는 OAuth2 Authorization Request를 저장하는 쿠키의 `secure` 속성이 하드코딩된 `false`로 설정되어 HTTPS 환경(Dev/Prod)에서 쿠키가 전송되지 않아 state 검증이 실패했습니다.

**💡 포인트**:
- SecurityFilterChain의 securityMatcher 동작 원리 이해
- OAuth2 Authorization Code Flow의 전체 흐름 설명
- HTTPS 환경에서 쿠키의 Secure 속성의 중요성

---

### Q2. 문제를 어떻게 진단하고 해결했나요?
**A**: CloudWatch 로그 분석, 브라우저 DevTools의 Network/Application 탭을 활용해 진단했습니다. 첫 번째 문제는 "No static resource" 에러 로그를 통해 요청이 Spring Security를 거치지 않고 DispatcherServlet으로 직접 도달함을 파악했고, `OrRequestMatcher`를 사용해 OAuth2 경로를 SecurityFilterChain에 추가했습니다. 두 번째 문제는 콜백 요청에서 쿠키가 전송되지 않는 것을 DevTools로 확인하고, `request.isSecure()`와 `X-Forwarded-Proto` 헤더를 조합한 HTTPS 자동 감지 로직을 구현했습니다.

**💡 포인트**:
- 체계적인 디버깅 프로세스 (로그 분석, 네트워크 트레이싱)
- 5 Whys 분석을 통한 근본 원인 파악
- ALB 뒤에서 X-Forwarded-Proto 헤더의 역할

---

### Q3. Spring Security의 다중 SecurityFilterChain은 어떻게 동작하나요?
**A**: Spring Security에서 여러 개의 SecurityFilterChain을 정의하면 `@Order` 어노테이션에 따라 우선순위가 결정됩니다. 각 체인의 `securityMatcher`로 요청 경로를 매칭하며, 첫 번째로 매칭되는 체인이 해당 요청을 처리합니다. 어떤 체인에도 매칭되지 않으면 Spring Security 필터를 거치지 않고 DispatcherServlet으로 바로 전달됩니다. 이 프로젝트에서는 Admin API(`/api/v1/admin/**`)와 User API(`/api/**` + OAuth2 경로)를 분리하여 서로 다른 인증 방식을 적용했습니다.

**💡 포인트**:
- Filter Chain 매칭 순서와 우선순위
- securityMatcher와 authorizeHttpRequests의 차이
- 다중 체인을 사용하는 시나리오 (Admin/User 분리)

---

### Q4. OAuth2 Authorization Code Flow에서 state 파라미터의 역할은 무엇인가요?
**A**: state 파라미터는 CSRF(Cross-Site Request Forgery) 공격을 방지하기 위한 보안 메커니즘입니다. 인증 요청 시 생성된 랜덤 값을 쿠키에 저장하고, OAuth2 Provider가 콜백 시 동일한 값을 반환합니다. 서버는 쿠키에 저장된 값과 콜백으로 받은 값을 비교하여 요청의 정당성을 검증합니다. 이 프로젝트에서는 쿠키가 전송되지 않아 저장된 state 값을 찾지 못해 검증이 실패했습니다.

**💡 포인트**:
- OAuth2 보안 취약점과 대응 방안
- state 파라미터 저장/검증 메커니즘
- HttpCookieOAuth2AuthorizationRequestRepository의 역할

---

### Q5. ALB(Application Load Balancer) 환경에서 HTTPS 감지를 어떻게 처리했나요?
**A**: ALB에서 SSL/TLS 종료(termination)를 수행하면 백엔드 EC2로는 HTTP로 요청이 전달됩니다. 이 경우 `request.isSecure()`만으로는 원본 프로토콜을 알 수 없습니다. ALB는 원본 프로토콜 정보를 `X-Forwarded-Proto` 헤더에 담아 전달하므로, `request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"))` 조합으로 HTTPS 환경을 정확하게 감지했습니다.

**💡 포인트**:
- 프록시/로드밸런서 환경에서의 HTTP 헤더 처리
- X-Forwarded-* 헤더 종류 (Proto, For, Host)
- SSL Termination의 장단점

---

### Q6. 이 문제 해결 경험에서 얻은 교훈은 무엇인가요?
**A**: 네 가지 핵심 교훈이 있습니다. 첫째, SecurityFilterChain 설계 시 OAuth2, Actuator 등 프레임워크가 사용하는 모든 경로를 고려해야 합니다. 둘째, 환경에 따라 달라지는 설정(쿠키 보안, CORS 등)은 하드코딩하지 않고 동적으로 감지하거나 외부 설정으로 분리해야 합니다. 셋째, 프록시 환경에서는 X-Forwarded-* 헤더를 반드시 고려해야 합니다. 넷째, 인증 흐름 같은 중요한 부분에는 충분한 로깅을 추가하여 문제 발생 시 빠른 진단이 가능하도록 해야 합니다.

**💡 포인트**:
- Security 설정의 복잡성과 테스트의 중요성
- 환경별 설정 분리 원칙
- Observability (로깅, 모니터링)의 중요성

---

## 📎 참고 자료 (References)

### 관련 문서
- SecurityConfig.java
- HttpCookieOAuth2AuthorizationRequestRepository.java
- CustomOAuth2UserService.java

### 외부 참조
- [Spring Security OAuth2 Reference](https://docs.spring.io/spring-security/reference/servlet/oauth2/index.html)
- [OAuth 2.0 Authorization Code Flow](https://datatracker.ietf.org/doc/html/rfc6749#section-4.1)
- [AWS ALB HTTP Headers](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/x-forwarded-headers.html)

---

## 📝 변경 이력 (Change Log)

| 버전 | 날짜 | 작성자 | 변경 내용 |
|------|------|--------|----------|
| 1.0.0 | 2025-11-25 | TradingPT Dev Team | 최초 작성 |
| 1.1.0 | 2025-11-26 | TradingPT Dev Team | 테스트 검증 및 면접 Q&A 섹션 추가 |
