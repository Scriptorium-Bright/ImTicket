# 잠금부터 Waiting Room·좌석 캐시까지 끝내는 안내

작성일: 2026-09-04  
갱신일: 2026-09-10  
대상: ImTicket의 좌석 경합, 입장 대기열, 좌석 조회 캐시를 하나의 흐름으로 이해하려는 독자  
현재 기준: Waiting Room 구현·검증 완료, 좌석 캐시 구현·혼합 부하 효과 확인, 콜드 캐시 집중 부하 용량 경계 확인, 내구성 메시지 전달 도입 보류

## 읽기 전에: 현재 결론 한눈에 보기

이 안내서는 다음 결론에 도달하기까지의 판단 흐름을 설명한다. 첫 회독에서는 아래 표와 한 줄 요약을 먼저 확인한다.

| 문제 | 현재 결정 | 결정의 근거 |
|---|---|---|
| 같은 좌석에 여러 요청이 도착함 | MySQL 비관적 잠금으로 최종 승자 확정 | 성공 1건·후속 충돌 응답·다중 인스턴스 공유 경계 |
| 잠금 대기가 예약 애플리케이션 자원을 점유함 | Nginx·Waiting Room Service·입장권으로 유입량 제어 | 활성 세션 30에서 HikariCP 대기 0, 계약 성공률 100% |
| 여러 사용자가 같은 좌석 현황을 반복 조회함 | 인기 공연에 Redis snapshot 캐시 적용 | 웜 캐시 MySQL projection 2,000회 → 0회, 혼합 조회 p95 7,261ms → 996ms |
| 캐시 무효화 직후 같은 회차를 동시에 조회함 | same-JVM single-flight와 version 조건부 저장 적용 | 통제 300건 projection 301회 → 1회, 오래된 snapshot 저장 차단 |
| Redis 무효화·snapshot 갱신 비용이 커질 수 있음 | `INCR + DEL`보다 full snapshot 재작성·전송을 우선 측정 | 무효화 Lua p99 1.511ms, 조건부 저장 p99 95.487ms, MGET p99 10.871ms |
| 커밋 이후 조회 모델 반영이 누락될 수 있음 | 현재 `AFTER_COMMIT` 무효화 유지, Outbox·Message Bus 도입 보류 | Redis 명령 실패·프로세스 종료에서 stale API 응답 재현, 테스트 TTL 잔여 17.7초·관측 복구 19초 |

한 줄로 요약하면 다음과 같다.

```text
MySQL은 좌석 선점의 권위 저장소다.
Waiting Room은 예약 애플리케이션으로 들어오는 유량을 제어한다.
Redis snapshot은 반복 좌석 조회에서 MySQL을 보호한다.
single-flight는 같은 version의 동시 재구축을 합친다.
현재 후속 최적화 대상은 full snapshot 크기와 HTTP 응답 경로다.
```

이 표의 수치와 결정이 만들어진 과정을 아래 본문에서 시간순으로 읽는다.

## 1. 이 안내의 역할

ImTicket의 대기열과 캐시 관련 문서는 잠금 실험부터 148.x까지 여러 시점에 작성됐다. 당시의 계획, 실패한 접근, 구현 결과, 후속 제안이 함께 남아 있어 파일 번호만 따라가면 현재 결론을 놓치기 쉽다.

이 문서는 다음 질문을 처음부터 끝까지 연결한다.

```text
같은 좌석의 승자를 누가 결정하는가?
  → 실패할 요청은 어디에서 기다리는가?
  → 좌석별 진입 제어가 남긴 포화는 무엇인가?
  → 예약 작업 큐를 왜 구현했고 왜 종료했는가?
  → Waiting Room은 어떤 상태와 자원을 보호하는가?
  → 운영 후보값은 어떤 실패를 거쳐 정했는가?
  → 입장 뒤 반복되는 좌석 조회를 왜 캐시했는가?
  → 캐시 무효화 직후 어떤 실패가 새로 나타났는가?
  → single-flight와 버전은 무엇을 해결했는가?
  → 현재 캐시 적용 범위와 다음 검증 조건은 무엇인가?
  → 커밋 이후 내구성 메시지 전달을 왜 보류했는가?
```

본문은 핵심 개념과 판단 근거를 자체적으로 설명한다. 연결된 문서는 수치의 원본, 코드 경계, 당시 계획을 확인할 때 연다. 구현 설명을 소스 파일까지 따라가려면 [읽기 가이드 코드 추적표](lock-to-waiting-room-code-trace.md)를 함께 연다.

| 문서 | 역할 |
|---|---|
| 이 안내 | 대기열·캐시의 전체 문제 해결 흐름 |
| [읽기 가이드 코드 추적표](lock-to-waiting-room-code-trace.md) | guide의 설명이 현재 코드·설정·실험 스크립트에 닿는 위치 |
| [현재 티켓팅 아키텍처](../architecture/current-ticketing-architecture-flow.md) | 사용자의 현재 요청이 실제 구성 요소를 통과하는 순서 |
| [현재 예매 아키텍처](../architecture/146-current-reservation-architecture.md) | Waiting Room, 보호 구역, 좌석 조회, 동기 선점의 코드 책임과 저장소 경계 |
| [좌석 현황 캐시 전략 조사와 선택](../architecture/seat-map-cache-strategy-recommendation.md) | Cache-Aside, 무효화, single-flight, 적용 범위와 다중 인스턴스 전환 기준 |
| [single-flight·Snapshot Version 조건부 저장 설계](../implements/seat-availability/146.6.4-single-flight-versioned-snapshot-design.md) | 현재 snapshot 키·payload·필드 필요성·version 계약과 single-flight 구현 기준 |
| [아키텍처 의사결정 연대기](imticket-architecture-decision-chronology.md) | 관찰이 기존 판단을 바꾼 시간순 기록 |
| [아키텍처 결정 기록](../architecture/decisions/README.md) | 현재 채택·제안된 개별 결정과 적용 범위 |
| [깊이 있는 문제 해결 사례](imticket-narrative/imticket-deep-problem-solving-cases.md) | 포트폴리오에 사용할 장문 서사 |

## 2. 먼저 구분할 다섯 가지 책임

정합성(consistency)은 여러 요청이 동시에 실행돼도 데이터가 정해진 상태 규칙을 지키는 성질이다. 진입 제어(admission control)는 시스템이 처리할 수 있는 요청만 보호 구간으로 통과시키는 제어다. 조회 모델(Read Model)은 조회 용도에 맞춰 구성한 데이터 표현이다. 캐시 어사이드(Cache-Aside)는 캐시 누락 때 원본 저장소에서 읽고 캐시에 저장하는 방식이다. single-flight는 같은 키의 동시 작업을 한 번의 실행으로 합치는 동시성 제어다.

| 책임 | 해결할 질문 | 현재 담당 |
|---|---|---|
| 입장 대기 | 누가 언제 좌석 화면에 들어갈 수 있는가 | Nginx, Waiting Room Service, Redis 정렬 집합(Sorted Set, ZSET)·Hash·Lua, 입장권 |
| 좌석 현황 조회 | 반복 조회가 MySQL 연결을 얼마나 사용하는가 | Redis 좌석 현황 스냅샷, 캐시 어사이드, 제한된 데이터베이스 대체 조회 |
| 좌석 선점 정합성 | 같은 좌석의 최종 승자를 누가 정하는가 | MySQL 트랜잭션·행 잠금·상태 검증·멱등성 |
| 결제·만료 상태 전이 | `LOCKED` 좌석을 확정하거나 해제하는 주체는 누구인가 | MySQL 예약·좌석·결제 상태 전이 |
| 커밋 이후 조회 반영 | 확정된 상태를 Redis 조회 모델에 언제 반영하는가 | 현재 애플리케이션 내부 커밋 이후 무효화와 버전 증가 |

### 2.1 모든 예약이 Waiting Room을 거치지는 않는다

Waiting Room 적용 여부는 전역 활성화 값과 회차별 활성 목록으로 정한다. 전역 활성화가 켜져 있고 요청 회차가 활성 목록에 있을 때만 좌석 조회와 `pre-reserve`가 보호 구역이 된다.

```text
Waiting Room 미적용 회차
  → GET /seats, POST /pre-reserve
  → WaitingRoomAccessGuard: BYPASS
  → 좌석 조회·멱등성·좌석별 진입 제어·MySQL 처리

Waiting Room 적용 회차
  → POST /waiting-room/{회차}/join
  → ADMITTED + entry pass
  → GET /seats, POST /pre-reserve + X-Waiting-Room-Pass
  → WaitingRoomAccessGuard: ACTIVE
  → 좌석 조회·멱등성·좌석별 진입 제어·MySQL 처리
```

적용 회차에서 entry pass 없이 좌석 조회나 `pre-reserve`를 호출하면 보호 구역 검사에서 종료된다. 미적용 회차의 `pre-reserve`는 join 없이 실행된다. 이 분기와 코드 위치는 [코드 추적표의 보호 구역 흐름](lock-to-waiting-room-code-trace.md)에서 확인한다.

현재 사용자 요청은 다음 순서로 처리된다.

