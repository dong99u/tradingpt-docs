# TradingPT - Trading Platform API

> Technical documentation & code patterns for a Spring Boot trading platform
> built with DDD, CQRS, and production-grade AWS infrastructure.
> Live since Dec 2025 — 642+ users, 5,300+ feedback requests processed.

> **Note**: This is a read-only technical showcase. The source repository is private.

[한국어](#한국어)

## Tech Stack

![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen?style=flat-square&logo=springboot)
![Spring Security](https://img.shields.io/badge/Spring%20Security-6-brightgreen?style=flat-square&logo=springsecurity)
![JPA/Hibernate](https://img.shields.io/badge/JPA-Hibernate-59666C?style=flat-square&logo=hibernate)
![QueryDSL](https://img.shields.io/badge/QueryDSL-5.0-blue?style=flat-square)
![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?style=flat-square&logo=mysql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-7-DC382D?style=flat-square&logo=redis&logoColor=white)
![AWS](https://img.shields.io/badge/AWS-EC2%20%7C%20S3%20%7C%20RDS%20%7C%20CloudFront-FF9900?style=flat-square&logo=amazonaws)
![Docker](https://img.shields.io/badge/Docker-2496ED?style=flat-square&logo=docker&logoColor=white)
![GitHub Actions](https://img.shields.io/badge/GitHub%20Actions-CI%2FCD-2088FF?style=flat-square&logo=githubactions&logoColor=white)

## Top 3 Highlights (Start Here)

1. **SQL Query 96% Reduction** — N+1 problem: 101 queries → 4 queries
   → [Read the story](docs/troubleshooting/N+1_QUERY_OPTIMIZATION.md)
2. **p95 Latency 97% Improvement** — 40.7s → 800ms via 3-layer bottleneck analysis
   → [Read the analysis](docs/performance/LOAD_TEST_ANALYSIS.md)
3. **Payment Success 0% → 100%** — REQUIRES_NEW + REPEATABLE_READ isolation fix
   → [Read the investigation](docs/troubleshooting/REPEATABLE_READ_ISOLATION.md)

## Architecture

![Production Architecture](diagrams/architecture-production.png)

## Repository Structure

| Directory | Contents |
|-----------|----------|
| [`docs/`](docs/) | 16 technical documents |
| [`patterns/`](patterns/) | 6 code pattern showcases |
| [`diagrams/`](diagrams/) | Architecture diagrams + ERD |

---

## 한국어

### 프로젝트 소개

20개 도메인을 가진 트레이딩 교육 플랫폼 API. 2025.12 서비스 오픈 이후 가입자 642명+, 피드백 요청 5,300건+ 처리 중.
Spring Boot 3.3.5 / Java 17 / JPA + QueryDSL / Redis Session / AWS Multi-AZ.

### 주요 기술 문서

#### 트러블슈팅 (8건)
- [N+1 쿼리 최적화](docs/troubleshooting/N+1_QUERY_OPTIMIZATION.md) — 101→4 쿼리 (96%↓)
- [REPEATABLE_READ 트랜잭션 격리](docs/troubleshooting/REPEATABLE_READ_ISOLATION.md) — 결제 성공률 0→100%
- [OAuth2 로그인 실패](docs/troubleshooting/OAUTH2_LOGIN_FAILURE.md) — ALB SSL Termination
- [OAuth2 직렬화](docs/troubleshooting/OAUTH2_SERIALIZATION.md) — Jackson→JDK 직렬화
- [커넥션 풀 고갈](docs/troubleshooting/CONNECTION_POOL_EXHAUSTION.md) — HikariCP 최적화
- [CSRF Cross-Origin](docs/troubleshooting/CSRF_CROSS_ORIGIN.md) — Wrapper Pattern
- [동시성 제어](docs/troubleshooting/CONCURRENCY_CONTROL.md) — Optimistic Locking + YAGNI
- [FK 제약조건 해결](docs/troubleshooting/FK_CONSTRAINT_RESOLUTION.md) — Snapshot Pattern

#### 아키텍처 (2건)
- [AWS 인프라](docs/architecture/AWS_INFRASTRUCTURE.md) — Multi-AZ VPC 설계
- [Blue/Green 배포](docs/architecture/BLUEGREEN_DEPLOYMENT.md) — 무중단 배포

#### 기능 설계 (3건)
- [정기 결제](docs/features/RECURRING_PAYMENT.md) — NicePay 빌링키 자동결제
- [글로벌 에러 처리](docs/features/GLOBAL_ERROR_HANDLER.md) — 7단계 예외 계층
- [S3 이미지 보안](docs/features/S3_IMAGE_SECURITY.md) — CloudFront + Presigned URL

#### 성능 (1건)
- [부하 테스트](docs/performance/LOAD_TEST_ANALYSIS.md) — p95 40.7s→800ms (97%↓)

#### 가이드 (2건)
- [DDD Entity 설계](docs/guides/DDD_ENTITY_DESIGN.md) — Rich Domain Model 원칙
- [HikariCP 설정](docs/guides/HIKARICP_CONFIGURATION.md) — 커넥션 풀 튜닝

### 코드 패턴

| 패턴 | 설명 |
|------|------|
| [DDD Rich Domain Model](patterns/ddd-rich-domain-model/) | Entity 비즈니스 메서드, Thin Service Layer |
| [CQRS](patterns/cqrs-pattern/) | Command/Query 분리, readOnly 최적화 |
| [Security](patterns/security/) | Dual FilterChain, CSRF Decorator, OAuth2 |
| [JPA Optimization](patterns/jpa-optimization/) | Dirty Checking, @DynamicUpdate, QueryDSL |
| [Error Handling](patterns/error-handling/) | 글로벌 예외 처리, 표준 응답 포맷 |
| [Scheduler](patterns/scheduler/) | ShedLock 분산 스케줄링 |

### 아키텍처 다이어그램

[diagrams/](diagrams/) 참조

### ERD

[ERD Cloud에서 보기](https://www.erdcloud.com/d/3SBFbay4FKzqudZxA) | [ERD 개요](diagrams/erd-overview.md)

### 연락처

- GitHub: [dong99u](https://github.com/dong99u)
- Email: qkrehdrb0813@gmail.com
