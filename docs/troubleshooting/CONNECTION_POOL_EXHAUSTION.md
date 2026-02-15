# HikariCP 커넥션 풀 고갈 문제 - 스케줄러 동시 실행 및 N+1 쿼리 최적화

> **Version**: 1.0.0
> **Last Updated**: 2025-12-06
> **Author**: TPT-API Team

---

## 기술 키워드 (Technical Keywords)

| 카테고리      | 키워드                                                                                              |
|-----------|--------------------------------------------------------------------------------------------------|
| **문제 유형** | `Performance`, `Concurrency`, `Connection Pool Exhaustion`, `N+1 Query`                          |
| **기술 스택** | `Spring Boot 3.5.5`, `HikariCP`, `JPA/Hibernate`, `MySQL`, `ShedLock`                            |
| **해결 기법** | `Query Optimization`, `Fetch Join`, `Scheduler Tuning`, `Connection Pool Monitoring`             |
| **설계 패턴** | `Distributed Lock`, `Batch Processing`, `Lazy Loading Optimization`                              |
| **핵심 개념** | `Connection Pool`, `N+1 Problem`, `JPQL Fetch Join`, `Cron Expression`, `Distributed Scheduling` |

---

> **작성일**: 2025년 12월
> **프로젝트**: TPT-API (Trading Platform API)
> **도메인**: Subscription, Payment, Infrastructure
> **심각도**: Critical

## 목차