```text
Client
  → Nginx Gateway
  → Waiting Room Service
  → Redis 입장 상태
  → 입장권
  → Reservation Application
  → Redis 좌석 현황 스냅샷
       ├─ 적중: 스냅샷 반환
       └─ 누락: single-flight → MySQL 전체 좌석 조회 → 버전 조건부 저장
  → 동기 pre-reserve
  → MySQL 트랜잭션·잠금·멱등성
  → 커밋 이후 좌석 스냅샷 버전 증가·삭제
```

Waiting Room은 유입량을 제어한다. 좌석 캐시는 조회 비용을 줄인다. MySQL은 좌석 선점의 승패와 예약 상태를 확정한다. 각 구성 요소의 성공 기준과 실패 응답을 따로 측정한다.

## 3. 첫 질문: 같은 좌석의 승자를 어떻게 한 명으로 제한했는가

### 3.1 MySQL 비관적 잠금으로 불변 조건을 세웠다

불변 조건(invariant)은 동시 실행과 실패가 발생해도 유지해야 하는 상태 규칙이다. 좌석 선점의 첫 불변 조건은 다음과 같았다.

```text
Reservation 성공 1건
ReservedSeat 1건
Seat 상태 LOCKED
후속 요청은 409 Conflict
```

비관적 잠금(pessimistic lock)은 `SELECT ... FOR UPDATE`로 좌석 행을 잠근다. 좌석 상태 확인, `AVAILABLE → LOCKED`, 예약과 연결 좌석 생성을 한 트랜잭션에서 처리했다. 여러 좌석은 식별자를 정렬해 같은 순서로 잠그고 함께 커밋하거나 함께 롤백했다.

MySQL 잠금은 여러 애플리케이션 인스턴스가 같은 데이터베이스를 사용할 때 공유되는 최종 정합성 경계다. 잠금은 한 명의 승자를 정했고, 패배할 요청도 행 잠금과 데이터베이스 연결을 기다렸다.

근거: [잠금 전략 비교 결과](../experiments/lock-strategy/95-lock-strategy-comparison-results.md) · [ADR-0002 잠금 경계](../architecture/decisions/0002-lock-boundary-by-application-topology.md)

### 3.2 여섯 잠금 전략을 비교해 대기 위치를 찾았다

비관적 잠금, 낙관적 잠금, `synchronized`, 공정 `ReentrantLock`, MySQL Named Lock, 단일 작업자 직렬화를 비교했다. 평가는 성공 한 건, 예상된 실패 응답, 전송 시간 초과, 대기 위치, 다중 인스턴스 적용 범위 순서로 진행했다.

| 전략 | 관찰 | 결정에 미친 영향 |
|---|---|---|
| MySQL 비관적 잠금 | 행 잠금과 HikariCP 연결 대기 발생 | 다중 인스턴스의 최종 정합성 경계로 유지 |
| 공정 `ReentrantLock` | MySQL 행 잠금 대기 감소, JVM 내부 대기 증가 | 단일 JVM의 대기 흐름 제어에 사용 |
| 낙관적 잠금 | 높은 충돌 조건에서 내부 오류 | 현재 충돌 응답 계약에서 제외 |
| MySQL Named Lock | 연결과 HTTP 요청 장기 점유 | 현재 예약 경로에서 제외 |
| 단일 작업자 | 서로 다른 좌석까지 직렬화 | 전역 처리 병목으로 제외 |
| `synchronized` | 제한 시간과 공정성 제어 부족 | 비교 후보에서 제외 |

공정 `ReentrantLock`을 사용한 동일 좌석 2,000 가상 사용자(Virtual User, VU) 시험에서 MySQL 행 잠금과 HikariCP 연결 풀 대기는 0이었다. p95는 응답의 95%가 완료되는 상한 시간이다. Tomcat 처리 중 스레드는 200에 도달했고, 세 번의 실행에서 전송 시간 초과가 `0 / 132 / 806`건 발생했다.

추상 큐 동기화기(AbstractQueuedSynchronizer, AQS)는 `ReentrantLock`의 대기열을 관리하는 JVM 동시성 기반이다. 스레드 덤프에는 `ReentrantLock.tryLock → AQS → LockSupport.park`가 반복됐다. 대기 위치가 MySQL에서 JVM 잠금과 Tomcat 요청 스레드로 이동했다.

## 4. 두 번째 질문: 실패할 요청을 어디에서 끝낼 것인가

### 4.1 좌석별 진입 제어로 전송 시간 초과를 명시적 429로 바꿨다

좌석별 허가증(permit)을 얻은 요청만 잠금과 예약 트랜잭션으로 진행시키고, 초과 요청은 `SEAT_ADMISSION_REJECTED` 429로 종료했다.

```text
요청
  → 좌석별 permit 획득
       ├─ 실패: 429
       └─ 성공: ReentrantLock → 예약 트랜잭션
```

2,000 VU 세 번의 재측정에서 전송 시간 초과는 `0 / 0 / 0`이 됐다. 예약 성공은 매회 1건이었고 나머지 요청은 설명 가능한 429와 충돌 결과로 분류됐다.

Tomcat 처리 중 스레드는 계속 200에 도달했다. 당시 회원 조회와 데이터베이스 멱등성 처리권 획득이 좌석별 진입 제어보다 먼저 실행됐다. 후속 진단에서는 HikariCP 활성 연결 30개와 대기 요청 167개가 관측됐다. 첫 데이터베이스 조회 앞에서 유입량을 제어할 제품 경계가 필요했다.

근거: [2,000 VU 통합 판정](../experiments/admission/103-2000vu-diagnosis-05-integrated-decision.md) · [좌석별 진입 제어 재측정](../experiments/admission/109-seat-admission-remeasurement-decision.md) · [진입 제어 이전 병목 귀속](../test/2026-07-29-reservation-pre-admission-bottleneck-attribution.md)

### 4.2 예상된 거절은 응답·메트릭·로그의 책임을 나눠야 한다

좌석별 진입 제어가 적용된 실행에서는 약 1,983건의 429가 짧은 시간에 발생했다. 각 요청을 경고 로그로 남기면 표준 출력 경합이 짧게 끝날 요청의 지연에 포함될 수 있다. 스레드 덤프에서 Logback `OutputStreamAppender` 호출이 반복된 사실을 관찰했다.

현재 문서에서 정한 운영 원칙은 다음과 같다. 구현 변경과 대조 시험은 후속 과제다.

| 상황 | 기록 방식 | 목적 |
|---|---|---|
| 예상된 용량 초과 | 메트릭과 제한된 표본 로그 | 거절률과 용량 추세 확인 |
| 대기열 우회·재시도 급증 | 경고 로그와 알림 | 비정상 경로 탐지 |
| 원인 조사가 필요한 429 | 요청 식별자와 원인을 포함한 로그 | 장애 분석 |

## 5. 세 번째 질문: 예약 요청을 비동기 큐에 넣으면 해결되는가

### 5.1 `202 Accepted + 예약 티켓 + Redis Streams 작업자`를 구현했다

첫 데이터베이스 조회 앞에서 요청을 수락하기 위해 비동기 예약 큐를 설계하고 구현했다.

```text
HTTP 요청
  → Redis 티켓·순번·Stream 저장
  → 202 Accepted

작업자
  → 소비자 그룹 수신
  → 데이터베이스 멱등성 처리권 획득
  → 좌석 잠금·예약 트랜잭션
  → 결과 저장
  → 처리 확인(acknowledgement, ACK)
```

Redis Streams는 작업 전달과 미처리 항목 회수를, ZSET은 순번과 시간 인덱스를, Hash는 예약 티켓 상태를 담당했다. 작업자 중단, 임대 만료, 최소 한 번 전달에서 발생하는 중복, 재시도, 반복 실패 메시지 격리까지 복구 계약을 설계했다.

### 5.2 사용자에게 두 종류의 기다림이 생겼다

구현한 흐름을 사용자 관점에서 다시 확인하자 서로 다른 상태 수명이 보였다.

| 상태 | 기다리는 대상 | 종료 조건 |
|---|---|---|
| 입장 대기 티켓 | 좌석 화면에 들어갈 순서 | 입장 허용·취소·대기 만료 |
| 예약 작업 티켓 | 선택한 좌석의 처리 결과 | 성공·충돌·실패·재시도 종료 |

두 티켓을 함께 운영하면 사용자는 입장 순번을 기다린 뒤 좌석 선점 결과를 다시 기다린다. 서버는 두 상태 체계의 만료, 재접속, 처리권 회수, 최종 결과를 각각 관리한다. 좌석을 선택한 사용자는 승패를 즉시 확인해야 했고, 입장 인원을 제한하면 좌석 조회와 동기 선점의 유입량을 함께 제어할 수 있었다.

Redis Streams 예약 큐를 제품 경로에서 종료하고 다음 흐름으로 전환했다.

```text
입장 Waiting Room
  → 좌석 조회·선택
  → 동기 pre-reserve
  → MySQL 좌석 선점
```

종료한 구현과 복구 설계는 보관 문서에 유지했다. 비동기 처리의 판단 기준도 남았다. 작업 수락 지연, 사용자에게 노출되는 상태 수명, 작업자 장애 복구, 최종 결과 확인 방식을 함께 계산해야 한다.

근거: [종료한 Redis Streams 예약 큐](../archive/145-retired-reservation-stream-queue/README.md) · [ADR-0001 입장 대기열과 동기 선점](../architecture/decisions/0001-entry-waiting-room-and-synchronous-pre-reserve.md)

