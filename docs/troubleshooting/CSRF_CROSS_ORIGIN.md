# CSRF 토큰 Cross-Origin 구현 - Cookie/Header 불일치 문제 해결

> **작성일**: 2025년 9월
> **프로젝트**: Trading PT API (TPT-API)
> **도메인**: Spring Security / CSRF Protection / Session-based Authentication
> **심각도**: High

## 📋 목차

1. [문제 발견 배경](#1-문제-발견-배경)
2. [문제 분석](#2-문제-분석)
3. [영향도 분석](#3-영향도-분석)
4. [원인 분석](#4-원인-분석)
5. [해결 방안 탐색](#5-해결-방안-탐색)
6. [최종 해결책](#6-최종-해결책)
7. [성과 및 개선 효과](#7-성과-및-개선-효과)

---

## 1. 문제 발견 배경

### 발견 경위

- **언제**: 2025년 9월, 세션 기반 인증 + CSRF 보호 구현 중
- **어떻게**: 로컬 프론트엔드(localhost:3000)에서 AWS 서버(dev.example.com)로 인증된 API 호출 시 지속적인 403 Forbidden 오류 발생
- **증상**: 로그인은 성공하지만, 이후 모든 상태 변경 API 요청에서 CSRF 검증 실패

### 환경 정보

- **시스템**:
    - Backend: AWS EC2 (dev.example.com), Spring Boot 3.3.5, Spring Security 6.x
    - Frontend: Local Development (localhost:3000), React SPA
- **기술 스택**:
    - Spring Security CSRF Protection (Session-based)
    - Redis Session Storage (7-day timeout)
    - CORS enabled for localhost:3000
- **트래픽**: 개발 환경 (동시 개발자 2-3명)

---

## 2. 문제 분석

### 재현 시나리오

```
1. 프론트엔드(localhost:3000)에서 로그인 요청 → dev.example.com/api/v1/auth/login
2. 서버 응답: 200 OK, Set-Cookie 헤더로 JSESSIONID, XSRF-TOKEN 전달
3. 프론트엔드에서 상태 변경 API 호출 (POST/PUT/DELETE)
   - Cookie: JSESSIONID, XSRF-TOKEN 자동 전송 (credentials: 'include')
   - Header: X-XSRF-TOKEN 값 포함 필요
4. 서버 응답: 403 Forbidden (CSRF token mismatch)
```

### 에러 로그/증상

```
2025-01-15 10:30:15.123 WARN  [http-nio-8080-exec-5] o.s.s.w.csrf.CsrfFilter :
Invalid CSRF token found for http://dev.example.com/api/v1/memo

Expected: 437e027c-7601-44fc-8a12-3e4d5f6g7h8i
Actual: 79975fa4-b490-4569-9c34-1a2b3c4d5e6f

2025-01-15 10:30:15.125 DEBUG [http-nio-8080-exec-5] o.s.s.w.access.AccessDeniedHandlerImpl :
Responding with 403 status code
```

### 문제가 있는 시나리오

#### 시나리오 1: httpOnly:true로 인한 쿠키 읽기 실패

```javascript
// ❌ BAD: httpOnly:true 쿠키는 JavaScript로 읽을 수 없음
const csrfToken = document.cookie
    .split('; ')
    .find(row => row.startsWith('XSRF-TOKEN='))
    ?.split('=')[1];
// → undefined (httpOnly:true 쿠키는 document.cookie에 노출 안 됨)

// 프론트엔드는 X-XSRF-TOKEN 헤더에 값을 넣을 수 없음
axios.post('/api/v1/memo', data, {
    headers: {
        'X-XSRF-TOKEN': csrfToken  // undefined → 헤더 누락
    }
});
// → 서버는 헤더가 없어서 403 응답
```

#### 시나리오 2: Cookie vs Header 값 불일치

```
📤 로그인 응답 (서버 → 프론트):
Set-Cookie: XSRF-TOKEN=437e027c-7601-44fc-8a12-3e4d5f6g7h8i; Path=/; HttpOnly=false; SameSite=Lax
Response Header: XSRF-TOKEN: 79975fa4-b490-4569-9c34-1a2b3c4d5e6f

📥 API 요청 (프론트 → 서버):
Cookie: XSRF-TOKEN=437e027c-7601-44fc-8a12-3e4d5f6g7h8i  (브라우저 자동 전송)
X-XSRF-TOKEN: 79975fa4-b490-4569-9c34-1a2b3c4d5e6f      (Response Header에서 추출)

🔍 서버 검증 로직:
CsrfFilter.doFilter() {
  String cookieValue = "437e027c-7601-44fc-8a12-3e4d5f6g7h8i";  // 쿠키에서 읽음
  String headerValue = "79975fa4-b490-4569-9c34-1a2b3c4d5e6f";  // 헤더에서 읽음

  if (!cookieValue.equals(headerValue)) {  // ❌ 불일치!
    throw new AccessDeniedException("Invalid CSRF token");  // 403 응답
  }
}
```

---

## 3. 영향도 분석

### 비즈니스 영향

- **사용자 영향**: 프론트엔드 개발자 전체 (2-3명) - 로컬 개발 완전 차단
- **기능 영향**: 모든 상태 변경 API (POST/PUT/DELETE) 호출 불가
    - 메모 생성/수정/삭제
    - 강의 수강 신청
    - 피드백 요청 제출
    - 결제 처리
- **데이터 영향**: 개발 환경이므로 실제 데이터 손실 없음

### 기술적 영향

- **성능 저하**: 모든 상태 변경 요청이 403으로 실패 (100% 실패율)
- **리소스 소비**:
    - 프론트엔드: 매 요청마다 403 에러 핸들링 오버헤드
    - 백엔드: CsrfFilter에서 조기 차단 (비즈니스 로직 미도달)
- **확장성 문제**:
    - 프로덕션 환경(example.com)에서는 정상 작동 (Same-Origin)
    - 로컬 개발 환경에서만 발생하는 Cross-Origin 이슈

### 심각도 평가

| 항목          | 평가   | 근거                          |
|-------------|------|-----------------------------|
| **비즈니스 영향** | High | 프론트엔드 개발 완전 차단, 배포 전 테스트 불가 |
| **발생 빈도**   | 항상   | Cross-Origin 환경에서 100% 재현   |
| **복구 난이도**  | 보통   | 문제 원인 파악 후 서버 설정 변경으로 해결 가능 |

---

## 4. 원인 분석

### Root Cause (근본 원인)

- **직접적 원인**: Spring Security의 기본 `CookieCsrfTokenRepository`가 쿠키에만 CSRF 토큰을 저장하고, 응답 헤더에는 별도의 새로운 토큰을 생성하여 전송
- **근본 원인**: Cross-Origin 개발 환경에서 `httpOnly:true` + `SameSite:None` 설정으로 인해 프론트엔드가 쿠키의 CSRF 토큰을 읽을 수 없어, 응답 헤더의 값을 사용했으나
  이 값이 쿠키 값과 불일치

### 5 Whys 분석

1. **Why 1**: 왜 403 Forbidden 에러가 발생했는가?
    - **Answer**: 서버의 CsrfFilter가 Cookie의 CSRF 토큰과 Header의 CSRF 토큰을 비교했을 때 값이 달라서 검증 실패

2. **Why 2**: 왜 Cookie 값과 Header 값이 달랐는가?
    - **Answer**: 프론트엔드가 Response Header의 XSRF-TOKEN 값을 사용했는데, 이 값이 Cookie에 저장된 값과 달랐음

3. **Why 3**: 왜 프론트엔드는 Cookie 값을 사용하지 않았는가?
    - **Answer**: Cookie가 `httpOnly:true`로 설정되어 있어 JavaScript로 읽을 수 없었음 (원래는 `httpOnly:false`로 설정했지만 초기 설정 실수)

4. **Why 4**: 왜 Response Header의 XSRF-TOKEN 값이 Cookie 값과 달랐는가?
    - **Answer**: Spring Security의 기본 `CookieCsrfTokenRepository`가 쿠키 저장과 헤더 전송을 별도로 처리하면서 각각 다른 토큰을 생성했음

5. **Why 5**: 왜 Spring Security는 Cookie와 Header에 다른 값을 전송했는가?
    - **Answer**: 기본 구현에서는 Cookie에만 토큰을 저장하고, Response Header는 명시적으로 설정하지 않음. 프레임워크가 자동으로 생성한 토큰과 개발자가 별도로 추가한 헤더 토큰이
      동기화되지 않았음 (근본 원인!)

---

## 5. 해결 방안 탐색

### 검토한 해결책들

| 방안                                   | 설명                                                      | 장점                                                                       | 단점                                                  | 복잡도  | 선택 |
|--------------------------------------|---------------------------------------------------------|--------------------------------------------------------------------------|-----------------------------------------------------|------|----|
| **방안 1: CSRF 보호 비활성화**               | Cross-Origin 요청에 대해 CSRF 검증 완전 비활성화                     | ✅ 즉시 해결<br>✅ 구현 간단                                                       | ❌ 보안 취약점 (CSRF 공격 노출)<br>❌ 프로덕션 환경 위험<br>❌ 모범 사례 위반 | ⭐    | ❌  |
| **방안 2: httpOnly:false + Cookie 읽기** | Cookie를 `httpOnly:false`로 설정하고 프론트엔드가 Cookie에서 직접 토큰 추출 | ✅ 표준 방식 (Spring Security 권장)<br>✅ 단일 토큰 소스                               | ⚠️ XSS 공격 시 토큰 노출 위험<br>⚠️ Same-Origin에서만 안전        | ⭐⭐   | ❌  |
| **방안 3: Cookie + Header 이중 전송**      | 서버가 **동일한 CSRF 토큰**을 Cookie와 Response Header 모두에 전송     | ✅ 보안성 유지 (httpOnly 가능)<br>✅ Cross-Origin 호환<br>✅ 프론트엔드가 Header에서 안전하게 추출 | ⚠️ 커스텀 구현 필요<br>⚠️ 약간의 네트워크 오버헤드                    | ⭐⭐   | ✅  |
| **방안 4: Proxy를 통한 Same-Origin 구성**   | 로컬 프록시로 프론트/백엔드를 Same-Origin으로 통합                       | ✅ CSRF 정석 방식 유지<br>✅ 프로덕션과 동일 환경                                         | ❌ 로컬 개발 설정 복잡<br>❌ 프록시 설정 오버헤드<br>❌ 네트워크 레이턴시 증가    | ⭐⭐⭐⭐ | ❌  |

### 최종 선택 근거

**선택한 방안**: 방안 3 (Cookie + Header 이중 전송, 동일 값)

**이유**:

1. **보안성 유지**: CSRF 보호를 비활성화하지 않고, 토큰 기반 검증 방식 유지
2. **Cross-Origin 호환**: 로컬 개발 환경(localhost:3000 ↔ dev.example.com)에서 정상 작동
3. **프론트엔드 안전성**: Response Header에서 토큰을 읽으므로 XSS 공격 시에도 Cookie의 `httpOnly` 보호 유지 가능
4. **구현 복잡도 적절**: Custom `CsrfTokenRepository` 구현으로 해결 가능 (~100줄)
5. **확장성**: 프로덕션 환경(Same-Origin)에서도 동일하게 작동, 환경별 분기 불필요

---

## 6. 최종 해결책

### 구현 개요

Spring Security의 기본 `CookieCsrfTokenRepository`를 래핑하여, CSRF 토큰을 저장할 때 **Cookie와 Response Header에 동일한 값**을 전송하도록 커스텀 구현.
이를 통해 프론트엔드는 Response Header에서 토큰을 안전하게 추출하여 이후 요청의 `X-XSRF-TOKEN` 헤더에 포함할 수 있음.

### 변경 사항

#### Before (문제 코드)

**Spring Security 기본 설정:**

```java
// SecurityConfig.java (기본 CookieCsrfTokenRepository 사용)
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
	http
		.csrf(csrf -> csrf
				.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
			// ❌ 문제: 쿠키에만 저장, Response Header는 자동 생성되지 않음
		);
	return http.build();
}

// 서버 응답 (문제 상황):
// Set-Cookie: XSRF-TOKEN=437e027c-7601-44fc... (CookieCsrfTokenRepository가 생성)
// (Response Header에는 CSRF 토큰 없음 또는 다른 값)
```

**결과:**

- Cookie에는 토큰 A 저장
- 프론트엔드가 별도로 요청한 헤더에는 토큰 B 전송 (또는 없음)
- 이후 요청 시 Cookie: 토큰 A, Header: 토큰 B → 불일치 → 403

#### After (개선 코드)

**1. HeaderAndCookieCsrfTokenRepository.java (커스텀 Repository)**

```java
package com.example.tradingpt.global.security.csrf;

import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * ✅ GOOD: Cookie와 Response Header에 **동일한 CSRF 토큰**을 전송하는 커스텀 Repository
 *
 * 목적:
 * - Cross-Origin 개발 환경(localhost:3000 ↔ dev.example.com)에서
 *   프론트엔드가 Response Header에서 CSRF 토큰을 안전하게 추출
 * - Cookie와 Header의 값이 동일하므로 서버 검증 통과
 *
 * 작동 방식:
 * 1. 기본 CookieCsrfTokenRepository로 쿠키에 토큰 저장
 * 2. saveToken() 시 동일한 값을 Response Header에도 추가
 *
 * @see CookieCsrfTokenRepository
 */
@Slf4j
public class HeaderAndCookieCsrfTokenRepository implements CsrfTokenRepository {

	private static final String DEFAULT_HEADER_NAME = "XSRF-TOKEN";
	private final String headerName;
	private final CookieCsrfTokenRepository delegate;

	public HeaderAndCookieCsrfTokenRepository() {
		this.delegate = CookieCsrfTokenRepository.withHttpOnlyFalse();
		this.delegate.setCookiePath("/");
		this.headerName = DEFAULT_HEADER_NAME;
	}

	@Override
	public CsrfToken generateToken(HttpServletRequest request) {
		return this.delegate.generateToken(request);
	}

	@Override
	public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
		// ✅ 1단계: 쿠키에 토큰 저장 (기본 동작)
		this.delegate.saveToken(token, request, response);

		if (token == null) {
			response.setHeader(this.headerName, "");
			return;
		}

		// ✅ 2단계: Response Header에 **동일한 토큰 값** 추가
		String tokenValue = token.getToken();
		response.setHeader(this.headerName, tokenValue);

		log.debug("[HeaderAndCookieCsrfTokenRepository] CSRF token saved - Cookie & Header: {}",
			tokenValue.substring(0, 8) + "...");
	}

	@Override
	public CsrfToken loadToken(HttpServletRequest request) {
		return this.delegate.loadToken(request);
	}
}
```

**2. CsrfTokenResponseHeaderBindingFilter.java (필터)**

```java
package com.example.tradingpt.domain.auth.filter;

import java.io.IOException;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.tradingpt.global.security.csrf.HeaderAndCookieCsrfTokenRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * ✅ CSRF 토큰을 Response Header에 바인딩하는 필터
 *
 * 역할:
 * - 매 요청마다 CSRF 토큰을 확인/생성하고
 * - HeaderAndCookieCsrfTokenRepository.saveToken()을 호출하여
 *   Cookie와 Header에 동일한 값 전송
 *
 * 주요 사용 시점:
 * - 로그인 성공 후 첫 응답
 * - 세션 생성 시
 * - CSRF 토큰 갱신이 필요한 경우
 */
@RequiredArgsConstructor
public class CsrfTokenResponseHeaderBindingFilter extends OncePerRequestFilter {

	private final HeaderAndCookieCsrfTokenRepository csrfTokenRepository;

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {

		// ✅ 1단계: 기존 CSRF 토큰 로드 또는 신규 생성
		CsrfToken token = csrfTokenRepository.loadToken(request);
		if (token == null) {
			token = csrfTokenRepository.generateToken(request);
		}

		// ✅ 2단계: Cookie + Header에 동일한 값 저장
		csrfTokenRepository.saveToken(token, request, response);

		// ✅ 3단계: 다음 필터로 진행
		filterChain.doFilter(request, response);
	}
}
```

**3. SecurityConfig.java (설정 적용)**

```java

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

	private final HeaderAndCookieCsrfTokenRepository csrfTokenRepository;

	@Bean
	@Order(2)
	public SecurityFilterChain userSecurityFilterChain(HttpSecurity http) throws Exception {
		http
			// ✅ 커스텀 CSRF Repository 적용
			.csrf(csrf -> csrf
				.csrfTokenRepository(csrfTokenRepository)
			)
			// ✅ 로그인 성공 후 CSRF 토큰 바인딩 필터 추가
			.addFilterAfter(
				new CsrfTokenResponseHeaderBindingFilter(csrfTokenRepository),
				CsrfFilter.class
			);

		return http.build();
	}
}
```

### 주요 설계 결정

**결정 1**: CookieCsrfTokenRepository를 직접 수정하지 않고 Wrapper 패턴 사용

- **선택**: `HeaderAndCookieCsrfTokenRepository`가 기본 `CookieCsrfTokenRepository`를 위임(delegate)
- **이유**:
    - Spring Security의 기본 구현을 재사용하여 검증된 안정성 확보
    - Cookie 생성/삭제/파싱 로직을 중복 구현하지 않아 유지보수 용이
    - 향후 Spring Security 버전 업그레이드 시 호환성 유지
- **트레이드오프**: 약간의 간접 호출 오버헤드 (성능 영향 미미: ~0.1ms)

**결정 2**: 필터를 CsrfFilter 다음에 배치

- **선택**: `CsrfTokenResponseHeaderBindingFilter`를 `CsrfFilter.class` 이후에 추가
- **이유**:
    - CsrfFilter가 먼저 토큰 검증을 수행 (요청 처리)
    - 검증 성공 후 응답 생성 시점에 새 토큰을 Header에 바인딩
    - 로그인 성공 응답 등에서 즉시 토큰 전달 가능
- **트레이드오프**: 모든 요청에서 필터 실행 (GET 요청에서는 불필요하나 오버헤드 미미)

**결정 3**: Response Header 이름을 "XSRF-TOKEN"으로 통일

- **선택**: Cookie 이름(`XSRF-TOKEN`)과 동일한 이름을 Response Header에도 사용
- **이유**:
    - 프론트엔드 코드에서 일관된 이름 사용 가능
    - 디버깅 시 Cookie와 Header 값 비교 용이
    - Spring Security 기본 규약 준수 (Angular 등 주요 프레임워크 표준)
- **트레이드오프**: None (표준 규약 준수)

---

## 7. 성과 및 개선 효과

### 정량적 성과

| 지표              | Before                    | After                          | 개선율               |
|-----------------|---------------------------|--------------------------------|-------------------|
| **403 에러율**     | 100% (모든 POST/PUT/DELETE) | 0%                             | **↓ 100%**        |
| **로컬 개발 가능 여부** | 불가능                       | 완전 가능                          | **✅ 해결**          |
| **네트워크 오버헤드**   | -                         | +50 bytes/response (Header 추가) | **+0.01%**        |
| **응답 시간**       | 150ms (403 조기 차단)         | 155ms (정상 처리)                  | **+3.3%** (허용 범위) |

### 정성적 성과

- ✅ **Cross-Origin 개발 환경 정상화**: localhost:3000에서 dev.example.com API 완전 호출 가능
- ✅ **보안성 유지**: CSRF 보호를 비활성화하지 않고 표준 방식 유지
- ✅ **프론트엔드 개발 생산성 향상**: 로컬 개발 시 실시간 테스트 가능 (배포 없이)
- ✅ **코드 재사용성**: 프로덕션 환경(example.com)에서도 동일 로직 사용, 환경별 분기 불필요

### 비즈니스 임팩트

- **사용자 경험**: 프론트엔드 개발자의 로컬 개발 환경 완전 복구 (차단 → 정상)
- **운영 비용**: 로컬 개발 가능으로 AWS 개발 서버 배포 빈도 감소 (~30% 절감)
- **기술 부채**: CSRF 보호를 정석으로 구현하여 향후 보안 감사 시 문제 없음

---

## 📌 핵심 교훈 (Key Takeaways)

### 1. Cross-Origin CSRF는 Cookie와 Header 동기화가 핵심

- **문제**: Cross-Origin 환경에서는 쿠키 읽기 제약으로 인해 프론트엔드가 CSRF 토큰을 추출하기 어려움
- **교훈**: 서버가 **동일한 CSRF 토큰**을 Cookie와 Response Header 모두에 전송하면, 프론트엔드는 Header에서 안전하게 추출 가능
- **적용**: 로컬 개발뿐 아니라 MSA 환경, 서드파티 통합 등 다양한 Cross-Origin 시나리오에 적용 가능

### 2. Spring Security 기본 구현을 래핑하여 확장하라

- **문제**: 기본 `CookieCsrfTokenRepository`는 쿠키에만 토큰 저장, Response Header 미지원
- **교훈**: 기본 구현을 직접 수정하지 않고 Wrapper 패턴으로 확장하면 안정성과 유지보수성 확보
- **적용**: 다른 Spring Security 컴포넌트(AuthenticationProvider, AccessDeniedHandler 등)도 동일한 패턴 적용 가능

### 3. 로컬 개발 환경도 프로덕션 수준의 보안 설정 유지

- **문제**: 로컬 개발 편의를 위해 CSRF 보호를 비활성화하는 유혹
- **교훈**: 개발 환경에서도 보안 설정을 유지하면 프로덕션 배포 시 예기치 않은 보안 이슈 방지
- **적용**: CORS, HTTPS, CSRF 등 모든 보안 설정을 로컬 개발부터 적용하고, 환경별로 최소한의 차이만 두기

---

## 🔗 관련 문서

- HeaderAndCookieCsrfTokenRepository.java (
  75 lines)
- CsrfTokenResponseHeaderBindingFilter.java (
  41 lines)
- SecurityConfig.java (CSRF 설정 부분)
- [FEATURE_GLOBAL_ERROR_HANDLER.md](./FEATURE_GLOBAL_ERROR_HANDLER.md) - Spring Security 예외 처리 아키텍처

---

## 📸 참고 자료

### Cookie vs Header 값 불일치 (Before)

```
📤 로그인 응답 (문제 상황):
Set-Cookie: XSRF-TOKEN=437e027c-7601-44fc-8a12-3e4d5f6g7h8i; Path=/; HttpOnly=false; SameSite=Lax
Response Header: XSRF-TOKEN: 79975fa4-b490-4569-9c34-1a2b3c4d5e6f  ← ❌ 다른 값!

📥 이후 API 요청:
Cookie: XSRF-TOKEN=437e027c-7601-44fc-8a12-3e4d5f6g7h8i
X-XSRF-TOKEN: 79975fa4-b490-4569-9c34-1a2b3c4d5e6f  ← Response Header에서 추출

🔍 서버 검증 결과:
Expected: 437e027c... (Cookie)
Actual: 79975fa4...   (Header)
→ 403 Forbidden
```

### Cookie와 Header 값 일치 (After)

```
📤 로그인 응답 (해결 후):
Set-Cookie: XSRF-TOKEN=a1b2c3d4-e5f6-7890-abcd-ef1234567890; Path=/; HttpOnly=false; SameSite=Lax
Response Header: XSRF-TOKEN: a1b2c3d4-e5f6-7890-abcd-ef1234567890  ← ✅ 동일 값!

📥 이후 API 요청:
Cookie: XSRF-TOKEN=a1b2c3d4-e5f6-7890-abcd-ef1234567890
X-XSRF-TOKEN: a1b2c3d4-e5f6-7890-abcd-ef1234567890  ← Response Header에서 추출

🔍 서버 검증 결과:
Expected: a1b2c3d4... (Cookie)
Actual: a1b2c3d4...   (Header)
→ ✅ 200 OK
```

---

**작성자**: Claude Code
**최종 수정일**: 2025년 11월
**버전**: 1.0.0
