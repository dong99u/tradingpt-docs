# HikariCP 커넥션 풀 설정 가이드

## 목차

1. [개요](#1-개요)
2. [커넥션 풀 크기 설정](#2-커넥션-풀-크기-설정)
3. [타임아웃 설정](#3-타임아웃-설정)
4. [모니터링 및 디버깅 설정](#4-모니터링-및-디버깅-설정)
5. [환경별 설정 예시](#5-환경별-설정-예시)
6. [커넥션 풀 크기 산정 가이드](#6-커넥션-풀-크기-산정-가이드)
7. [트러블슈팅 가이드](#7-트러블슈팅-가이드)
8. [참고 자료](#8-참고-자료)

---

## 1. 개요

### 1.1 커넥션 풀이란?

데이터베이스 커넥션은 생성하는 데 비용이 많이 드는 리소스입니다. 매번 요청마다 새로운 커넥션을 만들고 닫는 것은 마치 **매번 물을 마실 때마다 새 컵을 사고 버리는 것**과 같습니다.

```
[커넥션 풀 없이]
요청 1 → 커넥션 생성(100ms) → 쿼리 실행(10ms) → 커넥션 종료(10ms) = 총 120ms
요청 2 → 커넥션 생성(100ms) → 쿼리 실행(10ms) → 커넥션 종료(10ms) = 총 120ms
...

[커넥션 풀 사용]
시작 시 → 커넥션 10개 미리 생성
요청 1 → 풀에서 커넥션 가져옴(1ms) → 쿼리 실행(10ms) → 풀에 반환(1ms) = 총 12ms
요청 2 → 풀에서 커넥션 가져옴(1ms) → 쿼리 실행(10ms) → 풀에 반환(1ms) = 총 12ms
...
```

**커넥션 풀**은 미리 만들어 둔 데이터베이스 커넥션을 담아두는 "수영장(Pool)"입니다. 애플리케이션이 데이터베이스에 접근할 때마다 풀에서 커넥션을 빌려 사용하고, 다 쓰면 반환합니다.

### 1.2 왜 HikariCP인가?

HikariCP는 현재 가장 빠르고 가벼운 Java 커넥션 풀 라이브러리입니다.

| 특징 | 설명 |
|------|------|
| **속도** | 다른 풀 대비 2-3배 빠른 성능 |
| **메모리** | 최소한의 메모리 사용 (약 130KB) |
| **안정성** | 바이트코드 수준 최적화, 검증된 안정성 |
| **Spring Boot 기본** | Spring Boot 2.0부터 기본 커넥션 풀 |

### 1.3 커넥션 생명주기 이해하기

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        HikariCP 커넥션 생명주기                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   [생성] ──────────────────────────────────────────────────────────┐    │
│     │                                                              │    │
│     ▼                                                              │    │
│   ┌─────────────────┐    connection-timeout    ┌───────────────┐   │    │
│   │   커넥션 풀    │ ◄─────(대기)────────────── │  애플리케이션  │   │    │
│   │  (Pool)        │ ─────(획득)──────────────► │   (사용 중)   │   │    │
│   └─────────────────┘                          └───────────────┘   │    │
│     │         │                                        │           │    │
│     │         │                                        │           │    │
│     │    idle-timeout                              반환           │    │
│     │    (유휴 시간 초과 시)                          │           │    │
│     │         │                                        │           │    │
│     │         ▼                                        │           │    │
│     │    ┌─────────┐                                   │           │    │
│     │    │  제거   │ ◄────────────────────────────────┘           │    │
│     │    └─────────┘                                               │    │
│     │                                                              │    │
│     └── max-lifetime (최대 수명 도달 시) ──────────────────────────┘    │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 커넥션 풀 크기 설정

### 2.1 maximum-pool-size

**커넥션 풀이 가질 수 있는 최대 커넥션 수**입니다. 활성(active) + 유휴(idle) 커넥션의 총합이 이 값을 넘지 않습니다.

| 항목 | 값 |
|------|------|
| **기본값** | 10 |
| **권장값 (개발)** | 10-20 |
| **권장값 (운영)** | 20-50 (트래픽에 따라) |

#### 비유로 이해하기

음식점의 테이블 수와 같습니다:
- 테이블이 너무 적으면 → 손님이 대기해야 함 (요청 지연)
- 테이블이 너무 많으면 → 관리 비용 증가, 공간 낭비

#### 값이 너무 작을 때 발생하는 문제

```
[상황] maximum-pool-size: 5, 동시 요청: 20개

요청 1-5: 커넥션 획득 성공, 쿼리 실행 중...
요청 6-20: "커넥션 없음! connection-timeout까지 대기..."
         ↓
         connection-timeout 초과 시
         ↓
         SQLTransientConnectionException: Connection is not available
```

#### 값이 너무 클 때 발생하는 문제

```
[상황] maximum-pool-size: 500

문제 1: 데이터베이스 과부하
  - MySQL max_connections 초과 가능
  - DB 서버 메모리 부족

문제 2: 컨텍스트 스위칭 오버헤드
  - 너무 많은 커넥션은 오히려 성능 저하

문제 3: 장애 전파
  - 모든 커넥션이 느린 쿼리에 묶이면 전체 장애
```

#### 현재 프로젝트 설정

```yaml
# application.yml (공통)
maximum-pool-size: 20

# application-dev.yml (개발)
maximum-pool-size: 30  # RDS db.t4g.medium (max_connections ~340) 대응

# application-prod.yml (운영)
maximum-pool-size: 25  # RDS db.r6g.large (max_connections ~1,365) 대응
                       # 6 EC2 x 25 = 150 커넥션 (RDS 용량의 11%)
```

---

### 2.2 minimum-idle

**풀이 유지하려는 최소 유휴(idle) 커넥션 수**입니다. 트래픽이 없을 때도 이 정도는 미리 준비해 둡니다.

| 항목 | 값 |
|------|------|
| **기본값** | maximum-pool-size와 동일 |
| **권장값 (개발)** | 2-5 |
| **권장값 (운영)** | maximum-pool-size의 30-50% |

#### 비유로 이해하기

"대기 중인 택시 수"와 같습니다:
- 너무 적으면 → 갑자기 손님 몰릴 때 택시 부족
- 너무 많으면 → 기름값 낭비 (불필요한 리소스 점유)

#### 설정 전략

```
[트래픽 패턴에 따른 설정]

1. 트래픽이 일정한 서비스:
   minimum-idle = maximum-pool-size (기본값 사용)

2. 트래픽 변동이 큰 서비스:
   minimum-idle = maximum-pool-size의 30-50%
   → 유휴 시간에 리소스 절약

3. 급격한 스파이크가 있는 서비스:
   minimum-idle 높게 + idle-timeout 길게
   → 스파이크 대비
```

#### 현재 프로젝트 설정

```yaml
# application.yml (공통)
minimum-idle: 5

# application-dev.yml (개발)
minimum-idle: 10  # 개발 테스트 시 빠른 응답 보장

# application-prod.yml (운영)
minimum-idle: 10  # 25개 중 10개는 항상 준비
```

---

## 3. 타임아웃 설정

### 3.1 connection-timeout

**풀에서 커넥션을 기다리는 최대 시간**입니다. 이 시간 안에 커넥션을 얻지 못하면 예외가 발생합니다.

| 항목 | 값 |
|------|------|
| **기본값** | 30000ms (30초) |
| **최소값** | 250ms |
| **권장값 (개발)** | 10000-20000ms |
| **권장값 (운영)** | 20000-30000ms |

#### 비유로 이해하기

"음식점에서 빈 테이블을 기다리는 시간"입니다:
- 너무 짧으면 → 조금만 기다리면 되는데 포기하고 나감
- 너무 길면 → 문제가 있는데도 계속 기다림 (자원 낭비)

#### 값 설정 시 고려사항

```
[짧게 설정할 때 (5-10초)]
장점:
  - 빠른 실패 (Fail Fast)
  - 장애 전파 방지 (cascading failure 예방)
  - 사용자에게 빠른 피드백

단점:
  - 일시적 부하에 취약
  - 정상적인 상황에서도 실패 가능

[길게 설정할 때 (30-60초)]
장점:
  - 일시적 부하를 버틸 수 있음
  - 안정적인 서비스

단점:
  - 장애 시 느린 대응
  - 리소스를 오래 점유
```

#### 현재 프로젝트 설정

```yaml
# application.yml (공통)
connection-timeout: 10000  # 10초 - 빠른 실패로 cascading failure 방지

# application-dev.yml (개발)
connection-timeout: 20000  # 20초 - 디버깅 시간 고려

# application-prod.yml (운영)
connection-timeout: 20000  # 20초 - 안정성과 빠른 실패 균형
```

---

### 3.2 idle-timeout

**유휴 커넥션이 풀에서 제거되기까지의 시간**입니다. minimum-idle보다 많은 유휴 커넥션만 해당됩니다.

| 항목 | 값 |
|------|------|
| **기본값** | 600000ms (10분) |
| **최소값** | 10000ms (10초) |
| **권장값** | 300000ms (5분) |

#### 비유로 이해하기

"대기 중인 택시가 떠나는 시간"입니다:
- 승객(요청)이 없는 택시는 일정 시간 후 차고로 복귀
- 단, 최소 대기 택시(minimum-idle)는 항상 유지

#### 동작 방식

```
[예시: maximum-pool-size=10, minimum-idle=5, idle-timeout=300000]

상황: 현재 풀에 유휴 커넥션 8개

5분(idle-timeout) 후:
  - 8개 중 3개 제거 (8 - 5 = 3)
  - minimum-idle 5개는 유지

결과: 유휴 커넥션 5개로 조정
```

#### 주의사항

```
⚠️ idle-timeout은 반드시 max-lifetime보다 작아야 합니다.

잘못된 설정:
  idle-timeout: 1800000    # 30분
  max-lifetime: 600000     # 10분 ← 더 작음!

결과: idle-timeout이 적용되기 전에 max-lifetime에 의해 커넥션이 제거됨
      → idle-timeout 설정이 무의미해짐
```

#### 현재 프로젝트 설정

```yaml
# 모든 환경 동일
idle-timeout: 300000  # 5분 - 적절한 리소스 관리
```

---

### 3.3 max-lifetime

**커넥션이 풀에 존재할 수 있는 최대 시간**입니다. 이 시간이 지나면 사용 중이 아닌 커넥션은 제거됩니다.

| 항목 | 값 |
|------|------|
| **기본값** | 1800000ms (30분) |
| **최소값** | 30000ms (30초) |
| **권장값 (AWS RDS)** | 1800000ms (30분) |

#### 왜 max-lifetime이 필요한가?

```
[문제 상황: 오래된 커넥션]

1. 데이터베이스 서버 재시작
   → 기존 커넥션 무효화
   → 애플리케이션은 모름
   → "Stale connection" 에러

2. 네트워크 장비 타임아웃
   → 방화벽, 로드밸런서가 오래된 커넥션 끊음
   → "Connection reset" 에러

3. 메모리 누수 방지
   → 커넥션 관련 리소스 주기적 정리
```

#### AWS RDS 환경에서의 설정

```
[AWS RDS wait_timeout과의 관계]

AWS RDS MySQL wait_timeout 기본값: 28800초 (8시간)

권장 설정:
  max-lifetime < wait_timeout

이유:
  - wait_timeout보다 먼저 커넥션을 갱신해야
  - DB에서 강제로 끊기기 전에 깨끗하게 정리

실무 권장값:
  max-lifetime: 1800000 (30분)
  → 충분히 짧아서 안전하고, 너무 짧지 않아 오버헤드 최소화
```

#### 현재 프로젝트 설정

```yaml
# application.yml (공통)
max-lifetime: 1200000   # 20분

# application-dev.yml (개발)
max-lifetime: 1800000   # 30분 (RDS 권장)

# application-prod.yml (운영)
max-lifetime: 1800000   # 30분 (RDS 권장)
```

---

### 3.4 validation-timeout

**커넥션 유효성 검사의 최대 시간**입니다. 풀에서 커넥션을 꺼낼 때 실제로 사용 가능한지 확인하는 시간입니다.

| 항목 | 값 |
|------|------|
| **기본값** | 5000ms (5초) |
| **최소값** | 250ms |
| **권장값** | 5000ms (5초) |

#### 동작 방식

```
[커넥션 획득 과정]

1. 애플리케이션: "커넥션 주세요"
2. HikariCP: 풀에서 유휴 커넥션 선택
3. HikariCP: 커넥션 유효성 검사 (SELECT 1 또는 isValid())
     └─ validation-timeout 내에 완료해야 함
4. 검사 통과: 커넥션 제공
   검사 실패: 해당 커넥션 제거, 다른 커넥션으로 재시도
```

#### 주의사항

```
⚠️ validation-timeout < connection-timeout 이어야 합니다.

connection-timeout = 20000ms (20초)
validation-timeout = 5000ms (5초)

→ 유효성 검사에 5초, 나머지 15초 동안 다른 커넥션 시도 가능
```

#### 현재 프로젝트 설정

```yaml
# 모든 환경 동일
validation-timeout: 5000  # 5초 - 기본값 사용
```

---

### 3.5 keepalive-time

**유휴 커넥션에 주기적으로 "살아있니?" 신호를 보내는 간격**입니다.

| 항목 | 값 |
|------|------|
| **기본값** | 0 (비활성화) |
| **최소값** | 30000ms (30초) |
| **권장값 (AWS RDS)** | 0 (비활성화) |

#### AWS RDS에서 0으로 설정하는 이유

```
[문제 상황: keepalive-time 활성화 시]

1. HikariCP가 주기적으로 SELECT 1 쿼리 전송
2. AWS RDS Proxy/Aurora 환경에서 문제 발생:
   - 예상치 못한 커넥션 상태 변경
   - 커넥션 풀 불안정
   - 간헐적인 "Communications link failure"

[해결책]
keepalive-time: 0  # 비활성화

대신 다음으로 커넥션 상태 관리:
- max-lifetime: 주기적 커넥션 갱신
- validation-timeout: 사용 전 유효성 검사
- JDBC URL에 tcpKeepAlive=true: TCP 레벨 keep-alive
```

#### 현재 프로젝트 설정

```yaml
# 모든 환경 동일
keepalive-time: 0  # 비활성화 (RDS 환경 최적화)

# JDBC URL에서 TCP keep-alive 활성화
url: jdbc:mysql://...?tcpKeepAlive=true...
```

---

## 4. 모니터링 및 디버깅 설정

### 4.1 leak-detection-threshold

**커넥션 누수를 감지하는 임계 시간**입니다. 커넥션을 빌려간 후 이 시간이 지나도 반환하지 않으면 경고 로그를 남깁니다.

| 항목 | 값 |
|------|------|
| **기본값** | 0 (비활성화) |
| **최소값** | 2000ms (2초) |
| **권장값 (개발)** | 60000-120000ms |
| **권장값 (운영)** | 30000-60000ms |

#### 커넥션 누수란?

```java
// 커넥션 누수 예시
public void leakyMethod() {
    Connection conn = dataSource.getConnection();

    // 쿼리 실행...

    // ❌ 문제: 예외 발생 시 conn.close() 호출 안 됨!
    if (someCondition) {
        throw new RuntimeException("Oops!");
    }

    conn.close();  // 여기까지 도달 못함
}

// 올바른 방법: try-with-resources
public void safeMethod() {
    try (Connection conn = dataSource.getConnection()) {
        // 쿼리 실행...
        // 예외가 발생해도 자동으로 close() 호출
    }
}
```

#### Spring에서의 누수 방지

```java
// Spring @Transactional 사용 시
// → Spring이 자동으로 커넥션 관리
// → 누수 가능성 낮음

@Transactional
public void safeMethod() {
    // JPA 사용
    // 트랜잭션 종료 시 자동으로 커넥션 반환
}
```

#### 누수 감지 로그 예시

```
[WARN] Connection leak detection triggered for connection com.mysql.cj.jdbc.ConnectionImpl@1a2b3c4d,
       stack trace follows:
    at com.example.service.UserService.findAll(UserService.java:45)
    at com.example.controller.UserController.list(UserController.java:23)
    ...
```

#### N+1 쿼리와 오탐 방지

```
[상황: N+1 쿼리로 인한 긴 트랜잭션]

User 100명 조회 + 각 User의 Orders 조회
→ 총 101개 쿼리
→ 트랜잭션이 오래 걸림
→ leak-detection-threshold 경고 발생 (오탐)

[해결책]
1. N+1 쿼리 해결 (근본적 해결)
   - Fetch Join 사용
   - @EntityGraph 사용

2. 임계값 조정 (임시 방편)
   - leak-detection-threshold: 120000 (2분)
```

#### 현재 프로젝트 설정

```yaml
# application.yml (공통)
leak-detection-threshold: 30000   # 30초 - 빠른 감지

# application-dev.yml (개발)
leak-detection-threshold: 120000  # 2분 - N+1 쿼리 오탐 방지

# application-prod.yml (운영)
leak-detection-threshold: 60000   # 1분 - 적절한 감지
```

---

### 4.2 pool-name

**커넥션 풀의 이름**입니다. 로그와 JMX에서 풀을 식별하는 데 사용됩니다.

| 항목 | 값 |
|------|------|
| **기본값** | HikariPool-1, HikariPool-2, ... |
| **권장값** | 환경을 구분할 수 있는 이름 |

#### 왜 이름이 중요한가?

```
[pool-name 없이 로그 분석]
HikariPool-1 - Connection not available...
HikariPool-1 - Pool stats (total=10, active=10, idle=0)

→ 어떤 서비스? 어떤 환경?

[pool-name 설정 후]
HikariCP-Prod - Connection not available...
HikariCP-Prod - Pool stats (total=25, active=25, idle=0)

→ 운영 환경 문제임을 즉시 파악!
```

#### 현재 프로젝트 설정

```yaml
# application-dev.yml
pool-name: HikariCP-Dev

# application-prod.yml
pool-name: HikariCP-Prod
```

---

## 5. 환경별 설정 예시

### 5.1 개발 환경 (Dev)

```yaml
spring:
  datasource:
    url: jdbc:mysql://${DEV_DB_HOST}:3306/${DEV_DB_NAME}?serverTimezone=Asia/Seoul&characterEncoding=UTF-8&tcpKeepAlive=true&connectTimeout=10000&socketTimeout=60000
    hikari:
      # 풀 식별
      pool-name: HikariCP-Dev

      # 풀 크기 (db.t4g.medium: max_connections ~340)
      maximum-pool-size: 30     # 개발자 다수 동시 테스트 대응
      minimum-idle: 10          # 빠른 응답 보장

      # 타임아웃
      connection-timeout: 20000 # 20초 - 디버깅 여유
      idle-timeout: 300000      # 5분
      max-lifetime: 1800000     # 30분 (RDS 권장)

      # 커넥션 상태 관리
      keepalive-time: 0         # RDS 환경 최적화
      validation-timeout: 5000  # 5초

      # 디버깅
      leak-detection-threshold: 120000  # 2분 - 긴 트랜잭션 허용
```

#### 개발 환경 설정 근거

| 설정 | 값 | 이유 |
|------|------|------|
| maximum-pool-size | 30 | 여러 개발자 동시 테스트 |
| minimum-idle | 10 | API 호출 시 즉각 응답 |
| connection-timeout | 20초 | 디버깅 중 대기 시간 확보 |
| leak-detection-threshold | 2분 | N+1 쿼리, 긴 디버깅 세션 허용 |

---

### 5.2 운영 환경 (Prod)

```yaml
spring:
  datasource:
    url: jdbc:mysql://${PROD_DB_HOST}:3306/${PROD_DB_NAME}?serverTimezone=Asia/Seoul&characterEncoding=UTF-8&useSSL=true&requireSSL=true&tcpKeepAlive=true&connectTimeout=10000&socketTimeout=60000
    hikari:
      # 풀 식별
      pool-name: HikariCP-Prod

      # 풀 크기 (db.r6g.large: max_connections ~1,365)
      # 6 EC2 x 25 = 150 커넥션 (RDS 용량의 11%)
      maximum-pool-size: 25
      minimum-idle: 10

      # 타임아웃
      connection-timeout: 20000 # 20초
      idle-timeout: 300000      # 5분
      max-lifetime: 1800000     # 30분 (RDS 권장)

      # 커넥션 상태 관리
      keepalive-time: 0         # RDS 환경 최적화
      validation-timeout: 5000  # 5초

      # 모니터링
      leak-detection-threshold: 60000  # 1분 - 빠른 누수 감지
```

#### 운영 환경 설정 근거

| 설정 | 값 | 이유 |
|------|------|------|
| maximum-pool-size | 25 | 다중 인스턴스 고려 (6 x 25 = 150) |
| minimum-idle | 10 | 안정적인 응답 시간 보장 |
| max-lifetime | 30분 | RDS 권장값, 안정성 |
| leak-detection-threshold | 1분 | 운영 중 빠른 문제 감지 |

---

### 5.3 로컬 환경 (Local)

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/${LOCAL_DB_NAME}?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    hikari:
      pool-name: HikariCP-Local

      # 작은 풀 크기 (단일 개발자)
      maximum-pool-size: 10
      minimum-idle: 2

      # 느슨한 타임아웃 (디버깅 친화적)
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000

      # 엄격한 누수 감지 (개발 중 버그 발견)
      leak-detection-threshold: 30000
```

---

## 6. 커넥션 풀 크기 산정 가이드

### 6.1 기본 공식

```
최적 커넥션 수 = (코어 수 * 2) + 유효 스핀들 수

예시:
  - 4코어 서버: (4 * 2) + 1 = 9개
  - 8코어 서버: (8 * 2) + 1 = 17개
```

하지만 이 공식은 **CPU 바운드** 작업 기준입니다. 실제로는 다양한 요소를 고려해야 합니다.

### 6.2 실무에서의 산정 방법

```
[단계 1: RDS max_connections 확인]

AWS RDS의 max_connections 공식:
  max_connections = {DBInstanceClassMemory/12582880}

인스턴스별 예시:
  - db.t4g.micro (1GB): ~85
  - db.t4g.medium (4GB): ~340
  - db.r6g.large (16GB): ~1,365
  - db.r6g.xlarge (32GB): ~2,730
```

```
[단계 2: 인스턴스 수 고려]

사용 가능 커넥션 = max_connections * 0.8 (20% 여유)

각 인스턴스당 커넥션 = 사용 가능 커넥션 / 인스턴스 수

예시 (현재 프로젝트):
  - RDS: db.r6g.large (max_connections ~1,365)
  - 사용 가능: 1,365 * 0.8 = 1,092
  - EC2 인스턴스: 6대
  - 인스턴스당: 1,092 / 6 = 182개
  - 설정값: 25개 (충분한 여유)
```

```
[단계 3: 트래픽 패턴 분석]

측정 항목:
  - 동시 요청 수 (피크 시간대)
  - 평균 트랜잭션 시간
  - 초당 요청 수 (RPS)

공식:
  필요 커넥션 = RPS * 평균 응답 시간(초)

예시:
  - RPS: 100
  - 평균 응답 시간: 0.1초
  - 필요 커넥션: 100 * 0.1 = 10개
  - 여유분(2배): 20개
```

### 6.3 다중 인스턴스 환경 계산

```
┌──────────────────────────────────────────────────────────────────────────┐
│                    다중 인스턴스 커넥션 분배                              │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│                        RDS (db.r6g.large)                                │
│                       max_connections: 1,365                             │
│                                                                          │
│                    ┌──────────────────────┐                              │
│                    │     사용 가능 풀      │                              │
│                    │   1,365 * 80% = 1,092│                              │
│                    └──────────────────────┘                              │
│                              │                                           │
│          ┌─────────┬────────┴────────┬─────────┐                         │
│          ▼         ▼                  ▼         ▼                         │
│     ┌────────┐ ┌────────┐        ┌────────┐ ┌────────┐                   │
│     │ EC2 #1│ │ EC2 #2│  ...   │ EC2 #5│ │ EC2 #6│                   │
│     │  25개 │ │  25개 │        │  25개 │ │  25개 │                   │
│     └────────┘ └────────┘        └────────┘ └────────┘                   │
│                                                                          │
│     총 사용: 6 x 25 = 150개 (RDS 용량의 13.7%)                           │
│     여유분: 1,092 - 150 = 942개                                          │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### 6.4 권장 설정 매트릭스

| 트래픽 수준 | 인스턴스당 maximum-pool-size | minimum-idle |
|-------------|------------------------------|--------------|
| 소규모 (RPS < 50) | 10-15 | 3-5 |
| 중규모 (RPS 50-200) | 15-30 | 5-10 |
| 대규모 (RPS > 200) | 30-50 | 10-20 |

---

## 7. 트러블슈팅 가이드

### 7.1 "Connection is not available" 에러

```
SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms.
```

#### 원인 분석

```
[원인 1: 풀 크기 부족]
증상:
  - 피크 시간대에만 발생
  - 로그에 "active=10, idle=0" (풀 고갈)

해결:
  - maximum-pool-size 증가
  - 트래픽 분산 검토

[원인 2: 커넥션 누수]
증상:
  - 시간이 지날수록 증가
  - 재시작하면 일시적으로 해결

해결:
  - leak-detection-threshold 활성화
  - 스택 트레이스 분석
  - try-with-resources 사용

[원인 3: 느린 쿼리]
증상:
  - 특정 API 호출 후 발생
  - 슬로우 쿼리 로그에 기록

해결:
  - 쿼리 최적화
  - 인덱스 추가
  - 읽기 전용 쿼리는 레플리카로 분리

[원인 4: 데드락]
증상:
  - 여러 커넥션이 서로 대기
  - 데이터베이스 락 타임아웃

해결:
  - 트랜잭션 범위 최소화
  - 락 순서 일관성 유지
```

#### 진단 방법

```sql
-- MySQL에서 현재 커넥션 상태 확인
SHOW STATUS LIKE 'Threads_connected';
SHOW PROCESSLIST;

-- 락 대기 확인
SELECT * FROM information_schema.INNODB_LOCK_WAITS;
```

---

### 7.2 "Connection leak detection triggered" 경고

```
[WARN] Connection leak detection triggered for connection com.mysql.cj.jdbc.ConnectionImpl@1a2b3c4d
```

#### 원인 분석

```
[원인 1: 실제 커넥션 누수]
스택 트레이스 분석:
  at com.example.service.OldService.legacyMethod(OldService.java:123)

→ 해당 코드에서 커넥션을 명시적으로 닫지 않음

[원인 2: 긴 트랜잭션 (오탐)]
스택 트레이스 분석:
  at com.example.service.ReportService.generateReport(ReportService.java:45)

→ 대량 데이터 처리로 정상적으로 오래 걸림
→ leak-detection-threshold 증가 또는 배치 처리로 분리
```

#### 해결 방법

```java
// 1. Spring @Transactional 사용 (권장)
@Transactional
public void safeMethod() {
    // 트랜잭션 종료 시 자동 커넥션 반환
}

// 2. 수동 커넥션 관리 시 try-with-resources
public void manualConnection() {
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
        // 사용
    } // 자동 close()
}

// 3. 긴 작업은 배치로 분리
@Transactional
public void processLargeData() {
    List<Long> ids = repository.findAllIds();

    for (List<Long> batch : Lists.partition(ids, 1000)) {
        processBatch(batch);  // 별도 트랜잭션
    }
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void processBatch(List<Long> ids) {
    // 배치 처리
}
```

---

### 7.3 커넥션 풀 고갈 문제

```
증상:
- 모든 요청이 타임아웃
- 서버 응답 없음
- CPU, 메모리는 정상

로그:
HikariCP-Prod - Pool stats (total=25, active=25, idle=0, waiting=50)
```

#### 즉각 대응

```bash
# 1. 현재 상태 확인
curl http://localhost:8080/actuator/metrics/hikaricp.connections.active

# 2. 느린 쿼리 확인 (MySQL)
mysql> SHOW FULL PROCESSLIST;

# 3. 필요시 강제 종료
mysql> KILL <process_id>;
```

#### 장기 해결책

```yaml
# 1. 풀 크기 증가 (임시)
maximum-pool-size: 35  # 25 → 35

# 2. 타임아웃 최적화
connection-timeout: 10000  # 빠른 실패

# 3. 모니터링 강화
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
```

```java
// 4. 쿼리 최적화
// Before: N+1 문제
@Entity
public class User {
    @OneToMany(mappedBy = "user")
    private List<Order> orders;  // Lazy loading → N+1
}

// After: Fetch Join
@Query("SELECT u FROM User u LEFT JOIN FETCH u.orders WHERE u.id = :id")
Optional<User> findByIdWithOrders(@Param("id") Long id);
```

---

### 7.4 간헐적인 "Communications link failure"

```
CommunicationsException: Communications link failure
The last packet successfully received from the server was X milliseconds ago.
```

#### 원인 및 해결

```
[원인: 네트워크 타임아웃]
AWS 환경에서 유휴 커넥션이 중간 장비(ALB, NAT 등)에 의해 끊어짐

해결:
1. JDBC URL에 tcpKeepAlive 추가
   url: jdbc:mysql://...?tcpKeepAlive=true

2. max-lifetime 적절히 설정
   max-lifetime: 1800000  # 30분

3. keepalive-time은 0으로 유지 (RDS 환경)
   keepalive-time: 0
```

---

## 8. 참고 자료

### 8.1 공식 문서

- [HikariCP GitHub](https://github.com/brettwooldridge/HikariCP)
- [HikariCP Wiki](https://github.com/brettwooldridge/HikariCP/wiki)
- [Spring Boot DataSource Configuration](https://docs.spring.io/spring-boot/docs/current/reference/html/data.html#data.sql.datasource.connection-pool)

### 8.2 AWS RDS 관련

- [Amazon RDS for MySQL Parameters](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Appendix.MySQL.Parameters.html)
- [RDS Proxy Connection Pooling](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/rds-proxy-managing.html)

### 8.3 HikariCP 설정 요약표

| 설정 | 기본값 | Dev | Prod | 설명 |
|------|--------|-----|------|------|
| pool-name | HikariPool-N | HikariCP-Dev | HikariCP-Prod | 풀 식별자 |
| maximum-pool-size | 10 | 30 | 25 | 최대 커넥션 수 |
| minimum-idle | =max | 10 | 10 | 최소 유휴 커넥션 |
| connection-timeout | 30000 | 20000 | 20000 | 커넥션 획득 대기 (ms) |
| idle-timeout | 600000 | 300000 | 300000 | 유휴 커넥션 정리 (ms) |
| max-lifetime | 1800000 | 1800000 | 1800000 | 커넥션 최대 수명 (ms) |
| keepalive-time | 0 | 0 | 0 | Keep-alive 간격 (ms) |
| validation-timeout | 5000 | 5000 | 5000 | 유효성 검사 (ms) |
| leak-detection-threshold | 0 | 120000 | 60000 | 누수 감지 임계값 (ms) |

---

## 변경 이력

| 날짜 | 버전 | 변경 내용 |
|------|------|----------|
| 2025-01-15 | 1.0 | 최초 작성 |