## 6. 네 번째 질문: Waiting Room은 어떤 상태와 자원을 보호하는가

### 6.1 Redis ZSET·Hash·Lua로 입장 상태를 관리했다

Java 메모리 큐, MySQL 큐 테이블, Redis List, Redis ZSET·Hash·Lua, Redis Streams, Kafka, 관리형 Waiting Room을 순번 조회, 재접속, 다중 인스턴스 공유, 원자적 전이, 운영 복잡도로 비교했다.

| 구성 요소 | 책임 |
|---|---|
| waiting ZSET | 회차별 순번 |
| deadline ZSET | 대기 만료 |
| active ZSET | 입장권 임대 시간과 활성 세션 상한 |
| ticket Hash | 소유자·회차·상태·만료 시각 |
| sequence String | 회차별 단조 증가 순번 |
| owner String | 회원·회차별 현재 ticket mapping |
| admission Hash | admission window와 주기당 입장 count |
| Lua | 등록·승급·취소·완료의 원자적 상태 전이 |
| Redis Pub/Sub | 상태 변경 lifecycle event를 연결 소유 인스턴스로 전달 |
| SSE | 상태 변경 전달 |
| 상태 조회 | 연결 유실과 재접속 뒤 현재 상태 복원 |

Waiting Room 티켓의 `WAITING`, `ADMITTED`, `COMPLETED`, `CANCELED`, `EXPIRED`는 입장 생명주기다. 예약의 `PENDING_PAYMENT`, `SUCCESS`, `EXPIRED`는 좌석과 결제의 생명주기다. 두 상태는 서로 다른 만료 조건과 복구 책임을 가진다.

입장된 사용자에게 사용자·공연 회차·만료 시각을 포함한 서명 입장권을 발급한다. 좌석 조회와 `pre-reserve`는 같은 접근 제어기에서 입장권을 검증한다. Redis 오류가 발생하면 보호 대상 API는 503으로 닫힌다.

근거: [Waiting Room 저장 방식 결정](../implements/waiting-room/146.16-waiting-room-queue-alternatives-decision.md) · [단일 서버 메모리 대안 의사결정](../implements/waiting-room/146.18-single-server-memory-queue-decision.md) · [Waiting Room 전체 흐름](../implements/waiting-room/146.14-waiting-room-flow.md)