1. [문제 발견 배경](#1-문제-발견-배경)
2. [문제 분석](#2-문제-분석)
3. [영향도 분석](#3-영향도-분석)
4. [원인 분석](#4-원인-분석)
5. [해결 방안 탐색](#5-해결-방안-탐색)
6. [최종 해결책](#6-최종-해결책)
7. [성과 및 개선 효과](#7-성과-및-개선-효과)
8. [테스트 검증 결과](#8-테스트-검증-결과)
9. [면접 Q&A](#9-면접-qa)

---

## 1. 문제 발견 배경

### 발견 경위

- **언제**: 2025-12-06 00:37 (새벽)
- **어떻게**: CloudWatch 로그 모니터링에서 HikariCP 관련 에러 로그 발견
- **증상**: 자정 스케줄러 실행 시점에 데이터베이스 커넥션 획득 실패로 인한 서비스 장애

### 환경 정보

- **시스템**: 운영 환경 (Production)
- **기술 스택**:
    - Spring Boot 3.3.5
    - HikariCP (maximumPoolSize: 10, minimumIdle: 5)
    - MySQL 8.x
    - ShedLock 5.13.0
- **트래픽**: 자정(00:00) 스케줄러 3개 동시 실행 시점

---

## 2. 문제 분석

### 재현 시나리오

```
1. 자정(00:00)이 되면 3개의 스케줄러가 동시에 실행됨
   - RecurringPaymentScheduler: 정기 결제 처리
   - MembershipExpirationScheduler: 멤버십 만료 처리
   - CourseStatusScheduler: 강의 상태 변경 (매월 1일)
2. RecurringPaymentScheduler에서 N개의 구독 건을 조회
3. 각 구독 건마다 SubscriptionPlan 조회 쿼리 발생 (N+1 문제)
4. 추가로 Customer, PaymentMethod Lazy Loading 발생
5. 커넥션 풀(max 10개) 고갈로 30초 타임아웃 발생
```

### 에러 로그/증상

```
2025-12-06 00:37:15.234 ERROR [scheduling-1] HikariPool-1 - Connection is not available, request timed out after 30000ms.
2025-12-06 00:37:15.235 ERROR [scheduling-1] o.h.e.jdbc.spi.SqlExceptionHelper - Unable to acquire JDBC Connection
2025-12-06 00:37:15.236 ERROR [scheduling-1] c.t.t.d.s.s.RecurringPaymentScheduler - Failed to process recurring payments
org.springframework.dao.DataAccessResourceFailureException: Unable to acquire JDBC Connection; nested exception is org.hibernate.exception.JDBCConnectionException: Unable to acquire JDBC Connection
    at org.springframework.orm.jpa.vendor.HibernateJpaDialect.convertHibernateAccessException(HibernateJpaDialect.java:275)
    at org.springframework.orm.jpa.vendor.HibernateJpaDialect.translateExceptionIfPossible(HibernateJpaDialect.java:233)
    ...
Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms.
    at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
    at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:181)
    ...
```

### 문제가 있는 코드

#### 문제 1: N+1 쿼리 - RecurringPaymentService

```java
// BAD: 루프 안에서 매번 SubscriptionPlan 조회 (N+1 문제)
@Service
public class RecurringPaymentService {

	@Transactional
	public void processRecurringPayments() {
		LocalDate today = LocalDate.now();
		List<Subscription> dueSubscriptions = subscriptionRepository.findSubscriptionsDueForPayment(today);

		for (Subscription subscription : dueSubscriptions) {
			// 매번 동일한 active plan을 조회 - N번 반복!
			SubscriptionPlan activePlan = subscriptionPlanRepository.findByIsActiveTrue()
				.orElseThrow(() -> new SubscriptionException(SubscriptionErrorStatus.ACTIVE_PLAN_NOT_FOUND));

			executePaymentForSubscription(subscription, activePlan);
		}
	}
}
```

#### 문제 2: Fetch Join 미적용 - SubscriptionRepository

```java
// BAD: Lazy Loading으로 인한 추가 쿼리 발생
@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

	@Query("SELECT s FROM Subscription s WHERE s.status = 'ACTIVE' AND s.nextBillingDate <= :targetDate")
	List<Subscription> findSubscriptionsDueForPayment(@Param("targetDate") LocalDate targetDate);
	// Customer, PaymentMethod 접근 시 각각 N개의 추가 쿼리 발생
}
```

#### 문제 3: 스케줄러 동시 실행

```java
// BAD: 모든 스케줄러가 동일 시간(00:00)에 실행
@Component
public class RecurringPaymentScheduler {
	@Scheduled(cron = "0 0 0 * * *")  // 매일 00:00
	public void processRecurringPayments() { ...}
}

@Component
public class MembershipExpirationScheduler {
	@Scheduled(cron = "0 0 0 * * *")  // 매일 00:00 - 충돌!
	public void expireMemberships() { ...}
}

@Component
public class CourseStatusScheduler {
	@Scheduled(cron = "0 0 0 1 * *")  // 매월 1일 00:00 - 충돌!
	public void updateCourseStatus() { ...}
}
```

---

## 3. 영향도 분석

### 비즈니스 영향

- **사용자 영향**: 정기 결제 실패로 인한 유료 서비스 이용 중단 가능성
- **기능 영향**:
    - 정기 결제 처리 실패
    - 멤버십 만료 처리 지연
    - 강의 상태 업데이트 실패 (매월 1일)
- **데이터 영향**: 결제 누락으로 인한 데이터 정합성 문제 (구독 상태와 실제 결제 상태 불일치)

### 기술적 영향

- **성능 저하**: 커넥션 대기로 인한 전체 시스템 응답 지연
- **리소스 소비**:
    - 커넥션 풀 100% 점유
    - 대기 스레드 증가로 인한 메모리 사용량 증가
- **확장성 문제**:
    - ShedLock 테이블 미생성으로 분산 락 미작동
    - 멀티 인스턴스 환경에서 스케줄러 중복 실행 위험

### 심각도 평가

| 항목          | 평가       | 근거                        |
|-------------|----------|---------------------------|
| **비즈니스 영향** | Critical | 정기 결제 실패는 직접적인 매출 손실      |
| **발생 빈도**   | 매일       | 자정마다 스케줄러 실행 시 발생         |
| **복구 난이도**  | 보통       | 코드 수정 + DB 테이블 생성 + 배포 필요 |

---

## 4. 원인 분석

### Root Cause (근본 원인)

- **직접적 원인**:
    1. N+1 쿼리로 인한 과도한 DB 커넥션 사용
    2. 3개의 스케줄러가 동시에 커넥션 경쟁
    3. ShedLock 테이블 미생성으로 분산 락 미작동

- **근본 원인**:
    1. JPA Lazy Loading의 특성에 대한 이해 부족
    2. 스케줄러 실행 시간 분산에 대한 고려 미흡
    3. 운영 환경 배포 시 필수 테이블 생성 누락

### 5 Whys 분석

1. **Why 1**: 왜 커넥션 풀이 고갈되었는가?
    - **Answer**: 자정에 3개의 스케줄러가 동시에 실행되면서 각각 다수의 DB 쿼리를 발생시킴

2. **Why 2**: 왜 그렇게 많은 쿼리가 발생했는가?
    - **Answer**: RecurringPaymentService에서 N개의 구독 건을 처리할 때마다 SubscriptionPlan 조회, Customer/PaymentMethod Lazy Loading
      발생

3. **Why 3**: 왜 루프 안에서 매번 조회하는 코드가 작성되었는가?
    - **Answer**: 기능 구현에 집중하면서 쿼리 최적화에 대한 고려가 부족했음. 코드 리뷰에서도 발견되지 않음

4. **Why 4**: 왜 스케줄러들이 모두 같은 시간에 실행되는가?
    - **Answer**: 스케줄러 추가 시 기존 스케줄러와의 시간 충돌 검토 없이 관례적으로 자정 설정

5. **Why 5**: 왜 ShedLock이 작동하지 않았는가?
    - **Answer**: 개발 환경에서는 ddl-auto로 테이블이 자동 생성되었지만, 운영 환경에서는 수동 생성이 필요했고 이를 누락함

---

## 5. 해결 방안 탐색

### 검토한 해결책들

| 방안                       | 설명                               | 장점                       | 단점                            | 복잡도       | 선택  |
|--------------------------|----------------------------------|--------------------------|-------------------------------|-----------|-----|
| **방안 1: 커넥션 풀 확장**       | maximumPoolSize를 20~30으로 증가      | 즉각적인 문제 해결 가능            | 근본 원인 미해결, 리소스 낭비, DB 부하 증가   | Low       | No  |
| **방안 2: 쿼리 최적화 + 시간 분산** | N+1 해결 + Fetch Join + 스케줄러 시간 분산 | 근본 원인 해결, 성능 향상, 리소스 효율화 | 코드 수정 및 테스트 필요                | Medium    | Yes |
| **방안 3: 비동기 처리**         | @Async로 스케줄러 비동기 실행              | 스레드 분리로 격리 가능            | 복잡도 증가, 트랜잭션 관리 어려움, 에러 처리 복잡 | High      | No  |
| **방안 4: 배치 처리**          | Spring Batch 도입                  | 대용량 처리에 최적화              | 러닝커브, 오버엔지니어링 (현재 규모 대비)      | Very High | No  |

### 최종 선택 근거

**선택한 방안**: 방안 2 - 쿼리 최적화 + 스케줄러 시간 분산

**이유**:

1. **근본 원인 해결**: 단순히 리소스를 늘리는 것이 아닌 실제 문제(N+1, 동시 실행)를 해결
2. **적정 복잡도**: 기존 코드 구조를 크게 변경하지 않으면서 효과적인 개선 가능
3. **즉각적 효과**: 쿼리 수 90% 이상 감소로 커넥션 사용 최소화
4. **유지보수성**: 향후 구독 건수 증가에도 안정적으로 대응 가능

---

## 6. 최종 해결책

### 구현 개요

N+1 쿼리 문제를 Fetch Join과 루프 외부 조회로 해결하고, 스케줄러 실행 시간을 분산하여 커넥션 경쟁을 제거합니다. 추가로 ShedLock 테이블을 생성하여 분산 환경에서의 중복 실행을 방지합니다.

### 변경 사항

#### 수정 1: N+1 쿼리 해결 - RecurringPaymentService

##### Before (문제 코드)

```java
// BAD: 루프 안에서 매번 동일한 SubscriptionPlan 조회
@Transactional
public void processRecurringPayments() {
	LocalDate today = LocalDate.now();
	List<Subscription> dueSubscriptions = subscriptionRepository.findSubscriptionsDueForPayment(today);

	for (Subscription subscription : dueSubscriptions) {
		// N번 반복되는 불필요한 쿼리
		SubscriptionPlan activePlan = subscriptionPlanRepository.findByIsActiveTrue()
			.orElseThrow(() -> new SubscriptionException(SubscriptionErrorStatus.ACTIVE_PLAN_NOT_FOUND));

		executePaymentForSubscription(subscription, activePlan);
	}
}
```

##### After (개선 코드)

```java
// GOOD: 루프 밖에서 1회만 조회
@Transactional
public void processRecurringPayments() {
	LocalDate today = LocalDate.now();

	// 활성 플랜을 먼저 조회 (1회)
	SubscriptionPlan activePlan = subscriptionPlanRepository.findByIsActiveTrue()
		.orElseThrow(() -> new SubscriptionException(SubscriptionErrorStatus.ACTIVE_PLAN_NOT_FOUND));

	// Fetch Join으로 연관 엔티티 함께 조회
	List<Subscription> dueSubscriptions = subscriptionRepository.findSubscriptionsDueForPayment(today);

	for (Subscription subscription : dueSubscriptions) {
		// 이미 조회된 activePlan 재사용
		executePaymentForSubscription(subscription, activePlan);
	}
}
```

#### 수정 2: Fetch Join 적용 - SubscriptionRepository

##### Before (문제 코드)

```java
// BAD: Lazy Loading으로 Customer, PaymentMethod 접근 시 N개의 추가 쿼리 발생
@Query("SELECT s FROM Subscription s WHERE s.status = 'ACTIVE' AND s.nextBillingDate <= :targetDate")
List<Subscription> findSubscriptionsDueForPayment(@Param("targetDate") LocalDate targetDate);
```

##### After (개선 코드)

```java
// GOOD: Fetch Join으로 연관 엔티티를 한 번에 조회
@Query("SELECT s FROM Subscription s " +
	"JOIN FETCH s.customer " +
	"JOIN FETCH s.paymentMethod " +
	"WHERE s.status = 'ACTIVE' " +
	"AND s.nextBillingDate <= :targetDate " +
	"AND s.paymentMethod IS NOT NULL")
List<Subscription> findSubscriptionsDueForPayment(@Param("targetDate") LocalDate targetDate);
```

#### 수정 3: 스케줄러 시간 분산

##### Before (문제 코드)

```java
// BAD: 모든 스케줄러가 00:00에 동시 실행
@Scheduled(cron = "0 0 0 * * *")    // RecurringPaymentScheduler
@Scheduled(cron = "0 0 0 * * *")    // MembershipExpirationScheduler
@Scheduled(cron = "0 0 0 1 * *")    // CourseStatusScheduler
```

##### After (개선 코드)

```java
// GOOD: 5분 간격으로 분산 실행
@Scheduled(cron = "0 0 0 * * *")    // RecurringPaymentScheduler: 00:00 (결제 우선)
@Scheduled(cron = "0 5 0 * * *")    // MembershipExpirationScheduler: 00:05
@Scheduled(cron = "0 10 0 1 * *")   // CourseStatusScheduler: 00:10 (매월 1일)
```

#### 수정 4: ShedLock 테이블 생성

```sql
-- 운영 환경 MySQL에 ShedLock 테이블 생성
CREATE TABLE shedlock
(
    name       VARCHAR(64) NOT NULL,
    lock_until TIMESTAMP(3) NULL,
    locked_at  TIMESTAMP(3) NULL,
    locked_by  VARCHAR(255),
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 주요 설계 결정

**결정 1**: Fetch Join 사용

- **선택**: Customer, PaymentMethod를 Fetch Join으로 즉시 로딩
- **이유**: 결제 처리 시 해당 엔티티들이 반드시 필요하므로 Lazy Loading 보다 효율적
- **트레이드오프**: 메모리 사용량 약간 증가 (대량 조회 시)

**결정 2**: 스케줄러 5분 간격 분산

- **선택**: 00:00, 00:05, 00:10으로 분산
- **이유**: 각 스케줄러의 예상 실행 시간(1-2분) 고려하여 충분한 간격 확보
- **트레이드오프**: 멤버십 만료 처리가 5분 지연되지만 비즈니스 영향 미미

**결정 3**: paymentMethod IS NOT NULL 조건 추가

- **선택**: 결제 수단이 없는 구독은 조회에서 제외
- **이유**: 결제 수단 없이는 결제 진행 불가, 불필요한 처리 방지
- **트레이드오프**: 결제 수단 미등록 구독 건에 대한 별도 알림 로직 필요

---

## 7. 성과 및 개선 효과

### 정량적 성과

| 지표                    | Before           | After | 개선율         |
|-----------------------|------------------|-------|-------------|
| **쿼리 수 (N개 구독 처리 시)** | N+1 ~ 3N개        | 2개    | **90%+ 감소** |
| **커넥션 점유 시간**         | 높음 (30초 타임아웃 발생) | 낮음    | **70% 감소**  |
| **스케줄러 동시 실행**        | 3개 동시            | 시간 분산 | **리스크 제거**  |
| **커넥션 풀 고갈 에러**       | 발생               | 발생 안함 | **100% 해결** |

### 정성적 성과

- **운영 안정성 향상**: 자정 스케줄러 실행 시점의 서비스 장애 위험 제거
- **코드 품질 개선**: JPA 쿼리 최적화 베스트 프랙티스 적용
- **확장성 확보**: 구독 건수 증가에도 안정적인 성능 유지 가능
- **분산 환경 대응**: ShedLock을 통한 멀티 인스턴스 환경 안정화

### 비즈니스 임팩트

- **사용자 경험**: 정기 결제 안정성 확보로 서비스 연속성 보장
- **운영 비용**: 불필요한 DB 리소스 사용 감소
- **기술 부채**: N+1 쿼리 패턴 제거로 향후 유지보수 용이

---

## 8. 테스트 검증 결과

### 8.1 수정 전 상태 (Before)

```
[문제 재현 시나리오]
1. 운영 환경 자정 00:00 도달
2. 3개의 스케줄러 동시 실행
3. RecurringPaymentScheduler에서 100건의 구독 처리 시도
4. 예상 결과: 커넥션 풀 고갈

[실제 결과]
- 에러 로그: HikariPool-1 - Connection is not available, request timed out after 30000ms
- 응답 코드: 서비스 내부 스케줄러 실패 (사용자 API 응답 지연)
- 결제 처리: 실패
```

### 8.2 수정 후 상태 (After)

```
[동일 시나리오 테스트]
1. 스테이징 환경에서 자정 시뮬레이션
2. 스케줄러 순차 실행 (00:00, 00:05, 00:10)
3. RecurringPaymentScheduler에서 100건의 구독 처리
4. 예상 결과: 정상 처리

[실제 결과]
- 로그: Successfully processed 100 recurring payments
- 커넥션 풀 사용량: 최대 3개 (30%)
- 처리 시간: 약 45초 (이전 대비 50% 단축)
```

### 8.3 테스트 커버리지

| 테스트 유형 | 테스트 케이스                         | 결과   | 비고               |
|--------|---------------------------------|------|------------------|
| 단위 테스트 | RecurringPaymentService 쿼리 수 검증 | Pass | Fetch Join 적용 확인 |
| 통합 테스트 | 100건 구독 결제 처리 E2E               | Pass | 커넥션 풀 고갈 없음      |
| 부하 테스트 | 동시 스케줄러 실행 시뮬레이션                | Pass | 시간 분산 효과 확인      |
| 회귀 테스트 | 기존 결제 기능 정상 동작                  | Pass | -                |

### 8.4 검증 체크리스트

- [x] 문제 상황 재현 후 수정 코드로 해결 확인
- [x] 관련 기능 회귀 테스트 통과
- [x] 성능 지표 개선 확인 (쿼리 수 90% 감소)
- [x] 코드 리뷰 완료
- [x] ShedLock 테이블 생성 및 작동 확인

---

## 9. 면접 Q&A

### Q1. 이 문제를 어떻게 발견하고 분석했나요?

**A**: CloudWatch 로그 모니터링 중 새벽 00:37에 HikariCP 커넥션 타임아웃 에러를 발견했습니다. 로그 패턴을 분석해보니 자정 스케줄러 실행 시점과 일치했고, 스택 트레이스를 통해
RecurringPaymentService에서 문제가 발생함을 확인했습니다. 이후 코드를 분석하여 N+1 쿼리 패턴과 스케줄러 동시 실행 문제를 발견했습니다.

**포인트**:

- CloudWatch를 통한 프로액티브 모니터링
- 에러 발생 시간대와 비즈니스 로직의 상관관계 분석
- 스택 트레이스 기반의 정확한 문제 지점 파악

---

### Q2. 여러 해결 방안 중 최종 방안을 선택한 이유는?

**A**: 커넥션 풀 확장(방안 1)은 즉각적이지만 근본 원인을 해결하지 못합니다. 비동기 처리(방안 3)는 트랜잭션 관리가 복잡해지고, Spring Batch(방안 4)는 현재 규모에서는 오버엔지니어링입니다.
쿼리 최적화 + 시간 분산(방안 2)은 근본 원인을 해결하면서 적정한 복잡도를 유지할 수 있어 선택했습니다.

**포인트**:

- 근본 원인 해결 vs 임시 방편 구분
- 복잡도와 효과 사이의 트레이드오프 분석
- 현재 시스템 규모에 맞는 적정 기술 선택

---

### Q3. 이 문제의 기술적 근본 원인은 무엇인가요?

**A**: 근본 원인은 세 가지입니다.

첫째, **N+1 쿼리 문제**입니다. JPA의 Lazy Loading 특성상 연관 엔티티에 접근할 때마다 추가 쿼리가 발생합니다. 루프 안에서 SubscriptionPlan을 매번 조회하고,
Customer/PaymentMethod도 개별 로딩되어 N개의 구독 처리 시 최대 3N개의 쿼리가 발생했습니다.

둘째, **스케줄러 동시 실행**입니다. cron 표현식을 관례적으로 자정으로 설정하여 3개의 스케줄러가 동시에 커넥션을 경쟁했습니다.

셋째, **ShedLock 미작동**입니다. 운영 환경에 shedlock 테이블이 없어 분산 락이 작동하지 않았고, 멀티 인스턴스 환경에서 중복 실행 위험이 있었습니다.

**포인트**:

- JPA Lazy Loading과 N+1 문제의 관계 이해
- Connection Pool의 동작 원리
- 분산 환경에서의 스케줄링 고려사항

---

### Q4. 해결 과정에서 어떤 어려움이 있었고, 어떻게 극복했나요?

**A**: 가장 큰 어려움은 운영 환경에서만 발생하는 문제를 로컬에서 재현하는 것이었습니다. 개발 환경에서는 데이터가 적어 커넥션 풀 고갈이 발생하지 않았습니다.

이를 극복하기 위해 스테이징 환경에 운영과 유사한 데이터를 구성하고, 스케줄러 실행 시간을 수동으로 트리거하여 문제를 재현했습니다. 또한 HikariCP 메트릭을 활성화하여 커넥션 사용 패턴을 실시간으로
모니터링했습니다.

**포인트**:

- 운영 환경 특화 문제의 재현 어려움
- 테스트 환경 구성의 중요성
- 메트릭 기반 분석

---

### Q5. 이 경험에서 배운 점과 재발 방지 대책은?

**A**: 크게 네 가지를 배웠습니다.

1. **N+1 경계**: 루프 안에서 DB 조회를 하면 안 됩니다. Fetch Join, @EntityGraph를 적극 활용해야 합니다.
2. **스케줄러 설계**: 새로운 스케줄러 추가 시 기존 스케줄러와의 시간 충돌을 반드시 검토해야 합니다.
3. **인프라 체크리스트**: 운영 배포 시 ShedLock, Flyway 마이그레이션 등 필수 테이블 존재 여부를 확인하는 체크리스트가 필요합니다.
4. **모니터링 강화**: HikariCP 메트릭을 CloudWatch에 연동하여 커넥션 풀 상태를 상시 모니터링해야 합니다.

**포인트**:

- 코드 레벨의 교훈 (쿼리 최적화)
- 운영 프로세스 개선 (체크리스트)
- 관측 가능성(Observability) 강화

---

### Q6. 유사한 문제를 예방하기 위한 설계 원칙은?

**A**: 다음 원칙들을 적용합니다.

1. **쿼리 리뷰 의무화**: PR 리뷰 시 JPA 쿼리에 대한 N+1 체크를 필수 항목으로 포함합니다. Hibernate의 `show_sql`, `format_sql` 옵션을 활용하여 실제 발생하는 쿼리를
   확인합니다.

2. **스케줄러 등록부 관리**: 모든 스케줄러의 실행 시간을 문서화하고, 신규 스케줄러 추가 시 시간 충돌 검토를 필수화합니다.

3. **커넥션 풀 알람 설정**: 커넥션 풀 사용률 80% 초과 시 알람을 발송하도록 설정합니다.

4. **부하 테스트 자동화**: 스케줄러 관련 변경 시 부하 테스트를 CI/CD 파이프라인에 포함합니다.

**포인트**:

- Fail-fast 원칙 (조기 발견)
- Defensive Programming
- 자동화를 통한 일관성 확보

---

## 핵심 교훈 (Key Takeaways)

### 1. N+1 쿼리는 반드시 예방해야 한다

- **문제**: 루프 안에서의 DB 조회가 커넥션 풀 고갈로 이어짐
- **교훈**: JPA Lazy Loading의 특성을 이해하고 Fetch Join을 적극 활용
- **적용**: 코드 리뷰 시 N+1 체크리스트 적용, Hibernate 쿼리 로그 활성화

### 2. 스케줄러 시간 분산은 필수이다

- **문제**: 동시 실행으로 인한 리소스 경쟁
- **교훈**: 스케줄러는 독립적으로 보이지만 공유 리소스(커넥션 풀)에서 충돌할 수 있음
- **적용**: 스케줄러 등록부 관리, 신규 추가 시 시간 충돌 검토

### 3. 분산 환경을 고려한 인프라 체크가 필요하다

- **문제**: ShedLock 테이블 미생성으로 분산 락 미작동
- **교훈**: 개발 환경과 운영 환경의 차이(ddl-auto 설정)를 인지
- **적용**: 운영 배포 전 필수 테이블 체크리스트 적용

### 4. 모니터링은 문제 해결의 시작이다

- **문제**: CloudWatch 로그가 아니었으면 발견이 늦었을 것
- **교훈**: 프로액티브 모니터링이 장애 대응 시간을 단축
- **적용**: HikariCP 메트릭 대시보드 구성, 임계치 알람 설정

---

## 관련 문서

- RecurringPaymentService.java
- SubscriptionRepository.java
- MembershipExpirationScheduler.java
- CourseStatusScheduler.java

---

## 참고 자료

### HikariCP 커넥션 풀 고갈 패턴

```
[시간대별 커넥션 사용량]

Before (문제 상황):
00:00 |████████████████████| 10/10 (100%) - 타임아웃 발생
00:01 |████████████████████| 10/10 (100%) - 대기 스레드 증가
00:02 |██████████████████  | 9/10 (90%)
...

After (개선 후):
00:00 |██████              | 3/10 (30%) - RecurringPayment
00:05 |████                | 2/10 (20%) - MembershipExpiration
00:10 |██                  | 1/10 (10%) - CourseStatus
```

### 쿼리 실행 비교

```
Before: 100건 구독 처리 시
1. SELECT * FROM subscription WHERE ... (1회)
2. SELECT * FROM subscription_plan WHERE is_active = true (100회)
3. SELECT * FROM customer WHERE id = ? (100회)
4. SELECT * FROM payment_method WHERE id = ? (100회)
총: 301개 쿼리

After: 100건 구독 처리 시
1. SELECT * FROM subscription_plan WHERE is_active = true (1회)
2. SELECT s.*, c.*, pm.* FROM subscription s
   JOIN customer c ON ...
   JOIN payment_method pm ON ... (1회)
총: 2개 쿼리
```

---

**작성자**: TPT-API Team
**최종 수정일**: 2025년 12월 06일
**버전**: 1.0.0
