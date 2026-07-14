# ImTicket

> 동시 예약 상황의 데이터 정합성과 반복 조회 성능을 고려한 티켓 예매 플랫폼

## 프로젝트 소개

ImTicket은 공연 등록부터 회차별 좌석 생성, 좌석 선점, 예약 확정, 만료 좌석 회수, 입장 검증까지 티켓의 전체 흐름을 관리하는 서비스입니다.

운영자는 공연장과 좌석 템플릿을 등록하고 공연 일정과 좌석 가격을 설정할 수 있습니다. 사용자는 MetaMask 지갑 서명으로 인증한 뒤 공연과 좌석을 조회하고, 원하는 좌석을 선점해 예약을 확정할 수 있습니다. 예약이 확정되지 않으면 만료 처리 과정에서 좌석이 다시 판매 가능한 상태로 복구됩니다.

백엔드는 Java 21과 Spring Boot로 구현했으며, MySQL 트랜잭션과 비관적 Lock으로 동일 좌석의 중복 예약을 방지했습니다. 공연 상세처럼 반복 조회되는 데이터에는 Redis Cache를 적용하고, Micrometer와 Prometheus, k6를 이용해 동시 요청과 조회 성능을 측정했습니다.

## 핵심 흐름

```text
공연장과 좌석 템플릿 등록
    → 공연과 회차 등록
    → 회차별 판매 좌석 생성
    → 좌석 조회
    → 좌석 임시 선점
    → 예약 확정 또는 만료 좌석 회수
    → 입장 토큰 발급과 검증
```

## 주요 구현

### 좌석 예약 정합성

- 공연 회차와 좌석 ID를 함께 검증한 뒤 MySQL `PESSIMISTIC_WRITE` Lock 획득
- 다중 좌석 요청의 ID를 오름차순으로 정렬해 잠금 순서 고정
- `AVAILABLE`, `LOCKED`, `RESERVED` 상태를 기준으로 좌석 생명주기 관리
- 같은 공연 회차의 좌석 위치가 중복되지 않도록 Database Unique Constraint 적용

### 만료 예약 자동 정리

- `PENDING_PAYMENT` 상태이면서 만료 시각이 지난 예약만 정리
- 만료 예약 ID를 최대 5,000건 먼저 조회한 뒤 필요한 예약과 좌석 조회
- 만료된 예약의 좌석을 `AVAILABLE` 상태로 복구
- ShedLock을 적용해 다중 인스턴스의 정리 작업 중복 실행 방지
- 실행별 `runId`와 `correlationId`를 기록해 작업 단위 추적

### Redis 조회 Cache

- 공연 상세 응답을 Redis Look-aside Cache로 저장
- `performance:details:{id}` 형식의 Cache Key와 10분 TTL 적용
- Cache Hit, Miss, Write와 응답시간을 Micrometer로 계측
- k6와 Prometheus를 이용해 Database 직접 조회와 Redis 조회 성능 비교

### 인증과 입장 검증

- MetaMask ECDSA 서명 검증과 JWT 기반 API 인증
- 일회성 Nonce와 만료 시각을 이용한 서명 재사용 방지
- 로그인과 회원가입 목적에 따른 Nonce 분리
- 예약 소유자와 예약 상태를 확인한 뒤 입장 토큰 발급 및 검증

### 공연장 좌석 모델링

- 공연장의 좌석 배치를 `VenueHallSeatTemplate`로 관리
- 공연 회차마다 실제 판매 대상인 `Seat` 생성
- 좌석 템플릿과 회차별 좌석 상태의 생명주기 분리
- 좌석 등급별 가격을 회차별 판매 좌석에 반영

## 성능 검증

| 검증 항목 | 조건 | 결과 |
| --- | --- | --- |
| 동일 좌석 동시 예약 | 단일 서버, 동일 좌석, 50, 100, 200 VU | 각 구간에서 성공 1건, 나머지 409, 중복 예약과 5xx 0건 |
| 공연 상세 Redis Cache | 20 VU, 15초 | 평균 응답시간 65.30ms에서 24.66ms, 처리량 74.47 req/s에서 86.26 req/s |
| Cache Hit Ratio | Cache Warm-up 이후 | 99.92% |

## 기술 스택

| 구분 | 기술 |
| --- | --- |
| Backend | Java 21, Spring Boot 3.4.4, Spring Web MVC |
| Data | Spring Data JPA, Hibernate, MySQL 8 |
| Cache | Redis, Lettuce |
| Security | Spring Security, JWT, Web3j |
| Scheduling | Spring Scheduling, ShedLock |
| Monitoring | Micrometer, Prometheus, Grafana |
| Test | JUnit 5, Mockito, AssertJ, k6 |
| Frontend | React, Next.js, TypeScript |
| Infrastructure | Docker, Docker Compose |
| Collaboration | Git, GitHub |