저장소 연산 비용이 Controller·Service·embedded Tomcat API에서도 어떻게 나타나는지는 [146.18의 API 비교](../implements/waiting-room/146.18-single-server-memory-queue-decision.md#10-embedded-tomcat-api-비교-결과)에서 확인한다.

Redis의 기준 상태는 ticket Hash에 있고, `waiting`·`deadline`·`active` ZSET은 순번과 만료 대상을 빠르게 찾는 인덱스다. `sequence`는 join 순번을 발급하고, `owner`는 동일 회원의 중복 join을 기존 ticket으로 수렴시키며, `admission` Hash는 여러 인스턴스가 주기당 입장 quota를 공유하게 한다. Redis Pub/Sub는 상태 변경 알림을 전달하고, HTTP status 조회는 Redis의 현재 ticket 상태를 다시 읽는다. 좌석 선점의 최종 정합성은 MySQL이 담당한다. 상세 키 흐름은 [Waiting Room 전체 흐름 1.4절](../implements/waiting-room/146.14-waiting-room-flow.md)에서 확인한다.

### 6.2 Nginx와 별도 서비스로 실행 자원을 분리했다

Waiting Room을 Redis에 구현한 초기 구조에서는 2,000명 입장 요청이 Reservation Application의 Tomcat 스레드를 사용했다. Nginx Gateway와 별도 Waiting Room Service를 두어 입장 등록·상태·SSE와 좌석 조회·선점의 실행 자원을 나눴다.

```text
Client
  → Nginx Gateway
       ├─ Waiting Room API → Waiting Room Service
       └─ 좌석·예약·결제 API → Reservation Application
```

Nginx 도입 전후의 요청 경계는 세 단계로 이어진다.

```text
1. 직통 기준선
Client → Reservation Application `/join` → Redis Waiting Room
       → ticket status·SSE → seat map·pre-reserve

2. Nginx 도입
Client → Nginx `limit_req` → Reservation Application `/join`
       → Redis Waiting Room → ticket status·SSE → protected API

3. 최종 서비스 분리
Client → Nginx
       ├─ join·status·SSE → Waiting Room Service → Redis
       └─ seat map·pre-reserve → Reservation Application → MySQL
```

직통 기준선에서는 대기열 등록 요청도 Reservation Application의 Tomcat worker를 사용했다. Nginx 단계에서는 전달률을 조절해 origin 유입 시점을 분산했고, 별도 서비스 단계에서는 Waiting Room의 HTTP·스케줄러 실행 자원을 예약 애플리케이션과 나눴다. 2,000명 조건의 Tomcat busy는 직통 200, Nginx 단계 57~75, 별도 Waiting Room Service 단계의 origin 30~32로 측정됐다. 각 수치는 실행 범위가 다른 실험에서 수집했으므로 원문 조건과 함께 해석한다.

| 단계 | Reservation Application의 Tomcat 처리 중 스레드 최대 | HikariCP 연결 대기 요청 |
|---|---:|---:|
| 직접 유입 | 200 | 최대 167 수준 |
| Nginx 전달률 제어 | 57~75 | 0에 가까움 |
| 별도 Waiting Room Service | 30~32 | 0 |

이 표의 수치는 모두 k6 기반 HTTP API 부하에서 수집했지만, 각 행은 서로 다른 실행 범위를 요약한다. 동일한 API 시나리오를 한 번에 단계별로 재실행한 단일 before/after 표로 읽지 않는다.

- 직접 유입의 Tomcat busy `200`은 2,000건 `/join` 직통 부하에서도 확인됐다. HikariCP 대기 `154~167`은 회원 조회·멱등성 기록(`createClaim`)·좌석 선점이 포함된 초기 예약/pre-admission 경로에서 관측된 별도 부하다.
- Nginx 전달률 제어의 `57~75`는 [146.9 ingress 실험](../implements/waiting-room/146.9-waiting-room-ingress-boundary-experiment.md)의 2,000건 `MODE=join` API 실행 결과다. 이 실행의 HikariCP pending은 direct `0·1·0`, Gateway `0·0·0`이었다.
- 별도 Waiting Room Service의 `30~32`는 [146.9 서비스 분리 실행](../implements/waiting-room/146.9-waiting-room-boundary-plan-and-execution.md)의 `MODE=full-flow` API 결과다. `join → 상태 폴링(status polling) → entry pass → seat map → pre-reserve`를 수행했으며, 수치는 Reservation Application origin 지표다.

세 행은 모두 k6 HTTP API 실행으로 수집했다. Ticket SSE 부하와 1명 브라우저 대체 E2E 결과는 별도 검증으로 기록되어 있다. 따라서 이 표는 “API 기반 검증의 단계별 요약”으로 이해하고, 각 수치의 부하 시나리오와 측정 대상을 원문 실행 문서에서 함께 확인한다.

Nginx 전달률 제어는 예약 애플리케이션을 보호하면서 클라이언트 연결을 유지했다. 2,000건을 초당 100건으로 전달한 실행에서 `/join` p95는 약 19초였다. 별도 Waiting Room Service는 입장 티켓을 즉시 반환하고 실제 대기 상태를 Redis에서 관리했다. 서버 보호 지표와 사용자 대기시간을 별도 지표로 기록했다.

근거: [Nginx 진입 경계 실험](../implements/waiting-room/146.9-waiting-room-ingress-boundary-experiment.md) · [서비스 분리 실행 결과](../implements/waiting-room/146.9-waiting-room-boundary-plan-and-execution.md) · [Waiting Room 진입 경계 ADR](../implements/waiting-room/146.12-waiting-room-admission-boundary-adr.md)

### 6.3 입장 등록의 비동기화도 측정한 뒤 제거했다

동기 Lua 입장 등록과 Redis Streams 작업자를 사용하는 비동기 입장 등록을 각각 2,000건씩 세 번 비교했다.

| 지표 | 동기 입장 등록 | 비동기 입장 등록 |
|---|---:|---:|
| 계약 완료 | 6,000/6,000 | 6,000/6,000 |
| p95 평균 | 1,124.0ms | 1,454.3ms |
| Tomcat 처리 중 스레드 최대 | 200 | 200 |

비동기 경로의 HTTP 요청 안에서 티켓 Hash와 waiting ZSET 생성이 이미 끝났다. 작업자는 상태·알림 기록만 추가했다. 비동기 수락 p95는 29.4% 높았고 Tomcat 사용량은 같았다. Redis Streams 입장 인계와 전용 SSE를 제거하고 동기 Lua 등록을 유지했다.

SSE는 서버에서 브라우저로 상태 변경을 전달하는 서버 전송 이벤트(Server-Sent Events)다. 브라우저는 SSE 연결 중에도 15초마다 status API를 호출해 Redis의 현재 ticket 상태로 화면을 재조정한다. 이 상태 조회는 Pub/Sub 전달 누락과 재연결 뒤 복구 경로이며, SSE 오류가 세 번 누적된 경우에도 실행된다. 상태 전달 요청은 Nginx를 거쳐 Waiting Room Service와 Redis에서 처리되므로 Reservation Application의 Tomcat·HikariCP와 분리된다. 현재 구현은 `Last-Event-ID`를 이용한 과거 이벤트 재생을 제공하지 않는다. 상세 상태 전이와 측정 항목은 [Waiting Room 전체 흐름](../implements/waiting-room/146.14-waiting-room-flow.md)에 정리했다.

#### 6.3.1 Ticket SSE와 HTTP polling은 어떻게 함께 동작하는가

대기 화면은 ticket 하나의 상태를 두 경로로 받는다. SSE는 브라우저가 `GET /api/reservation/waiting-room/{회차}/tickets/{ticket}/events` 연결을 열어 두고 상태 변경 이벤트를 받는 방식이다. Waiting Room Service는 연결을 열 때 현재 ticket snapshot을 먼저 보내고, 승급·취소·만료·완료가 발생하면 `WAITING`, `ADMITTED`, `CANCELED`, `EXPIRED`, `COMPLETED` 상태를 즉시 전달한다. 사용자는 순번 변동과 입장 허용을 화면에서 확인할 수 있다.

HTTP polling은 브라우저가 `GET /api/reservation/waiting-room/{회차}/tickets/{ticket}`을 짧게 호출해 Redis의 현재 ticket 상태를 다시 읽는 방식이다. 현재 프런트엔드는 SSE가 정상 연결된 상태에서도 15초마다 이 API를 호출한다. 새로고침 뒤와 SSE 오류가 세 번 누적된 때도 같은 API를 호출한다. 응답에는 `WAITING`의 현재 순번이 들어가고, `ADMITTED`에서는 보호 구역 접근에 쓸 entry pass가 함께 들어간다.

두 경로는 같은 Redis ticket 상태를 바라본다. SSE는 상태 변경을 빠르게 인지하게 한다. HTTP polling은 Redis Pub/Sub 전달 누락, 브라우저 재연결, 화면과 서버 상태의 차이를 Redis snapshot으로 복구한다. 현재 Pub/Sub는 연결이 끊긴 구독자에게 이전 이벤트를 다시 전달하지 않고, 구현에는 `Last-Event-ID` 기반 이벤트 재생도 없다. 그래서 15초 polling은 상태 전달의 정합성을 보완하는 경로다. 이 요청은 Nginx를 거쳐 Waiting Room Service와 Redis에서 끝나며, Reservation Application과 MySQL에는 대기 사용자당 status 조회가 전달되지 않는다.

| 구분 | SSE | HTTP polling |
|---|---|---|
| 요청 방식 | 장시간 유지하는 event stream | 15초마다 수행하는 짧은 status API 요청 |
| 주된 역할 | 상태 변경의 빠른 화면 전달 | Redis snapshot으로 상태 재조정·복구 |
| 실행 시점 | 대기 화면 진입부터 `ADMITTED` 또는 terminal 상태까지 | SSE 연결 중, 새로고침 뒤, SSE 오류 누적 뒤 |
| 서버 처리 경로 | Waiting Room Service의 `SseEmitter` | Waiting Room Service의 status 조회와 Redis Hash·ZSET 조회 |
| 측정 지표 | 연결 수, 전달 지연, 재연결 | status RPS, Redis 호출 수, 화면 복구 시간 |

근거: [동기·비동기 입장 등록 비교](../test/2026-08-20-waiting-room-a1-a2-comparison.md) · [Waiting Room 의사결정 로그](../implements/waiting-room/146.13-waiting-room-decision-log.md)

### 6.4 운영값은 제외한 후보의 실패에서 정했다

활성 세션 상한 50에서는 HikariCP 연결 대기 요청이 최대 20개 발생했다. 상한 30과 주기당 입장 허용량 25를 적용한 2026-08-24 종료 시험에서는 2,000명 전체 흐름을 세 번 실행했다.

| 검증 | 2026-08-24 결과 |
|---|---:|
| 입장·좌석 조회·선점 계약 | 3회 모두 100% |
| Reservation Application의 Tomcat 처리 중 스레드 | 최대 30 |
| HikariCP 연결 대기 요청 | 0 |
| 동일 좌석 100명 | 성공 1건·409 99건·교착 상태 0건 |
| 입장 처리율 | 13.06~16.09명/s |
| 대기시간 p95 | 93.47~121.70초 |

상한 30은 예약 애플리케이션 보호 기준을 통과했다. 주기당 25명 설정은 초당 25명의 실제 입장 처리율을 보장하지 않았다. 활성 세션 반환 시간과 승급 스케줄러 실행 시간이 실제 처리율을 결정했다.

좌석 캐시 변경 이후 같은 전체 흐름을 다시 검증한 결과 2,000 VU에서는 좌석 조회 대체 경로 거부와 대기시간 기준 초과가 발생했다. 500·1,000·1,500 VU는 계약·HTTP·업무 흐름을 통과했다. 최신 1,750 VU 실행은 계약 99.60%, 대기시간 p95 140.287초, 승급 처리율 8.663명/s로 종료 기준을 충족하지 못했다. 현재 1,500 VU를 보수적인 업무 흐름 기준선으로 기록하며, 최종 운영 용량은 별도 부하 발생기와 승급 스케줄러 개선을 검증한 뒤 확정한다.

이 두 결과의 의미는 시간순으로 구분한다.

1. 2026-08-24 결과는 당시 Waiting Room 보호 구조의 종료 근거다.
2. 캐시 통합 이후 재검증은 현재 코드의 전체 흐름 용량 경계를 갱신한 근거다.
3. 활성 세션 30은 Reservation Application 보호값으로 유지한다.
4. 수용 가능한 전체 대기 인원은 현재 1,500 VU 기준선과 후속 용량 시험으로 관리한다.

근거: [2026-08-24 최종 검증](../test/2026-08-24-waiting-room-final-closure.md) · [single-flight 결과의 보호 경로 재검증](../implements/seat-availability/146.6.4-single-flight-versioned-snapshot-result.md) · [응답 용량 후속 진단](../implements/seat-availability/146.6.2-seat-map-response-optimization-diagnosis.md#12-http-응답-용량-후속-실행-계획)

## 7. 다섯 번째 질문: Waiting Room 뒤에서 좌석 조회는 왜 다시 병목이 됐는가

### 7.1 입장한 사용자들이 같은 전체 좌석 목록을 반복 조회했다

Waiting Room은 동시에 좌석 화면에 들어가는 사용자를 제한했다. 입장한 사용자들의 좌석 조회는 같은 공연 회차의 전체 좌석을 매번 MySQL에서 읽고 HTTP 응답으로 만들었다.

캐시 비활성 조건과 웜 캐시(warm cache), 즉 스냅샷이 미리 적재된 조건에서 2,000건을 세 번씩 비교했다.

| 지표 | 캐시 비활성 | 웜 캐시 | 변화 |
|---|---:|---:|---:|
| 계약 완료 | 2,000/2,000 | 2,000/2,000 | 유지 |
| p95 중앙값 | 15.593초 | 11.062초 | 29.06% 감소 |
| MySQL 전체 좌석 조회 | 2,000회 | 0회 | 제거 |
| HikariCP 연결 대기 요청 | 166 | 0 | 제거 |
| Tomcat 처리 중 스레드 | 200 | 200 | 유지 |
| 응답 수신량 | 약 616.75MB | 약 616.75MB | 유사 |

캐시는 MySQL 전체 좌석 조회와 연결 풀 대기를 줄였다. Tomcat 처리, JSON 직렬화, HTTP 전송량은 남았다. 이 결과부터 데이터베이스 보호와 사용자 응답 완료를 별도 지표로 관리했다.

근거: [148.1 좌석 조회 종합 결과](../implements/seat-availability/148.1-results-and-decision.md)

### 7.2 현재 캐시 어사이드 흐름

현재 좌석 현황은 캐시 어사이드 방식으로 공연 회차별 JSON 스냅샷을 Redis에 저장한다.

좌석 현황 캐시의 전체 요청·재구축·무효화 흐름은 [좌석 현황 캐시 전체 흐름](../implements/seat-availability/148.4-seat-availability-cache-flow.md)에서 별도로 읽는다. 이 안내에서는 Waiting Room 이후 어떤 병목이 생겼고 어떤 측정 결과가 캐시 적용으로 이어졌는지만 연결한다.

```text
GET /api/seats/{performanceTimeId}
  → 입장권 검증
  → 회차별 캐시 적용 여부 확인
  → Redis 스냅샷 조회
       ├─ 적중: 좌석 현황 반환
       └─ 누락: MySQL 전체 좌석 조회
                 → Redis 스냅샷 저장
                 → 좌석 현황 반환
```

MySQL의 좌석·예약·결제 상태가 권위 데이터다. Redis는 조회 응답을 위한 스냅샷이다. `pre-reserve`는 캐시 결과를 신뢰해 좌석을 확정하지 않고 MySQL 행 잠금과 상태를 다시 검사한다.

구현 경계는 [SeatMapCacheReader](../../src/main/java/org/example/ticket/reservation/booking/cache/SeatMapCacheReader.java)에 있다. cache hit·miss, same-JVM single-flight, DB fallback 상한, version 조건부 저장을 이 클래스가 조율한다.

### 7.3 커밋 이후 무효화로 롤백과 조회 모델을 분리했다

좌석 선점, 결제 완료, 만료 해제가 커밋되면 해당 회차의 좌석 상태가 바뀐다. 트랜잭션 실행 중에 캐시를 삭제하면 이후 롤백됐을 때 변경되지 않은 데이터 때문에 캐시가 비워질 수 있다. 현재 구현은 트랜잭션 커밋 이후 단계(`AFTER_COMMIT`)의 이벤트에서 회차 버전을 증가시키고 스냅샷을 삭제한다.

```text
좌석 상태 변경 트랜잭션
  → MySQL 상태 변경
  → commit
  → AFTER_COMMIT 이벤트
  → Redis 회차 버전 증가
  → Redis 좌석 스냅샷 삭제
```

롤백된 트랜잭션은 무효화 이벤트를 발생시키지 않는다. Redis 삭제가 실패해도 이미 커밋된 예약은 유지된다. 스냅샷 만료 시간과 다음 데이터베이스 조회가 복구를 보조한다. 애플리케이션 프로세스가 커밋 직후 종료되면 프로세스 메모리의 무효화 이벤트가 재기동 뒤 자동으로 회수되지 않는 구간이 확인됐다.

Redis 무효화의 `INCR`·`DEL` 실행 실패를 재현한 시험에서는 MySQL이 `LOCKED`를 커밋한 뒤 Redis version `0`과 `AVAILABLE` snapshot이 남았고, 좌석 조회 API도 `AVAILABLE`을 반환했다. 테스트 TTL 2분에서 stale 응답은 관측 시점 기준 약 56초 유지됐으며, TTL 만료 뒤 MySQL projection으로 `LOCKED` snapshot이 재구축됐다. 프로세스 종료 시험에서는 MySQL `LOCKED` 커밋과 k6 전송 실패를 확인한 뒤 애플리케이션을 종료했고, 재기동 직후 `AVAILABLE` 응답을 17.7초 남은 TTL 동안 관측했다. 19초 뒤 API가 `LOCKED`로 복구됐다.

이 일관성 경계의 구현·검증·후속 계획은 다음 문서에서 이어진다.

| 읽을 질문 | 문서 |
|---|---|
| version과 조건부 snapshot 저장은 오래된 재구축을 어떻게 막는가 | [single-flight·version 조건부 저장 설계](../implements/seat-availability/146.6.4-single-flight-versioned-snapshot-design.md) |
| commit·rollback 뒤 무효화는 실제로 어떻게 검증됐는가 | [148.1 W3 상태 변경 최신성](../implements/seat-availability/148.1-w3-state-change-freshness.md) |
| AFTER_COMMIT 무효화 실패·프로세스 종료가 실제 stale API 응답으로 이어지는가 | [148.3 무효화 실패·프로세스 종료 재현 결과](../implements/seat-availability/148.3-after-commit-cache-invalidation-failure-test.md) |
| 캐시 조회 모델의 권위·장애·version 경계는 무엇인가 | [ADR-0005 좌석 현황 캐시 경계](../architecture/decisions/0005-seat-availability-cache-boundary.md) |
| commit 이후 이벤트 유실을 어떻게 복구할 것인가 | [148.2 Transactional Outbox 계획](../implements/seat-availability/148.2-transactional-outbox-plan.md) |

## 8. 여섯 번째 질문: 캐시 무효화 직후의 동시 조회를 어떻게 처리했는가

### 8.1 캐시 스탬피드가 전체 좌석 조회를 반복시켰다

캐시 스탬피드(cache stampede)는 하나의 캐시 누락에 많은 요청이 원본 저장소로 몰리는 현상이다. 좌석 상태 변경이 스냅샷을 삭제한 직후 여러 요청이 들어오면 각 요청이 같은 회차의 전체 좌석을 MySQL에서 읽고 같은 JSON을 만들 수 있었다.

원인 후보를 TTL·무효화 시점·동시 DB projection·Redis 명령·응답 전송 비용으로 분해한 기록은 [캐시 스탬피드 원인 분해](../implements/seat-availability/146.6.3-seat-map-cache-churn-stampede-decision.md)에 남아 있다.

같은 JVM과 공연 회차의 재구축을 하나로 합치는 single-flight를 추가했다.

```text
동시 캐시 누락
  → 첫 요청: owner
       → 버전 읽기
       → MySQL 전체 좌석 조회 1회
       → 버전 조건부 Redis 저장
  → 나머지 요청: joiner
       → owner의 좌석 목록 또는 실패 결과 대기
  → 각 요청이 자신의 HTTP 응답 생성·전송
```

single-flight는 데이터베이스 재구축 결과를 공유한다. JSON 응답 생성과 네트워크 전송은 요청마다 실행된다.

### 8.2 버전 조건부 저장으로 오래된 스냅샷의 복귀를 막았다

재구축 중 좌석 상태가 다시 변경되면 오래 걸린 조회가 최신 스냅샷을 덮어쓸 수 있다. owner는 시작 시점의 회차 버전을 읽는다. MySQL 조회 뒤 현재 버전이 같을 때만 스냅샷을 저장한다. 버전이 증가했다면 저장을 거부하고 다음 조회가 최신 상태를 다시 구성하도록 한다.

통제된 300건 시험에서 MySQL 전체 좌석 조회는 301회에서 최종 비교 1회로 감소했고 300건 모두 성공했다. 전체 Gradle 시험은 218개였으며 건너뜀 11개, 실패·오류 0개를 기록했다.

근거: [single-flight·버전 조건부 저장 설계](../implements/seat-availability/146.6.4-single-flight-versioned-snapshot-design.md) · [구현·성능 결과](../implements/seat-availability/146.6.4-single-flight-versioned-snapshot-result.md)

### 8.3 2,000건 콜드 캐시 시험은 새로운 포화를 보여줬다

콜드 캐시(cold cache)는 필요한 스냅샷이 없어 원본 저장소에서 다시 구성해야 하는 상태다. 스냅샷 무효화 직후 2,000건을 동시에 보낸 최신 비교 결과는 다음과 같다.

| 지표 | 캐시 비활성 | 캐시 활성 |
|---|---:|---:|
| 좌석 조회 p95 | 14.912초 | 15.947초 |
| 조회 성공 | 1,035건 | 101건 |
| 전송 실패 | 965건 | 1,867건 |
| MySQL 전체 좌석 조회 | 1,431회 | 4회 |
| single-flight 대기 참여 | 0회 | 1,915회 |
| single-flight 대기 시간 초과 | 0회 | 35회 |

캐시 활성 조건에서 MySQL 조회는 4회로 줄었다. 사용자 요청 성공은 101건으로 감소했고 전송 실패는 1,867건으로 증가했다. 데이터베이스 보호가 곧바로 HTTP 용량 개선으로 이어지지 않는 경계가 확인됐다.

당시 지표로 남은 원인 후보는 Redis 스냅샷 조회·저장 지연, JSON 직렬화·역직렬화, single-flight 대기, Tomcat 응답 처리, 약 308KB 응답 전송이다. 하나의 주원인은 아직 확정하지 않았다.

### 8.4 혼합 부하에서는 캐시 적용 가치가 확인됐다

쓰기 초당 50건과 좌석 조회 초당 100건을 20초 동안 섞은 시험에서는 캐시가 다음 효과를 보였다.

| 지표 | 캐시 비활성 | 캐시 활성 |
|---|---:|---:|
| 좌석 조회 p95 | 7,261ms | 996ms |
| 전체 요청 p95 | 7,513ms | 1,107ms |
| MySQL 전체 좌석 조회 | 1,701회 | 229회 |
| 버려진 반복 실행 | 463건 | 0건 |
| single-flight 대기 참여 | 0회 | 1,696회 |

혼합 부하에서는 캐시가 조회 지연과 MySQL 전체 좌석 조회를 줄였다. 콜드 캐시 2,000건 집중 부하에서는 응답 경로의 용량 한계가 나타났다. 현재 캐시는 선택한 인기 공연의 조회 모델로 유지하며 두 조건을 별도의 서비스 목표로 관리한다.

근거: [인기 공연 캐시 활성·비활성 시험의 의미](../implements/seat-availability/146.6.3-popular-cache-toggle-test-meaning.md)

### 8.5 Redis 명령 비용을 분리해 full snapshot의 비용 중심을 확인했다

상태 변경률이 높은 인기 공연에서 Redis 비용을 원시 명령과 현재 구현의 Lua 연산으로 나누어 측정했다. 애플리케이션 코드와 기존 부하 테스트 파일은 수정하지 않고 Redis 7.4.9 격리 컨테이너를 사용했다.

| 경로 | 시험 결과 | 판단 |
|---|---:|---|
| 무효화 Lua `INCR + DEL 2회` | clients 50, p99 1.511ms | 무효화 명령 자체의 비용은 작음 |
| 조건부 snapshot 저장 Lua | 305KB, clients 50, p99 95.487ms | 대형 snapshot 입력·저장 비용이 커짐 |
| snapshot `MGET` | 305KB, 5,000건, p99 10.871ms | 약 1.53GB 출력과 꼬리 지연 관찰 |

조건부 저장 5,000건에서는 Redis 입력이 약 1.53GB, main thread CPU 증가가 6.729초였다. 유효한 snapshot `MGET` 5,000건에서는 Redis 출력이 약 1.53GB, main thread CPU 증가가 0.806초였다. 이 수치는 loopback Docker 환경의 Redis 단독 결과이며, 애플리케이션 JSON 처리·Tomcat 응답·OCI 네트워크는 포함하지 않는다.

현재 구현에서 상태 변경마다 발생하는 무효화는 회차 version `INCR`와 snapshot 삭제다. 다음 cache miss의 owner가 MySQL projection 뒤 full snapshot을 조건부로 저장한다. 따라서 `INCR + DEL`을 일괄 처리하는 것보다 상태 변경 version마다 발생하는 full snapshot 재작성과 조회 payload를 먼저 관리해야 한다.

`S`를 snapshot 크기, `W`를 상태 변경률, `R`을 좌석 조회률, `Q`를 실제 재구축 횟수로 두면 payload 비용은 `Q × S` 입력과 `R × S` 출력으로 계산한다. single-flight는 같은 version의 동시 miss를 하나의 재구축으로 합친다. 상태 변경으로 version이 계속 증가하면 version별 재구축 비용은 남는다.

현재 결과는 Redis 자체가 혼합 시험의 `W=50/s`, `R=100/s`를 즉시 처리하지 못한다는 근거를 제공하지 않는다. 고정 입력률, snapshot 크기 변화, OCI 네트워크, 애플리케이션 직렬화가 포함된 후속 시험 뒤 전체 용량을 확정한다.

근거: [Redis 명령·좌석 snapshot 비용 격리 시험](../implements/seat-availability/146.6.6-redis-command-capacity-isolated-test.md)

### 8.6 Redis 장애는 제한된 데이터베이스 대체 조회와 503으로 격리했다

Redis 오류 때 모든 요청이 MySQL로 이동하면 캐시 장애가 데이터베이스 장애로 확산될 수 있다. 회차별 데이터베이스 대체 조회 동시 실행 상한을 5로 제한했다. 상한을 넘은 요청은 `503 Service Unavailable`로 종료한다.

Redis 장애 중 직접 동시 요청 2,000건은 성공 112건과 503 1,888건으로 모두 분류됐다. 보호 동시성 30 시험도 계약을 통과했다. 이 결과는 장애 중 성공률 향상보다 MySQL로 들어가는 요청 수의 상한과 설명 가능한 실패 응답을 검증한 근거다.

## 9. 일곱 번째 질문: 디스크 입출력과 일괄 처리를 우선해야 하는가

인기 공연의 좌석 상태 변경이 1~2분에 집중된다는 관찰에서 디스크 입출력을 일괄 처리하면 큰 효과가 있을 수 있다는 초기 가설을 세웠다. 쓰기와 조회를 분리해 측정하면서 가설을 수정했다.

### 9.1 `AVAILABLE → LOCKED` 쓰기 기준선

초당 10건, 2분 시험에서 1,201건 중 1,200건이 성공했고 예상 충돌은 1건이었다. 쓰기 p95는 46.755ms, p99는 126.866ms였다. InnoDB 데이터 쓰기와 로그 동기화는 발생했고, 로그 대기와 행 잠금 대기는 0이었다.

### 9.2 결제 확정 기준선

초당 10건, 2분 결제 확정 시험에서 1,172건을 시도했다. 클라이언트 성공은 1,122건, 전송 시간 초과는 50건이었다. 데이터베이스 최종 상태는 확정 1,164건과 결제 대기 36건이었다. p95는 2.15초, p99는 15.55초였다. 클라이언트 응답 집합과 서버 커밋 집합이 달랐고 로그 대기는 0이었다.

### 9.3 현재 판단

선행 쓰기 활동은 관측됐다. 디스크 입출력이 사용자 지연의 주원인이라는 근거는 확보되지 않았다. `AVAILABLE → LOCKED`는 좌석 승패와 점유권을 즉시 확정해야 하므로 판매 종료 시점까지 모아서 처리하는 방식은 현재 계약에 적용하지 않는다.

`LOCKED → RESERVED`, 만료 해제, 알림·감사 처리는 별도 처리 단위로 시험할 수 있다. 비동기 작업자와 일괄 처리는 상태 확정 시간, 사용자 응답 시간, 처리 적체량, 만료 지연, 재처리 계약을 함께 측정한 뒤 결정한다.

쓰기 선행 기록(Write-Ahead Logging, WAL)은 데이터 페이지보다 먼저 복구용 로그를 기록하는 원칙이다. MySQL InnoDB의 redo log는 충돌 승패나 좌석 현황을 전달하는 메시지가 아니다. 커밋 내구성과 비동기 이벤트 전파는 서로 다른 책임이다.

근거: [인기 공연 `LOCKED` 이후 쓰기 전략](../architecture/hot-performance-locked-state-write-strategy.md) · [WAL·캐시 사고 흐름](../implements/seat-availability/146.6.3-seat-availability-wal-cache-thought-record.md)

## 10. 여덟 번째 질문: Outbox와 Message Bus를 지금 적용해야 하는가

트랜잭셔널 아웃박스(Transactional Outbox)는 업무 데이터 변경과 발행할 이벤트를 같은 데이터베이스 트랜잭션에 저장하고 별도 발행기가 전달하는 방식이다. 메시지 버스(Message Bus)는 생산자와 소비자 사이에서 메시지를 전달하는 비동기 통신 기반이다.

현재 좌석 캐시 무효화는 애플리케이션 내부 `AFTER_COMMIT` 이벤트로 동작한다. 프로세스가 커밋 직후 종료되면 Redis 무효화가 누락될 수 있다. 프로세스 종료 시험에서 이 경로와 TTL 기반 복구 시간이 확인됐다. 다음 다섯 조건을 도입 게이트로 정했다.

1. 좌석 화면의 오래된 상태가 합의한 최신성 목표를 초과하고 자동 재처리가 필요하다.
2. 반복 상태 변경이 MySQL·HikariCP 보호 기준을 깨뜨린다.
3. 조회 모델 외에 알림·분석·감사 같은 독립 소비자가 생긴다.
4. 소비자 중단 뒤 미처리 이벤트 회수와 처리 위치 복원이 필요하다.
5. 상태 변경 집중 구간을 동기 예약 처리량과 분리해야 한다.

일관성 검토는 다음 순서로 읽는다. 먼저 현재 구현의 commit·rollback 경계를 확인하고, version 조건부 저장으로 재구축 경합을 확인한다. 그 다음 프로세스 종료·전달 실패 때 남는 stale window를 Outbox 계획과 도입 판정 결과로 비교한다.

1. [148.1 W3 상태 변경 최신성](../implements/seat-availability/148.1-w3-state-change-freshness.md)
2. [single-flight·version 조건부 저장 설계](../implements/seat-availability/146.6.4-single-flight-versioned-snapshot-design.md)
3. [148.3 AFTER_COMMIT 무효화 실패·프로세스 종료 재현 결과](../implements/seat-availability/148.3-after-commit-cache-invalidation-failure-test.md)
4. [148.2 Transactional Outbox 계획](../implements/seat-availability/148.2-transactional-outbox-plan.md)
5. [148.2 Message Bus 도입 판정 결과](../implements/seat-availability/148.2-message-bus-adoption-gate-test-result.md)

2026-09-01 검증에서 Redis Streams의 `XPENDING`, `XAUTOCLAIM`, `XACK`, `XTRIM` 자료구조 동작은 통과했다. 현재 애플리케이션에는 생산자, 소비자, 가용성 프로젝터(Availability Projector), Outbox가 없다. Redis 영속성과 복제도 운영 조건을 충족하지 않았다.

다섯 게이트에서 도입을 확정할 운영 근거가 확인되지 않았다. 현재 구조를 유지한다.

```text
현재
MySQL 커밋
  → AFTER_COMMIT 버전 증가·스냅샷 삭제
  → 다음 조회가 MySQL에서 재구축

도입 게이트 충족 뒤 검토
MySQL 상태 변경 + Outbox 이벤트 저장
  → 발행기
  → Message Bus
  → 조회 모델·알림·분석 소비자
```

Redis Streams는 도입 게이트가 다시 열릴 때 첫 기능 검증 후보다. 운영 채택 상태는 아니다. 장기 보존, 여러 소비자 그룹, 파티션 단위 확장이 요구되면 Kafka를 비교한다.

근거: [Message Bus 도입 판정 결과](../implements/seat-availability/148.2-message-bus-adoption-gate-test-result.md) · [ADR-0004 Redis Streams 조건부 PoC](../architecture/decisions/0004-redis-streams-first-message-bus-poc.md)

## 11. 현재 아키텍처

### 11.1 입장과 상태 전달

```text
POST /waiting-room/{회차}/join
  → Waiting Room Service
  → Redis Lua 등록
  → WAITING 티켓
  → SSE 상태 전달 + 15초 status 재조정
  → 승급 스케줄러
  → ADMITTED + 입장권
```

SSE는 상태 변경을 즉시 전달한다. status 재조정은 Redis의 현재 ticket 상태를 15초마다 다시 읽어 Pub/Sub 전달 누락과 브라우저 재연결 뒤 화면을 복구한다. 이 요청은 Waiting Room Service와 Redis에서 처리되므로, Nginx와 서비스 분리가 Reservation Application의 Tomcat·HikariCP에 대기 사용자 상태 조회가 닿지 않게 하는 경계가 된다. 현재 프런트엔드와 같은 15초 재조정 조건의 상태 조회 RPS·Redis 비용은 후속 측정 항목이다.

### 11.2 좌석 조회

```text
GET /seats/{회차}
  → 입장권 검증
  → Redis 스냅샷 조회
       ├─ 적중: 반환
       └─ 누락: 같은 JVM single-flight
                 → 회차 버전 읽기
                 → MySQL 전체 좌석 조회
                 → 버전 조건부 저장
  → Redis 오류: 데이터베이스 대체 조회 상한 5
       ├─ 허용: MySQL 조회
       └─ 초과: 503
```

### 11.3 좌석 선점

```text
POST /pre-reserve
  → 입장권 검증
  → 데이터베이스 멱등성 처리권 획득·재생
  → 좌석별 진입 제어
  → 설정된 잠금 전략
  → MySQL 트랜잭션
       → AVAILABLE 검사
       → LOCKED 전이
       → Reservation·ReservedSeat·응답 스냅샷 커밋
```

멱등성(idempotency)은 같은 사용자 의도의 재요청을 최초 결과로 수렴시키는 성질이다. 처리권 레코드, 정규화 요청 해시, 응답 스냅샷, 임대 시간, 처리 시도 토큰을 사용한다. 좌석 잠금은 여러 요청의 경쟁을 다루고, 멱등성은 한 사용자 의도의 재전송을 다룬다.

### 11.4 좌석 상태 변경과 조회 반영

```text
pre-reserve: AVAILABLE → LOCKED
결제 완료: LOCKED → RESERVED
예약 만료: LOCKED → AVAILABLE
  → MySQL commit
  → AFTER_COMMIT
  → 회차 버전 증가·Redis 스냅샷 삭제
```

### 11.5 저장소별 권한

| 저장소 | 권한과 책임 |
|---|---|
| Redis Waiting Room | 입장 순번, 활성 세션, 티켓 생명주기 |
| Redis 좌석 스냅샷 | 화면에 제공할 좌석 현황 조회 |
| MySQL Seat | `AVAILABLE`, `LOCKED`, `RESERVED` 권위 상태 |
| MySQL Reservation | 결제 대기, 완료, 만료 권위 상태 |
| MySQL Idempotency | 중복 요청 처리권과 최초 응답 재생 |
| 애플리케이션 메모리 | 단일 JVM 잠금, single-flight, 커밋 이후 이벤트 전달 |

상세 호출 순서와 저장소 경계는 [현재 티켓팅 아키텍처 전체 흐름](../architecture/current-ticketing-architecture-flow.md)에서 확인한다.

## 12. 시간순 의사결정 로그

| 순서 | 관찰한 문제 | 당시 선택 또는 가설 | 판단을 바꾼 근거 | 현재 결정 | 상태 |
|---:|---|---|---|---|---|
| 1 | 같은 좌석의 다중 성공 가능성 | MySQL 비관적 잠금 | 성공 한 건을 보장했으나 행 잠금·연결 대기 발생 | MySQL을 최종 정합성 경계로 유지 | 채택 |
| 2 | 패배 요청도 데이터베이스에서 대기 | 공정 `ReentrantLock` | MySQL 대기 0, Tomcat 200과 전송 시간 초과 938건 | JVM 대기 제어와 MySQL 정합성 책임 분리 | 채택 |
| 3 | 잠금 대기에서 응답 유실 | 좌석별 진입 제어 | 전송 시간 초과 0, 진입 이전 DB 접근에서 HikariCP 30/167 | 첫 DB 조회 이전의 제품 진입 경계 필요 | 대체 |
| 4 | 대기를 요청 처리 앞에 배치 | Redis Streams 예약 큐 | 입장 티켓과 예약 작업 티켓의 상태 수명 중복 | 입장 Waiting Room과 동기 선점 | 예약 큐 종료 |
| 5 | 순번·재접속·원자 전이 필요 | Redis ZSET·Hash·Lua | 저장 방식 비교와 상태 계약 검증 | Redis 입장 상태 유지 | 채택 |
| 6 | 입장 요청이 예약 서버 자원 사용 | Nginx 전달률 제어와 별도 서비스 | Tomcat 200 → 30~32, HikariCP 대기 0 | Waiting Room Service 분리 | 채택 |
| 7 | 입장 등록 수락 지연 | Redis Streams 비동기 입장 등록 | 동기 대비 p95 29.4% 증가, Tomcat 200 동일 | 동기 Lua 등록·SSE 상태 전달 | 비동기 경로 종료 |
| 8 | 활성 세션 안전 범위 | 상한 50 | HikariCP 연결 대기 20 | 상한 30 유지 | 채택 |
| 9 | 2,000명 전체 흐름 | Waiting Room 종료 기준 | 2026-08-24 계약 100% | 당시 보호 구조 종료 근거 | 완료 |
| 10 | 반복 좌석 조회의 MySQL 사용 | 회차별 Redis 스냅샷 | MySQL 조회 2,000 → 0, HikariCP 대기 166 → 0 | 캐시 어사이드 조회 모델 | 채택 |
| 11 | 커밋·롤백과 캐시 최신성 | 커밋 이후 스냅샷 삭제 | 커밋 반영·롤백 무효화 0 확인 | 버전 증가와 커밋 이후 무효화 | 채택 |
| 12 | 동시 캐시 누락의 중복 조회 | single-flight | 통제 300건에서 MySQL 조회 301 → 1 | 같은 JVM 재구축 합치기 | 채택 |
| 13 | 재구축 중 오래된 스냅샷 저장 | 회차 버전 조건부 저장 | 버전 불일치 저장 거부 검증 | 버전 조건부 저장 | 채택 |
| 14 | 콜드 캐시 2,000건 | 캐시가 전체 응답도 개선할 것이라는 가설 | MySQL 조회 4회, 성공 101건, 전송 실패 1,867건 | 응답 용량을 별도 문제로 분리 | 미해결 경계 |
| 15 | 상태 변경·조회 혼합 부하 | 선택적 인기 공연 캐시 | 조회 p95 7,261 → 996ms, MySQL 조회 1,701 → 229 | 혼합·웜 캐시 조건에서 유지 | 채택 |
| 16 | Redis 장애의 MySQL 확산 | 제한 없는 대체 조회 | 장애 시험에서 요청 분류와 DB 상한 필요 | 회차별 상한 5와 503 | 채택 |
| 17 | 짧은 기간의 쓰기 집중 | 디스크 입출력 일괄 처리 가설 | 쓰기 p95 46.755ms, 로그·행 잠금 대기 0 | 일괄 처리 보류, 계층별 측정 유지 | 보류 |
| 18 | 커밋 이후 무효화 유실 가능성 | Outbox·Message Bus | 명령 실패·프로세스 종료에서 stale 응답 재현, TTL 잔여 17.7초·관측 복구 19초 | 현재 무효화 유지, Redis Streams 조건부 후보 | 보류 |
| 19 | 캐시 통합 뒤 전체 흐름 용량 | 2,000 VU 유지 가설 | 2,000 실패, 1,500 업무 흐름 통과 | 1,500 보수적 기준선, 최종 용량 재검증 | 진행 중 |
| 20 | Redis 명령과 snapshot payload 비용 | 명령별 비용을 분리 측정 | 무효화 Lua p99 1.511ms, 조건부 저장 p99 95.487ms, MGET p99 10.871ms | full snapshot 재작성·전송을 우선 후속 측정 | 부분 확인 |

세부 인과관계와 원문은 [아키텍처 의사결정 연대기](imticket-architecture-decision-chronology.md)에 기록한다.

## 13. 현재 완료 범위와 후속 제안

### 13.1 구현·검증 완료

- MySQL 기반 동일 좌석 단일 승자와 다중 좌석 원자성
- 좌석별 진입 제어와 명시적 429
- Redis 입장 대기 상태와 서명 입장권
- Nginx Gateway와 별도 Waiting Room Service
- 동기 입장 등록, SSE 상태 전달, 상태 조회 복구
- 활성 세션 30의 Reservation Application 보호 기준
- Redis 좌석 현황 스냅샷과 회차별 적용 정책
- 커밋 이후 버전 증가·스냅샷 무효화
- 같은 JVM single-flight와 버전 조건부 저장
- Redis 장애 시 데이터베이스 대체 조회 상한 5와 503

### 13.2 측정으로 확인한 적용 범위

- 웜 캐시와 혼합 부하에서 MySQL 조회·HikariCP 대기·좌석 조회 지연 감소
- 통제된 콜드 캐시에서 MySQL 재구축 301회에서 1회로 감소
- 2,000건 콜드 캐시 집중 부하에서 HTTP 응답 용량 미충족
- Redis 단독 시험에서 무효화 명령과 full snapshot payload의 비용 분리
- 최신 전체 흐름에서 1,500 VU 업무 계약 통과, 승급 처리율 목표 미충족

### 13.3 제안·보류

- 정적 좌석 배치와 동적 좌석 상태 분리: 응답 크기·직렬화·Redis 명령·클라이언트 조합 비용을 측정한 뒤 결정
- Waiting Room 승급 후보 조회 파이프라인: 기존 Lua 상태 전이를 유지하고 Redis 왕복 감소를 비교. [후속 우선순위 조사](../implements/waiting-room/146.17-waiting-room-next-priority-review.md)에서 SSE 상태 재조정·승급 처리율·장애 복구 순서를 확인한다.
- 결제 확정 작업자와 만료 해제 일괄 처리: 상태 확정 시간·처리 적체·재처리 계약을 측정한 뒤 결정
- Transactional Outbox·Message Bus: 최신성 목표 초과, 독립 소비자, 자동 재처리 요구가 확인되면 도입 게이트 재개
- 다중 애플리케이션 인스턴스: 실제 수평 확장·무중단 배포 요구가 생기면 MySQL 잠금과 전체 연결 예산을 재검증

## 14. 아키텍처·의사결정 문서 지도

### 현재 구조를 이해할 때

1. [현재 티켓팅 아키텍처 전체 흐름](../architecture/current-ticketing-architecture-flow.md)
2. [현재 예매 아키텍처](../architecture/146-current-reservation-architecture.md)
3. [Waiting Room 전체 흐름](../implements/waiting-room/146.14-waiting-room-flow.md)
4. [좌석 현황 캐시 전체 흐름](../implements/seat-availability/148.4-seat-availability-cache-flow.md)
5. [좌석 현황 캐시 전략 조사와 선택](../architecture/seat-map-cache-strategy-recommendation.md)
6. [single-flight·Snapshot Version 조건부 저장 설계](../implements/seat-availability/146.6.4-single-flight-versioned-snapshot-design.md)
7. [좌석 조회 종합 결과](../implements/seat-availability/148.1-results-and-decision.md)

### 판단이 바뀐 이유를 확인할 때

1. [예약 아키텍처 의사결정 연대기](imticket-architecture-decision-chronology.md)
2. [Waiting Room 의사결정 로그](../implements/waiting-room/146.13-waiting-room-decision-log.md)
3. [깊이 있는 문제 해결 사례](imticket-narrative/imticket-deep-problem-solving-cases.md)

### 개별 결정을 확인할 때

1. [ADR-0001 입장 대기열과 동기 선점](../architecture/decisions/0001-entry-waiting-room-and-synchronous-pre-reserve.md)
2. [ADR-0002 애플리케이션 토폴로지별 잠금 경계](../architecture/decisions/0002-lock-boundary-by-application-topology.md)
3. [ADR-0003 데이터베이스 기반 멱등성](../architecture/decisions/0003-database-backed-pre-reserve-idempotency.md)
4. [ADR-0004 Redis Streams 조건부 PoC](../architecture/decisions/0004-redis-streams-first-message-bus-poc.md)
5. [ADR-0005 좌석 현황 캐시 전략](../architecture/decisions/0005-seat-availability-cache-boundary.md)
6. [단일 서버 메모리 Waiting Room 대안](../implements/waiting-room/146.18-single-server-memory-queue-decision.md)
7. [메모리·Redis embedded Tomcat API 비교](../implements/waiting-room/146.18-single-server-memory-queue-decision.md#10-embedded-tomcat-api-비교-결과)

### 수치와 실패를 다시 확인할 때

| 질문 | 원시 근거 |
|---|---|
| 잠금별 대기 위치 | [잠금 전략 비교](../experiments/lock-strategy/95-lock-strategy-comparison-results.md) |
| 좌석별 진입 제어 전후 | [진입 제어 재측정](../experiments/admission/109-seat-admission-remeasurement-decision.md) |
| 예약 큐의 구현과 종료 | [보관 색인](../archive/145-retired-reservation-stream-queue/README.md) |
| Waiting Room 종료 결과 | [2026-08-24 최종 검증](../test/2026-08-24-waiting-room-final-closure.md) |
| 좌석 캐시 W0~W5 | [148.1 종합 결과](../implements/seat-availability/148.1-results-and-decision.md) |
| single-flight·버전 결과 | [146.6.4 결과](../implements/seat-availability/146.6.4-single-flight-versioned-snapshot-result.md) |
| 캐시 활성·비활성 최신 비교 | [실험의 의미](../implements/seat-availability/146.6.3-popular-cache-toggle-test-meaning.md) |
| Message Bus 도입 보류 | [도입 판정 결과](../implements/seat-availability/148.2-message-bus-adoption-gate-test-result.md) |

## 15. 포트폴리오에서 사용할 흐름

대기열과 캐시를 한 사례로 길게 설명할 때는 다음 순서를 사용한다.

```text
동일 좌석 단일 승자
  → 잠금 대기 위치 측정
  → 좌석별 진입 제어
  → 진입 이전 데이터베이스 포화 발견
  → Redis Streams 예약 큐 구현
  → 두 티켓 생명주기 발견
  → 입장 Waiting Room + 동기 선점으로 전환
  → 단일 서버 메모리 대안 구현·계약·성능 비교
  → embedded Tomcat API p95·handler 동시 처리 최대 비교
  → 순번 조회·재시작·다중 인스턴스 조건으로 Redis 유지
  → 별도 Waiting Room Service
  → 비동기 입장 등록 제거
  → 운영값 50 실패, 30 채택
  → 반복 좌석 조회 발견
  → 캐시 어사이드 조회 모델
  → 캐시 스탬피드
  → single-flight + 버전 조건부 저장
  → 콜드 캐시 2,000건 실패
  → 혼합 부하의 적용 가치 확인
  → Redis 장애의 대체 조회 상한과 503
  → Message Bus 도입 게이트 미충족·보류
```

결과만 요약하는 이력서와 판단 과정을 보여주는 포트폴리오의 길이는 다르게 가져간다. 실제 장문 원고는 [ImTicket 깊이 있는 문제 해결 사례](imticket-narrative/imticket-deep-problem-solving-cases.md)의 사례 1과 사례 4를 사용한다.

## 16. 학습 완료 확인 질문

다음 질문에 근거 수치와 함께 답할 수 있으면 대기열·캐시 흐름을 이해한 것이다.

1. MySQL 비관적 잠금이 여러 인스턴스의 최종 정합성 경계인 이유는 무엇인가?
2. `ReentrantLock`이 MySQL 대기를 줄인 뒤 Tomcat 포화를 남긴 이유는 무엇인가?
3. 좌석별 진입 제어의 429와 좌석 충돌의 409는 어떤 상태를 뜻하는가?
4. 좌석별 진입 제어를 적용한 뒤 첫 데이터베이스 조회 이전에 어떤 포화가 남았는가?
5. 입장 대기 티켓과 예약 작업 티켓의 생명주기는 어떻게 다른가?
6. Redis Streams 예약 큐를 종료한 제품 판단은 무엇인가?
7. Waiting Room의 ZSET·Hash·Lua가 각각 어떤 상태를 관리하는가?
8. Nginx와 별도 Waiting Room Service가 어떤 실행 자원을 분리하는가?
9. 비동기 입장 등록을 제거한 측정 근거는 무엇인가?
10. 활성 세션 50과 30에서 HikariCP 결과가 어떻게 달랐는가?
11. 2026-08-24의 2,000명 종료 결과와 최신 캐시 통합 용량 결과를 어떻게 구분하는가?
12. 좌석 캐시가 MySQL 조회와 HTTP 응답에서 각각 줄인 비용은 무엇인가?
13. 커밋 이후 무효화가 롤백과 캐시 최신성을 어떻게 분리하는가?
14. single-flight의 owner와 joiner는 무엇을 공유하는가?
15. 버전 조건부 저장이 오래된 스냅샷의 저장을 막는 원리는 무엇인가?
16. MySQL 조회가 1,431회에서 4회로 줄어도 캐시 활성 성공이 101건이었던 이유 후보는 무엇인가?
17. 혼합 부하에서 캐시 적용 가치를 판단한 수치는 무엇인가?
18. Redis 장애 시 대체 조회 상한 5와 503이 보호하는 자원은 무엇인가?
19. InnoDB 쓰기 활동이 관측됐어도 디스크 입출력을 주 병목으로 확정하지 않은 이유는 무엇인가?
20. Outbox와 Message Bus 도입 게이트 다섯 가지는 무엇인가?
21. 단일 서버 메모리 대안에서 `AtomicLong`, `ConcurrentSkipListMap`, `ConcurrentHashMap`, `Semaphore`가 각각 어떤 계약을 담당하며, 현재 Redis를 유지한 조건은 무엇인가?
22. 저장소 연산 p95와 embedded Tomcat API p95를 함께 측정했을 때 어떤 비용과 동시 처리 경계를 구분할 수 있는가?

## 17. 이 안내의 종료점

현재 대기열·캐시의 완료선은 다음과 같다.

```text
입장 대기열
  → Redis 상태와 입장권
  → 별도 Waiting Room Service
  → 동기 입장 등록·SSE·상태 조회
  → 활성 세션 30으로 예약 애플리케이션 보호

좌석 조회 캐시
  → 캐시 어사이드
  → 커밋 이후 버전 증가·무효화
  → 같은 JVM single-flight
  → 버전 조건부 저장
  → 데이터베이스 대체 조회 상한 5·503
  → 웜 캐시·혼합 부하 효과 확인
  → 콜드 캐시 2,000건과 최신 전체 흐름 용량은 후속 경계
```

정적·동적 좌석 정보 분리, 승급 스케줄러 파이프라인, 결제 확정 작업자, 만료 해제 일괄 처리, Outbox·Message Bus는 제안 또는 보류 상태다. 새로운 측정이 현재 책임 경계나 적용 범위를 바꿀 때 이 안내와 의사결정 연대기를 함께 갱신한다.
