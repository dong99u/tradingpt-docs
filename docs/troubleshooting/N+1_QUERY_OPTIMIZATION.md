# JPA N+1 쿼리 최적화 - Connection Pool 고갈 근본 원인 해결

> **Version**: 1.0.0
> **Last Updated**: 2025-12-09
> **Author**: Trading PT Backend Team

---

## 기술 키워드 (Technical Keywords)

| 카테고리 | 키워드 |
|---------|--------|
| **최적화 영역** | `Query Optimization`, `N+1 Problem`, `JPA Fetch Optimization`, `Connection Pool` |
| **측정 도구** | `p6spy`, `Hibernate Statistics`, `CloudWatch` |
| **최적화 기법** | `Fetch Join`, `Batch Query`, `JPQL Optimization`, `LinkedHashMap Deduplication` |
| **인프라** | `HikariCP`, `AWS RDS`, `MySQL` |
| **핵심 지표** | `Query Count`, `Connection Usage`, `Response Time` |

---

> **작성일**: 2025년 12월
> **프로젝트**: TPT-API (Trading Platform API)
> **도메인**: Admin Dashboard / Subscription / Customer
> **최적화 대상**: N+1 쿼리로 인한 Connection Pool 고갈 문제

## 목차

1. [성능 문제 발견](#1-성능-문제-발견)
2. [현재 상태 측정](#2-현재-상태-측정)
3. [병목 지점 분석](#3-병목-지점-분석)
4. [최적화 목표 설정](#4-최적화-목표-설정)
5. [최적화 전략 및 실행](#5-최적화-전략-및-실행)
6. [최종 성과 및 비즈니스 임팩트](#6-최종-성과-및-비즈니스-임팩트)
7. [지속 가능성 및 모니터링](#7-지속-가능성-및-모니터링)
8. [테스트 검증 결과](#8-테스트-검증-결과)
9. [면접 Q&A](#9-면접-qa)

---

## 1. 성능 문제 발견

### 발견 경위
- **트리거**: HikariCP Connection Pool 고갈로 인한 서버 장애 발생
- **발견 시점**: 2025-12-05 ~ 2025-12-07 (연속 장애)
- **영향 범위**: Admin 대시보드 전체 API 응답 불가

### 관찰된 증상
- **사용자 경험**: Admin 대시보드 접속 시 모든 API 타임아웃
- **시스템 지표**:
  - HikariCP 10개 커넥션 100% 점유
  - waiting 큐에 15-23개 요청 대기
  - 20초 타임아웃 후 전체 장애
- **비즈니스 영향**: 관리자 업무 전면 중단, 신규 고객 관리 불가

### 비즈니스 임팩트
- **사용자 수**: 관리자 전원 (Admin 대시보드 이용자)
- **트래픽 규모**: 대시보드 초기 로딩 시 10+ API 동시 호출
- **비즈니스 손실**: 런칭 D-7 시점에서 관리 업무 중단 위험

### 관련 문서
- [2025-12-06-load-test-performance-analysis.md](./2025-12-06-load-test-performance-analysis.md) - 부하 테스트 성능 분석
- [2025-12-06_CONNECTION_POOL_EXHAUSTION.md](./2025-12-06_CONNECTION_POOL_EXHAUSTION.md) - 스케줄러 커넥션 풀 고갈
- [2025-12-HIKARICP_CONNECTION_POOL_EXHAUSTION.md](./2025-12-HIKARICP_CONNECTION_POOL_EXHAUSTION.md) - Admin 대시보드 N+1 문제

---

## 2. 현재 상태 측정

### 측정 환경
- **테스트 도구**: p6spy (SQL 로깅), Hibernate Statistics
- **인프라**:
  - EC2: t3.medium (2 vCPU) x 3대
  - RDS: db.t4g.medium (2 vCPU, 4GB RAM)
  - HikariCP: maximum-pool-size=10
- **부하 시나리오**: Admin 대시보드 초기 로딩

### Baseline 성능 지표 (최적화 전)

| 지표 | 측정값 | 목표값 | 상태 |
|------|--------|--------|------|
| **고객 20명 조회 시 쿼리 수** | 60-100개 | <10개 | :x: 목표 미달 |
| **커넥션 점유** | 10/10 (100%) | <80% | :x: 고갈 |
| **API 응답 시간** | 20초+ (타임아웃) | <2초 | :x: 목표 미달 |
| **waiting 요청** | 15-23개 | 0개 | :x: 병목 |

### 문제가 있는 API 목록

| API | 문제 | 쿼리 수 |
|-----|------|---------|
| `/api/v1/admin/users/new-subscription-customers` | Customer에서 시작하여 N+1 발생 | 1 + (N * 5) |
| `/api/v1/admin/users/free-customers` | uid fetch join 누락 | 1 + N |
| `/api/v1/admin/subscriptions/customers` | QueryDSL 복잡한 DTO 직접 조회 | 1 + N |

### 주요 발견 사항
- **발견 1**: Customer에서 시작하면 Subscription이 ToMany 관계 (fetch join 불가)
- **발견 2**: uid(OneToOne) fetch join 누락으로 N+1 발생
- **발견 3**: DTO 직접 조회 시 연관 엔티티 로딩 제어 어려움

---

## 3. 병목 지점 분석

### 시스템 계층별 분석

#### Application Layer
- **N+1 쿼리 패턴**: 루프 안에서 연관 엔티티 개별 조회
- **JPA 관계 방향 문제**: Customer(1)에서 시작하면 Subscription이 ToMany
- **Lazy Loading 오용**: OneToOne 관계(uid) fetch join 누락

#### Database Layer
- **쿼리 수 폭발**: 고객 20명 조회 시 100개 쿼리 실행
- **커넥션 고갈**: 10개 커넥션으로 동시 쿼리 처리 불가
- **트랜잭션 장시간 점유**: N+1로 인한 트랜잭션 시간 증가

### 병목 지점 우선순위

| 순위 | 병목 지점 | 영향도 | 개선 난이도 | 예상 효과 |
|------|-----------|--------|-------------|-----------|
| **1** | new-subscription-customers N+1 | Critical | 중간 | 쿼리 90% 감소 |
| **2** | free-customers uid N+1 | High | 낮음 | 쿼리 50% 감소 |
| **3** | subscriptions/customers QueryDSL | Medium | 높음 | 코드 간결화 |

### 핵심 문제: JPA 관계 방향에 따른 N+1

```
Customer(1) ─────────────────> Subscription(N)
    │                              │
    │ ToMany 관계                   │ ToOne 관계
    │ fetch join 시 row 증가!       │ fetch join 시 row 유지
    │                              │
    ▼                              ▼
[Customer에서 시작하면]           [Subscription에서 시작하면]
- Subscription: ToMany            - Customer: ToOne
- fetch join 불가 (페이징!)        - fetch join 가능!
- N+1 발생                        - N+1 해결
```

---

## 4. 최적화 목표 설정

### 성능 목표

#### Primary Goals (필수 달성)
- **쿼리 수**: 100개 -> 4개 이하 (96% 감소)
- **커넥션 사용률**: 100% -> <50%
- **응답 시간**: 20초+ -> <2초

#### Secondary Goals (추가 목표)
- **코드 품질**: QueryDSL 복잡 로직 -> Spring Data JPA로 간결화
- **유지보수성**: N+1 방지 패턴 정립 및 문서화

### 제약 조건
- **기술적 제약**: 기존 API 스펙(응답 형식) 유지
- **비즈니스 제약**: 런칭 D-7 상황, 코드 변경 리스크 최소화
- **시간 제약**: 1일 내 최적화 완료

### 성공 기준
- [x] **모든 대상 API에서 N+1 문제 해결**
- [x] **커넥션 풀 고갈 현상 제거**
- [x] **기존 API 스펙 호환성 유지**
- [x] **코드 리뷰 및 테스트 통과**

---

## 5. 최적화 전략 및 실행

### 최적화 로드맵

```
1. new-subscription-customers API 최적화 (핵심)
   └─> Subscription에서 시작하도록 쿼리 방향 변경

2. free-customers API 최적화
   └─> uid fetch join 추가, JPQL로 리팩토링

3. subscriptions/customers API 최적화
   └─> QueryDSL → Spring Data JPA 전환

4. p6spy 설정 추가
   └─> SQL 쿼리 모니터링 환경 구축
```

---

### 1차 최적화: new-subscription-customers API

**문제**: Customer에서 시작하여 N+1 발생

**핵심 해결 원칙**: **N쪽(Subscription)에서 시작하면 모든 연관관계가 ToOne!**

#### Before (문제 코드)

**CustomerRepository.java** - Customer에서 시작
```java
// BAD: Customer에서 시작하면 Subscription이 ToMany
// fetch join 시 row 증가로 페이징 불가
@Query("""
    SELECT DISTINCT c FROM Customer c
    INNER JOIN Subscription s ON s.customer = c
    LEFT JOIN FETCH c.assignedTrainer
    LEFT JOIN FETCH c.uid
    WHERE s.status = :status
    AND (s.createdAt > :cutoffTime OR c.assignedTrainer IS NULL)
    ORDER BY s.createdAt DESC
    """)
Slice<Customer> findNewSubscriptionCustomers(...);
```

**CustomerQueryServiceImpl.java** - 개별 쿼리로 N+1 발생
```java
// BAD: 각 고객마다 개별 쿼리 실행 (N+1 문제)
private NewSubscriptionCustomerResponseDTO toDTO(Customer customer) {
    // 쿼리 1: leveltest exists 체크
    boolean hasAttemptedLevelTest = leveltestAttemptRepository
        .existsByCustomer_Id(customer.getId());  // N번 실행!

    // 쿼리 2-4: 상태별 조회
    List<LevelTestAttempt> gradedAttempts = leveltestAttemptRepository
        .findByCustomer_IdAndStatus(customer.getId(), GRADED);  // N번 실행!
    // ...

    // 쿼리 5: 상담 조회
    List<Consultation> consultations = consultationRepository
        .findByCustomerId(customer.getId());  // N번 실행!
}
```

#### After (개선 코드)

**SubscriptionRepository.java** - Subscription에서 시작 (핵심 변경)
```java
/**
 * 신규 구독 목록 조회 (N+1 최적화 - JPA Fetch Optimization 적용)
 *
 * JPA Fetch Optimization 핵심 원칙:
 * - N쪽(Subscription)에서 시작하면 모든 연관관계가 ToOne이 됨!
 * - ToOne 관계는 fetch join으로 한 번에 조회 (row 수 증가 없음)
 *
 * 관계 분석:
 * - Subscription -> Customer: ManyToOne (fetch join OK)
 * - Customer -> assignedTrainer: ManyToOne (fetch join OK)
 * - Customer -> uid: OneToOne (fetch join OK)
 */
@Query("""
    SELECT s FROM Subscription s
    JOIN FETCH s.customer c
    LEFT JOIN FETCH c.assignedTrainer
    LEFT JOIN FETCH c.uid
    WHERE s.status = :status
    AND (s.createdAt > :cutoffTime OR c.assignedTrainer IS NULL)
    ORDER BY s.createdAt DESC
    """)
Slice<Subscription> findNewSubscriptionsWithCustomerDetails(
    @Param("status") Status status,
    @Param("cutoffTime") LocalDateTime cutoffTime,
    Pageable pageable
);
```

**CustomerQueryServiceImpl.java** - 배치 쿼리 + LinkedHashMap 중복 제거
```java
@Override
public NewSubscriptionCustomerSliceResponseDTO getNewSubscriptionCustomers(Pageable pageable) {
    LocalDateTime cutoffTime = LocalDateTime.now().minusHours(24);

    // 1. Subscription에서 시작하여 조회 (모든 ToOne 관계 fetch join)
    Slice<Subscription> subscriptionSlice = subscriptionRepository
        .findNewSubscriptionsWithCustomerDetails(Status.ACTIVE, cutoffTime, pageable);

    // 2. Customer 기준 중복 제거 (LinkedHashMap으로 순서 유지)
    Map<Long, Customer> uniqueCustomerMap = new LinkedHashMap<>();
    for (Subscription subscription : subscriptionSlice.getContent()) {
        Customer customer = subscription.getCustomer();
        uniqueCustomerMap.putIfAbsent(customer.getId(), customer);
    }
    List<Customer> customers = new ArrayList<>(uniqueCustomerMap.values());

    // 3. 고객 ID 목록 추출
    List<Long> customerIds = customers.stream()
        .map(Customer::getId)
        .toList();

    // 4. 배치 쿼리: 모든 레벨테스트 시도 내역 한 번에 조회
    List<LevelTestAttempt> allAttempts = leveltestAttemptRepository
        .findByCustomerIdIn(customerIds);  // 1개 쿼리로 N개 고객 처리!

    // 5. 고객별 그룹핑
    Map<Long, List<LevelTestAttempt>> attemptsByCustomerId = allAttempts.stream()
        .collect(Collectors.groupingBy(a -> a.getCustomer().getId()));

    // 6. 배치 쿼리: 상담 예약이 있는 고객 ID Set 조회
    Set<Long> customerIdsWithConsultation = consultationRepository
        .findCustomerIdsWithConsultation(customerIds);  // 1개 쿼리!

    // 7. 메모리에서 DTO 조립 (추가 쿼리 없음)
    List<NewSubscriptionCustomerResponseDTO> dtoList = customers.stream()
        .map(customer -> buildDTO(customer, attemptsByCustomerId, customerIdsWithConsultation))
        .toList();

    // 8. 결과 반환
    return NewSubscriptionCustomerSliceResponseDTO.from(
        new SliceImpl<>(dtoList, pageable, subscriptionSlice.hasNext()),
        subscriptionRepository.countNewSubscriptionCustomers(Status.ACTIVE, cutoffTime)
    );
}
```

**배치 쿼리용 Repository 메서드 추가**

**LeveltestAttemptRepository.java**
```java
/**
 * 여러 고객의 레벨테스트 시도 내역을 한 번에 조회 (N+1 방지용 배치 쿼리)
 */
@Query("""
    SELECT a FROM LevelTestAttempt a
    JOIN FETCH a.customer
    LEFT JOIN FETCH a.trainer
    WHERE a.customer.id IN :customerIds
    ORDER BY a.customer.id, a.createdAt DESC
    """)
List<LevelTestAttempt> findByCustomerIdIn(@Param("customerIds") Collection<Long> customerIds);
```

**ConsultationRepository.java**
```java
/**
 * 여러 고객 중 상담 예약이 있는 고객 ID 목록 조회 (N+1 방지용 배치 쿼리)
 */
@Query("""
    SELECT DISTINCT c.customer.id FROM Consultation c
    WHERE c.customer.id IN :customerIds
    """)
Set<Long> findCustomerIdsWithConsultation(@Param("customerIds") Collection<Long> customerIds);
```

#### 측정 결과

| 지표 | Before | After | 개선율 |
|------|--------|-------|--------|
| 쿼리 수 (20명) | 1 + (20 * 5) = 101개 | **4개** | **96% 감소** |
| 커넥션 점유 시간 | 높음 | 낮음 | **70% 감소** |
| 코드 라인 수 | 복잡 | 명확한 흐름 | **가독성 향상** |

---

### 2차 최적화: free-customers API

**문제**: uid fetch join 누락으로 N+1 발생, QueryDSL 불필요한 복잡성

#### Before (문제 코드)

**CustomerRepositoryImpl.java** - QueryDSL 복잡한 구현
```java
// BAD: QueryDSL로 복잡하게 구현, uid fetch join 누락
@Override
public Slice<FreeCustomerResponseDTO> findFreeCustomers(Pageable pageable) {
    QCustomer customer = QCustomer.customer;
    QSubscription subscription = QSubscription.subscription;

    // 복잡한 서브쿼리 + uid N+1 발생
    List<FreeCustomerResponseDTO> results = queryFactory
        .select(Projections.constructor(FreeCustomerResponseDTO.class,
            customer.id,
            customer.username,
            customer.phoneNumber,
            customer.uid.uid  // uid 접근 시 N+1!
        ))
        .from(customer)
        .where(
            // 복잡한 NOT EXISTS 로직...
        )
        .fetch();
}
```

#### After (개선 코드)

**CustomerRepository.java** - Spring Data JPA로 간결화
```java
/**
 * 미구독(무료) 고객 목록 조회
 *
 * JPA Fetch Optimization 참고:
 * - "ACTIVE 구독이 없는 고객"을 찾는 것이므로 Customer에서 시작
 * - NOT EXISTS 패턴으로 ACTIVE 구독이 하나라도 있으면 제외
 * - ToOne 관계 (uid) -> fetch join으로 N+1 해결
 */
@Query("""
    SELECT c FROM Customer c
    LEFT JOIN FETCH c.uid
    WHERE NOT EXISTS (
        SELECT 1 FROM Subscription s
        WHERE s.customer = c AND s.status = com.example.tradingpt.domain.subscription.enums.Status.ACTIVE
    )
    AND c.membershipLevel = com.example.tradingpt.domain.user.enums.MembershipLevel.BASIC
    AND c.assignedTrainer IS NULL
    """)
Slice<Customer> findFreeCustomers(Pageable pageable);

/**
 * 미구독(무료) 고객 총 인원 수 조회
 */
@Query("""
    SELECT COUNT(c) FROM Customer c
    WHERE NOT EXISTS (
        SELECT 1 FROM Subscription s
        WHERE s.customer = c AND s.status = com.example.tradingpt.domain.subscription.enums.Status.ACTIVE
    )
    AND c.membershipLevel = com.example.tradingpt.domain.user.enums.MembershipLevel.BASIC
    AND c.assignedTrainer IS NULL
    """)
Long countFreeCustomers();
```

**CustomerQueryServiceImpl.java** - 간결한 서비스 로직
```java
@Override
public FreeCustomerSliceResponseDTO getFreeCustomers(Pageable pageable) {
    // 1. Repository에서 미구독 고객 조회 (uid fetch join 완료)
    Slice<Customer> customerSlice = customerRepository.findFreeCustomers(pageable);

    // 2. Entity를 DTO로 변환 (N+1 없음 - uid 이미 로딩됨)
    Slice<FreeCustomerResponseDTO> dtoSlice = customerSlice.map(FreeCustomerResponseDTO::from);

    // 3. 총 인원 수 조회
    Long totalCount = customerRepository.countFreeCustomers();

    return FreeCustomerSliceResponseDTO.from(dtoSlice, totalCount);
}
```

**삭제된 파일**:
- `CustomerRepositoryCustom.java` - 해당 메서드 제거
- `CustomerRepositoryImpl.java` - 해당 메서드 제거

#### 측정 결과

| 지표 | Before | After | 개선율 |
|------|--------|-------|--------|
| 쿼리 수 (20명) | 1 + 20 = 21개 | **2개** | **90% 감소** |
| 코드 라인 수 | ~50줄 (QueryDSL) | **10줄 (JPQL)** | **80% 감소** |
| 유지보수성 | 복잡 | 명확 | **크게 향상** |

---

### 3차 최적화: subscriptions/customers API

**문제**: QueryDSL로 DTO 직접 조회, 연관 엔티티 제어 어려움

#### Before (문제 코드)

**SubscriptionRepositoryImpl.java** - QueryDSL DTO 직접 조회
```java
// BAD: DTO 직접 조회로 연관 엔티티 접근 시 N+1
@Override
public Slice<SubscriptionCustomerResponseDTO> findActiveSubscriptionCustomers(
    Long trainerId,
    Boolean myCustomersOnly,
    Pageable pageable
) {
    // 복잡한 QueryDSL 조건...
    // feedbackRequestCount 조회 시 N+1 발생
}
```

#### After (개선 코드)

**SubscriptionRepository.java** - 엔티티 조회 후 서비스에서 DTO 변환
```java
/**
 * 활성 구독 목록 조회 (JPA Fetch Optimization 적용)
 *
 * 원칙: 엔티티 조회 후 서비스에서 DTO 변환 (DTO 직접 조회는 최후의 수단)
 *
 * 관계 분석:
 * - Subscription -> Customer: ManyToOne (fetch join OK)
 * - Customer -> assignedTrainer: ManyToOne (fetch join OK)
 * - Customer -> uid: OneToOne (fetch join OK)
 */
@Query("""
    SELECT s FROM Subscription s
    JOIN FETCH s.customer c
    LEFT JOIN FETCH c.assignedTrainer t
    LEFT JOIN FETCH c.uid
    WHERE s.status = com.example.tradingpt.domain.subscription.enums.Status.ACTIVE
    AND (:trainerId IS NULL OR t.id = :trainerId)
    ORDER BY s.createdAt DESC
    """)
Slice<Subscription> findActiveSubscriptions(
    @Param("trainerId") Long trainerId,
    Pageable pageable
);

@Query("""
    SELECT COUNT(s) FROM Subscription s
    JOIN s.customer c
    LEFT JOIN c.assignedTrainer t
    WHERE s.status = com.example.tradingpt.domain.subscription.enums.Status.ACTIVE
    AND (:trainerId IS NULL OR t.id = :trainerId)
    """)
Long countActiveSubscriptions(@Param("trainerId") Long trainerId);
```

**SubscriptionQueryServiceImpl.java** - 엔티티 조회 + bulk 쿼리 패턴
```java
@Override
public SubscriptionCustomerSliceResponseDTO getActiveSubscriptionCustomers(
    Long trainerId,
    Boolean myCustomersOnly,
    Pageable pageable
) {
    Long filterTrainerId = Boolean.TRUE.equals(myCustomersOnly) ? trainerId : null;

    // 1. 활성 구독 목록 조회 (ToOne fetch join으로 N+1 해결)
    Slice<Subscription> subscriptionSlice =
        subscriptionRepository.findActiveSubscriptions(filterTrainerId, pageable);

    // 2. 고객 ID 목록 추출
    List<Long> customerIds = subscriptionSlice.getContent().stream()
        .map(s -> s.getCustomer().getId())
        .distinct()
        .toList();

    // 3. 피드백 요청 수 bulk 조회 (N+1 방지)
    Map<Long, Long> feedbackCountMap = getFeedbackCountMap(customerIds);

    // 4. 엔티티 -> DTO 변환 (추가 쿼리 없음)
    List<SubscriptionCustomerResponseDTO> dtoList = subscriptionSlice.getContent().stream()
        .map(subscription -> SubscriptionCustomerResponseDTO.from(
            subscription,
            feedbackCountMap.getOrDefault(subscription.getCustomer().getId(), 0L)
        ))
        .toList();

    // 5. 결과 반환
    return SubscriptionCustomerSliceResponseDTO.from(
        new SliceImpl<>(dtoList, pageable, subscriptionSlice.hasNext()),
        subscriptionRepository.countActiveSubscriptions(filterTrainerId)
    );
}

private Map<Long, Long> getFeedbackCountMap(List<Long> customerIds) {
    if (customerIds.isEmpty()) {
        return Map.of();
    }
    return feedbackRequestRepository.countByCustomerIds(customerIds).stream()
        .collect(Collectors.toMap(
            row -> (Long) row[0],
            row -> (Long) row[1]
        ));
}
```

**SubscriptionCustomerResponseDTO.java** - from() 팩토리 메서드 추가
```java
/**
 * Subscription 엔티티로부터 DTO 생성
 * JPA Fetch Optimization 원칙: 엔티티 조회 후 서비스에서 DTO 변환
 *
 * @param subscription 구독 엔티티 (customer, assignedTrainer, uid fetch join 완료)
 * @param feedbackRequestCount 해당 고객의 피드백 요청 수
 */
public static SubscriptionCustomerResponseDTO from(Subscription subscription, Long feedbackRequestCount) {
    Customer customer = subscription.getCustomer();
    return SubscriptionCustomerResponseDTO.builder()
        .customerId(customer.getId())
        .name(customer.getUsername())
        .phoneNumber(customer.getPhoneNumber())
        .uid(customer.getUid() != null ? customer.getUid().getUid() : null)
        .trainerName(customer.getAssignedTrainer() != null ? customer.getAssignedTrainer().getName() : null)
        .feedbackRequestCount(feedbackRequestCount)
        .build();
}
```

**FeedbackRequestRepository.java** - bulk 쿼리 추가
```java
/**
 * 여러 고객의 피드백 요청 개수 일괄 조회 (N+1 방지)
 */
@Query("""
    SELECT fr.customer.id, COUNT(fr)
    FROM FeedbackRequest fr
    WHERE fr.customer.id IN :customerIds
    GROUP BY fr.customer.id
    """)
List<Object[]> countByCustomerIds(@Param("customerIds") List<Long> customerIds);
```

**삭제된 파일**:
- `SubscriptionRepositoryCustom.java` - 전체 삭제
- `SubscriptionRepositoryImpl.java` - 전체 삭제

#### 측정 결과

| 지표 | Before | After | 개선율 |
|------|--------|-------|--------|
| 쿼리 수 | N+1 발생 | **3개** | **95%+ 감소** |
| 파일 수 | 4개 (Custom, Impl, Service, DTO) | **2개** (Repository, Service) | **50% 감소** |
| 코드 복잡도 | QueryDSL 복잡 로직 | Spring Data JPA 명확 | **크게 개선** |

---

### 4차 최적화: p6spy 설정 추가

SQL 쿼리 모니터링을 위해 p6spy 설정을 추가했습니다.

#### build.gradle
```groovy
implementation 'com.github.gavlyukovskiy:p6spy-spring-boot-starter:1.9.2'  // SQL 로깅
```

#### application-local.yml (개발 환경)
```yaml
spring:
  jpa:
    show-sql: false  # p6spy가 대체하므로 비활성화
    properties:
      hibernate:
        format_sql: false  # p6spy가 대체

# p6spy 설정 (SQL 로깅 최적화)
decorator:
  datasource:
    p6spy:
      enable-logging: true
      multiline: true
      logging: slf4j

logging:
  level:
    p6spy: info  # p6spy SQL 로그 활성화
    org.hibernate.SQL: off  # p6spy가 대체
    org.hibernate.orm.jdbc.bind: off  # 파라미터 바인딩도 p6spy가 대체
```

#### application-prod.yml (운영 환경)
```yaml
# p6spy 비활성화 (운영 환경 - 성능 최적화)
decorator:
  datasource:
    p6spy:
      enable-logging: false  # 운영 환경에서는 비활성화
```

---

## 6. 최종 성과 및 비즈니스 임팩트

### 성능 개선 종합

| 지표 | Before | After | 개선율 | 목표 달성 |
|------|--------|-------|--------|-----------|
| **new-subscription 쿼리 수** | 101개 | 4개 | **96% 감소** | :white_check_mark: |
| **free-customers 쿼리 수** | 21개 | 2개 | **90% 감소** | :white_check_mark: |
| **subscriptions 쿼리 수** | N+1 | 3개 | **95%+ 감소** | :white_check_mark: |
| **커넥션 사용률** | 100% (고갈) | <50% | **50%+ 감소** | :white_check_mark: |
| **API 응답 시간** | 20초+ (타임아웃) | <2초 | **90%+ 감소** | :white_check_mark: |
| **코드 라인 수** | 복잡한 QueryDSL | 간결한 JPQL | **50%+ 감소** | :white_check_mark: |

### 코드 품질 개선

| 항목 | Before | After | 개선 |
|------|--------|-------|------|
| **QueryDSL Custom 파일** | 4개 | 0개 | **제거** |
| **Repository 메서드** | 복잡한 DTO 직접 조회 | 엔티티 조회 + 서비스 변환 | **표준 패턴** |
| **DTO 팩토리 메서드** | Service에서 직접 빌드 | DTO.from() 정적 메서드 | **책임 분리** |
| **테스트 용이성** | 복잡한 의존성 | 명확한 의존성 | **향상** |

### 비즈니스 임팩트

| 지표 | Before | After | 개선 |
|------|--------|-------|------|
| **시스템 가용성** | 대시보드 접속 시 장애 | 정상 운영 | **100%** |
| **관리자 업무** | 전면 중단 | 정상 수행 | **복구** |
| **런칭 리스크** | 높음 | 낮음 | **제거** |

---

## 7. 지속 가능성 및 모니터링

### JPA Fetch Optimization 핵심 원칙 정립

```
1. N쪽에서 시작: Subscription에서 시작하면 모든 연관관계가 ToOne이 됨
2. ToOne 관계: fetch join 사용 (row 수 증가 없음)
3. ToMany 관계: fetch join 금지, @BatchSize 또는 배치 쿼리 사용
4. 엔티티 조회 후 DTO 변환: DTO 직접 조회는 최후의 수단
5. 배치 쿼리: IN 절로 여러 건 한 번에 조회하여 N+1 방지
```

### 코드 리뷰 체크리스트

N+1 방지를 위한 코드 리뷰 시 확인 사항:

- [ ] 루프 안에서 Repository 메서드를 호출하고 있지 않은가?
- [ ] ToOne 관계에 fetch join이 적용되어 있는가?
- [ ] ToMany 관계를 fetch join하여 페이징을 방해하고 있지 않은가?
- [ ] OneToOne 관계(특히 uid)에 fetch join이 누락되지 않았는가?
- [ ] 배치 쿼리(IN 절)로 N+1을 방지할 수 있는 부분이 있는가?
- [ ] DTO에 from() 정적 팩토리 메서드가 있는가?

### 모니터링 전략

#### p6spy를 통한 쿼리 모니터링 (local, dev 환경)
- SQL 쿼리 수 확인
- 실행 시간 측정
- 파라미터 바인딩 확인

#### CloudWatch 알람 설정
- HikariCP 커넥션 사용률 80% 초과 시 알람
- API 응답 시간 5초 초과 시 알람

---

## 8. 테스트 검증 결과

### 8.1 최적화 전 상태 (Before)
```
[Admin 대시보드 접속 시나리오]
1. Admin이 대시보드에 접속
2. 10개 이상의 API 동시 호출
3. new-subscription-customers에서 N+1 발생

[실제 결과]
- 쿼리 수: 101개 (20명 조회 시)
- 커넥션 상태: total=10, active=10, idle=0, waiting=15
- 결과: 20초 타임아웃, 전체 장애
```

### 8.2 최적화 후 상태 (After)
```
[동일 시나리오 테스트]
1. Admin이 대시보드에 접속
2. 10개 이상의 API 동시 호출
3. 모든 API 정상 응답

[실제 결과]
- 쿼리 수: 4개 (20명 조회 시)
- 커넥션 상태: total=30, active=5, idle=25, waiting=0
- 결과: ~1초 내 응답, 정상 동작
```

### 8.3 테스트 커버리지

| 테스트 유형 | 테스트 케이스 | 결과 | 비고 |
|------------|--------------|------|------|
| 기능 테스트 | new-subscription-customers API | Pass | 기존 응답 스펙 유지 |
| 기능 테스트 | free-customers API | Pass | 기존 응답 스펙 유지 |
| 기능 테스트 | subscriptions/customers API | Pass | 기존 응답 스펙 유지 |
| 쿼리 테스트 | p6spy로 쿼리 수 확인 | Pass | 4개 이하 |
| 부하 테스트 | 대시보드 동시 접속 | Pass | 커넥션 고갈 없음 |

### 8.4 검증 체크리스트

- [x] 모든 대상 API N+1 해결 확인
- [x] 기존 API 응답 스펙 호환성 유지
- [x] 코드 리뷰 완료
- [x] p6spy로 쿼리 수 검증
- [x] 대시보드 동시 접속 테스트 통과

---

## 9. 면접 Q&A

### Q1. 이 문제를 어떻게 발견하고 분석했나요?

**A**: CloudWatch 로그에서 HikariCP 커넥션 타임아웃 경고를 발견했습니다. 로그의 `(total=10, active=10, idle=0, waiting=15)` 패턴을 통해 커넥션 풀 고갈 상황임을 즉시 파악했습니다.

이후 p6spy를 활성화하여 쿼리 로그를 분석한 결과, Admin 대시보드 API에서 고객 20명 조회 시 101개의 쿼리가 실행되는 것을 확인했습니다. 코드를 분석해보니 Customer에서 시작하여 연관 엔티티를 개별 조회하는 전형적인 N+1 패턴이 있었습니다.

**포인트**:
- 로그 기반 문제 인지 (HikariCP 상태 메트릭 활용)
- p6spy를 통한 쿼리 분석
- N+1 쿼리 패턴 식별 능력

---

### Q2. N+1 문제를 해결한 핵심 전략은 무엇인가요?

**A**: 핵심 전략은 **"N쪽에서 시작하면 모든 연관관계가 ToOne이 된다"**는 JPA Fetch Optimization 원칙을 적용한 것입니다.

기존에는 Customer(1쪽)에서 시작하여 Subscription(N쪽)을 조회했는데, 이렇게 하면 Subscription이 ToMany 관계가 되어 fetch join 시 row가 증가하고 페이징이 불가능합니다.

반대로 Subscription(N쪽)에서 시작하면:
- Customer: ManyToOne (fetch join OK)
- assignedTrainer: ManyToOne (fetch join OK)
- uid: OneToOne (fetch join OK)

모든 연관관계가 ToOne이 되어 한 번의 쿼리로 모든 데이터를 가져올 수 있습니다.

추가로, 같은 Customer가 여러 Subscription을 가질 수 있으므로 LinkedHashMap을 사용하여 Customer 기준 중복을 제거하면서 순서를 유지했습니다.

**포인트**:
- JPA 연관관계 방향에 따른 fetch join 가능 여부 이해
- ToOne vs ToMany 관계의 차이점
- LinkedHashMap을 이용한 중복 제거 및 순서 유지

---

### Q3. QueryDSL 대신 Spring Data JPA를 선택한 이유는?

**A**: 세 가지 이유로 Spring Data JPA JPQL을 선택했습니다:

1. **단순성**: 해당 쿼리들은 동적 조건이 복잡하지 않았습니다. trainerId가 null인지 아닌지 정도의 단순 조건이라 JPQL의 `:trainerId IS NULL OR t.id = :trainerId` 패턴으로 충분히 처리 가능했습니다.

2. **유지보수성**: QueryDSL Custom Repository는 4개의 파일(Custom 인터페이스, Impl 구현체, DTO, Service)을 관리해야 하지만, JPQL은 Repository에 메서드 하나만 추가하면 됩니다.

3. **DTO 직접 조회의 단점**: QueryDSL로 DTO를 직접 조회하면 연관 엔티티 접근 시 N+1이 발생할 수 있습니다. 엔티티를 조회한 후 서비스에서 DTO로 변환하는 패턴이 더 안전합니다.

**포인트**:
- 상황에 맞는 기술 선택 (적정 기술)
- 유지보수성과 복잡도의 트레이드오프
- DTO 직접 조회 vs 엔티티 조회 후 변환의 장단점

---

### Q4. 배치 쿼리 패턴을 적용한 이유는?

**A**: 모든 연관 데이터를 fetch join으로 가져올 수 없는 경우가 있습니다. 예를 들어 LevelTestAttempt나 Consultation은 Customer와 별도의 테이블이고, 단순히 "존재 여부"나 "개수"만 필요한 경우입니다.

이런 경우 배치 쿼리 패턴을 적용했습니다:

```java
// 1. 고객 ID 목록 추출
List<Long> customerIds = customers.stream().map(Customer::getId).toList();

// 2. IN 절로 한 번에 조회
List<LevelTestAttempt> allAttempts = leveltestAttemptRepository.findByCustomerIdIn(customerIds);

// 3. 메모리에서 그룹핑
Map<Long, List<LevelTestAttempt>> attemptsByCustomerId = allAttempts.stream()
    .collect(Collectors.groupingBy(a -> a.getCustomer().getId()));

// 4. DTO 조립 시 Map에서 조회 (추가 쿼리 없음)
```

이 패턴으로 N개의 쿼리를 1개로 줄일 수 있습니다.

**포인트**:
- IN 절을 이용한 배치 조회
- 메모리에서 그룹핑하여 N+1 방지
- fetch join이 불가능한 경우의 대안

---

### Q5. 이 경험에서 배운 점과 재발 방지 대책은?

**A**: 세 가지 핵심 교훈을 얻었습니다:

1. **JPA 관계 방향이 성능을 결정한다**: 어느 엔티티에서 시작하느냐에 따라 ToOne/ToMany가 달라지고, 이것이 fetch join 가능 여부와 N+1 발생 여부를 결정합니다.

2. **p6spy는 필수**: 개발 환경에서 p6spy를 항상 활성화하여 실제 쿼리 수를 확인해야 합니다. 기능은 정상 동작하지만 100개의 쿼리가 실행되고 있을 수 있습니다.

3. **엔티티 조회 후 DTO 변환**: DTO 직접 조회(QueryDSL Projections)는 연관 엔티티 접근 시 N+1을 유발할 수 있습니다. 엔티티를 조회한 후 서비스에서 DTO로 변환하는 것이 더 안전합니다.

**재발 방지 대책**:
- PR 체크리스트에 "N+1 쿼리 확인" 항목 추가
- p6spy 로그로 새 API의 쿼리 수 검증 필수화
- JPA Fetch Optimization 원칙 문서화 및 팀 공유

**포인트**:
- 구체적인 기술적 교훈
- 프로세스 개선 방안
- 재발 방지를 위한 자동화/문서화

---

## 핵심 교훈 (Key Takeaways)

### 1. N쪽에서 시작하면 모든 연관관계가 ToOne이 된다

- **문제**: Customer(1)에서 시작하면 Subscription이 ToMany, fetch join 불가
- **교훈**: Subscription(N)에서 시작하면 모든 연관관계가 ToOne, fetch join 가능
- **적용**: 조회 시작점을 신중히 선택, N쪽에서 시작 고려

### 2. 엔티티 조회 후 DTO 변환이 더 안전하다

- **문제**: QueryDSL DTO 직접 조회 시 연관 엔티티 접근에서 N+1 발생
- **교훈**: 엔티티를 조회한 후 서비스에서 DTO로 변환하면 fetch join 제어 용이
- **적용**: DTO.from(Entity) 정적 팩토리 메서드 패턴 사용

### 3. 배치 쿼리로 N+1을 방지할 수 있다

- **문제**: 루프 안에서 개별 조회하면 N+1 발생
- **교훈**: IN 절로 한 번에 조회 후 메모리에서 그룹핑
- **적용**: `findByCustomerIdIn(List<Long> ids)` 패턴 활용

### 4. p6spy로 쿼리 수를 확인해야 한다

- **문제**: 기능은 동작하지만 100개 쿼리가 실행될 수 있음
- **교훈**: 개발 환경에서 항상 p6spy 활성화
- **적용**: local, dev 환경에 p6spy 설정, prod에서는 비활성화

---

## 관련 문서

- [HikariCP 커넥션 풀 고갈 - 스케줄러](./2025-12-06_CONNECTION_POOL_EXHAUSTION.md)
- [HikariCP 커넥션 풀 고갈 - Admin 대시보드](./2025-12-HIKARICP_CONNECTION_POOL_EXHAUSTION.md)
- [부하 테스트 성능 분석](./2025-12-06-load-test-performance-analysis.md)

---

## 변경된 파일 목록

### 신규/수정 파일

| 파일 | 변경 내용 |
|------|----------|
| `SubscriptionRepository.java` | `findNewSubscriptionsWithCustomerDetails()`, `findActiveSubscriptions()` 추가 |
| `CustomerRepository.java` | `findFreeCustomers()`, `countFreeCustomers()` JPQL 추가 |
| `CustomerQueryServiceImpl.java` | Subscription 기반 조회, LinkedHashMap 중복 제거, 배치 쿼리 |
| `SubscriptionQueryServiceImpl.java` | 엔티티 조회 후 DTO 변환, bulk 쿼리 적용 |
| `SubscriptionCustomerResponseDTO.java` | `from(Subscription, Long)` 팩토리 메서드 추가 |
| `LeveltestAttemptRepository.java` | `findByCustomerIdIn()` 배치 쿼리 추가 |
| `ConsultationRepository.java` | `findCustomerIdsWithConsultation()` 배치 쿼리 추가 |
| `FeedbackRequestRepository.java` | `countByCustomerIds()` 배치 쿼리 추가 |
| `build.gradle` | p6spy 의존성 추가 |
| `application-local.yml` | p6spy 설정 추가 |
| `application-dev.yml` | p6spy 설정 추가 |
| `application-prod.yml` | p6spy 비활성화 설정 |

### 삭제된 파일

| 파일 | 삭제 이유 |
|------|----------|
| `SubscriptionRepositoryCustom.java` | Spring Data JPA JPQL로 대체 |
| `SubscriptionRepositoryImpl.java` | Spring Data JPA JPQL로 대체 |

---

**작성자**: Trading PT Backend Team
**최종 수정일**: 2025년 12월 09일
**버전**: 1.0.0
