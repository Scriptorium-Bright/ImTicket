# ImTicket 예약 경로와 좌석 선점 문제

## 1. 이 기능이 다루는 문제

ImTicket은 공연 좌석 조회부터 예약, 결제, QR 입장까지를 제공하는 티켓팅 백엔드다. 이 글에서 다루는 범위는 사용자가 좌석을 선택하고 결제 화면으로 이동하기 전까지의 `pre-reserve` 경로다.

좌석 선점은 단순히 `AVAILABLE`을 `LOCKED`로 바꾸는 작업이 아니다. 인기 좌석에는 짧은 시간 안에 동일한 요청이 몰리고, 네트워크가 끊긴 사용자는 같은 버튼을 다시 누르거나 클라이언트가 요청을 재전송한다. 서버는 다음 세 가지를 동시에 만족해야 한다.

| 책임 | 서버가 보장해야 하는 결과 |
| --- | --- |
| 좌석 정합성 | 같은 공연 회차와 좌석 조합에 대해 임시 예약은 한 건만 생성한다. |
| 과부하 응답 | 선점에 실패할 요청이 무작정 DB와 서버 thread를 점유하지 않도록, 이유가 있는 응답으로 종료한다. |
| 요청 재전송 | 같은 사용자의 같은 의도가 다시 들어와도 예약을 중복 생성하지 않고 최초 결과로 수렴시킨다. |

처음에는 첫 번째 문제만 해결하면 된다고 생각했다. MySQL row를 비관적으로 잠그면 여러 application instance에서도 하나의 transaction만 좌석 상태를 바꿀 수 있기 때문이다. 실제 부하를 만들자 병목은 곧바로 두 번째 문제로 이어졌다. 중복 예약은 막혔지만, 이미 실패가 정해진 요청이 409를 받기까지 수 초에서 10초 이상 기다렸다. 이후에는 대기를 어디에 둘지, 그 대기를 어느 시점에 끊을지, 결제와 만료가 만났을 때 어떤 상태를 남길지를 순서대로 다시 설계했다.

## 2. 시스템 구성

현재 검증 대상은 Spring Boot application 한 대와 MySQL 8, Redis로 구성한 단일 서버 구조다. Redis는 현재 SMS 인증 코드를 1분 TTL로 보관하는 용도이며, 좌석 선점의 정합성 판단은 MySQL과 application 내부의 제어 경로에 둔다. Prometheus와 Grafana는 Actuator/Micrometer 지표를 수집한다.

```text
Browser · k6
      │
      ▼
Spring Boot application
  ├── Reservation API
  ├── Payment API
  ├── Expiration Scheduler
  │
  ├── MySQL 8
  │     └── Seat · Reservation · ReservedSeat · PaymentOrder
  ├── Redis
  │     └── SMS verification code · TTL 1 minute
  └── Actuator / Micrometer
            │
            ▼
     Prometheus → Grafana
```

이 배치는 이후 선택의 적용 범위를 분명히 한다. JVM 내부의 `ReentrantLock`은 application 한 대 안에서는 같은 좌석 요청을 줄 세울 수 있지만, application을 둘 이상 두면 각 JVM이 서로 다른 lock map을 가진다. 반대로 MySQL row lock은 모든 instance가 공유하는 DB transaction 경계다. 따라서 단일 서버에서의 응답 성능을 개선하는 선택과, 다중 application 환경의 최종 정합성을 보장하는 선택은 처음부터 구분해서 다뤄야 했다.

## 3. 실제 예약 요청이 지나가는 코드

예약 API의 진입점은 `ReservationController`의 `POST /api/reservation/pre-reserve`다. 컨트롤러는 요청을 `ReservationPreReserveService`로 전달하고, 서비스는 좌석을 잠그기 전에 요청 자체가 새로운 의도인지부터 확인한다.

```text
ReservationController
    │
    ▼
ReservationPreReserveService
    ├── Idempotency-Key claim / replay 판단
    ├── SeatAdmissionService
    └── ReservationIdempotentCreationService
            ├── @ReservationLock
            └── ReservationService
                    ├── Seat row 조회·상태 전이
                    ├── Reservation(PENDING_PAYMENT) 생성
                    └── ReservedSeat 생성
```

`ReservationPreReserveService`는 `(member_id, idempotency_key)`를 기준으로 요청 claim을 만든다. 같은 key로 이미 성공한 요청은 새 예약을 만들지 않고 최초 응답 snapshot을 돌려준다. 같은 key에 다른 좌석이나 회차를 담아 보낸 경우는 의도가 바뀐 요청이므로 충돌로 처리한다.

새 요청일 때만 `SeatAdmissionService`가 해당 좌석의 처리 진입을 판단한다. 이 단계는 성공할 가능성이 없는 대량 요청을 DB transaction까지 보내지 않기 위한 경계다. 통과한 요청은 `ReservationIdempotentCreationService`에서 좌석별 lock과 transaction을 함께 획득한 뒤 `ReservationService`로 들어간다. `ReservationService`는 좌석 ID를 정렬하고 `SeatService.findAndLockSeatsByPerformanceTime()`으로 좌석을 조회한 뒤, 모든 좌석이 `AVAILABLE`인 경우에만 `LOCKED`와 `PENDING_PAYMENT` 예약을 함께 만든다.

다중 좌석 요청에서 ID를 정렬하는 이유도 이 경로에 있다. 두 transaction이 좌석을 서로 다른 순서로 잠그면 1번을 가진 transaction이 2번을 기다리고, 2번을 가진 transaction이 1번을 기다리는 순환 대기가 생길 수 있다. 요청마다 같은 정렬 순서를 사용하면 이 deadlock 가능성을 낮출 수 있다.

### 클래스가 나뉜 기준

클래스를 나눈 기준은 기능을 잘게 쪼개는 데 있지 않았다. 요청 claim을 읽고 재시도하는 단계, 좌석을 잠그고 예약을 만드는 transaction, 결제 승인과 만료를 반영하는 transaction이 서로 다른 상태와 실패를 다루기 때문에 경계를 분리했다.

| 클래스 | 맡은 일 | 따로 둔 이유 |
| --- | --- | --- |
| `ReservationPreReserveService` | member 식별, idempotency claim 생성·재조회·replay, seat admission 호출 | 같은 key 재시도는 좌석 transaction에 들어가지 않고 끝날 수 있어 예약 생성 transaction과 수명이 다르다. |
| `ReservationIdempotencyTransactionService` | claim의 `PROCESSING`·`SUCCEEDED`·실패 상태와 lease 변경 | claim row의 소유권과 재시도 경쟁을 reservation entity 변경과 분리해 DB 경계를 명확히 한다. |
| `ReservationIdempotentCreationService` | 소유한 claim 확인, `@ReservationLock`과 transaction 안에서 예약 생성, 응답 snapshot 저장 | 실제 좌석 선점은 한 transaction에서 lock·좌석 상태·예약·claim 결과를 함께 확정해야 한다. |
| `ReservationService` | 좌석 조회·가용성 검증·`LOCKED` 전이·`PENDING_PAYMENT` 예약 생성 | 선점의 핵심 도메인 규칙을 보유하되 idempotency나 HTTP 응답 replay를 알지 않도록 했다. |
| `ReservationCompletionService` | PG 검증 결과를 예약·좌석·결제 주문의 최종 상태로 반영 | 외부 PG 호출 뒤의 상태 확정은 선점 transaction과 다른 경로이며, 늦은 승인·재검증 replay를 별도로 처리한다. |
| `ReservationExpirationService` | 만료 후보 조회, reservation·seat row lock, `EXPIRED`와 `AVAILABLE` 전이 | scheduler가 아니라 만료 transaction 자체를 담당하게 해 수동 실행과 테스트에서도 같은 잠금 순서를 사용한다. |

그래서 `ReservationService`에 모든 동작을 몰아넣지 않았다. 선점 전 claim 경쟁과 선점 후 결제·만료 경쟁을 한 메서드에 넣으면 transaction 범위와 lock 순서를 설명하기 어려워지고, 한 경로의 변경이 다른 상태 전이에 영향을 준다. 지금의 분리는 클래스 수를 늘리기 위한 구조가 아니라, 각 상태 전이가 어느 transaction에서 확정되는지를 코드와 문서에서 같은 모양으로 보이게 하기 위한 것이다.

## 4. 선점 뒤에는 결제와 만료가 이어진다

임시 선점은 결제 성공이 아니다. 선점이 끝나면 `PaymentPreparationService`가 결제 주문과 시도를 준비하고, `PaymentVerificationService`가 PG의 결제 결과를 서버에서 재검증한다. 그 결과는 `ReservationCompletionService`가 예약, 좌석, 결제 주문에 반영한다.

```text
AVAILABLE
   │ pre-reserve
   ▼
LOCKED + PENDING_PAYMENT
   ├── 결제 검증 성공 ──▶ RESERVED + SUCCESS + APPLIED
   └── 만료 cleanup  ──▶ AVAILABLE + EXPIRED + REFUND_PENDING(늦은 승인 시)
```

만료 작업은 `ReservationExpirationService`가 담당한다. 결제 완료와 cleanup은 같은 예약을 동시에 볼 수 있으므로, 두 경로는 reservation row를 먼저 잠그고 연결된 seat row를 같은 순서로 잠근 뒤 상태와 만료 시각을 다시 검사한다. 먼저 확정한 transaction의 상태만 남고, 뒤따른 transaction은 그 상태를 보고 처리 방향을 바꾼다.

이 구조는 "좌석 락 하나를 무엇으로 둘 것인가"보다 넓은 문제를 보여준다. 선점 경로에서는 경쟁 요청의 대기 위치가 중요했고, 결제 경로에서는 상태 전이의 순서가 중요했으며, 재전송 경로에서는 요청의 동일성을 보존하는 일이 중요했다.

## 5. 이 연재에서 확인한 흐름

연재는 MySQL 비관적 락을 기준선으로 시작한다. 같은 좌석 하나에 요청을 집중시켜 중복 예약이 사라지는지와, 후발 요청이 DB·connection pool·Tomcat 중 어디에서 기다리는지를 함께 측정했다. 그 결과를 바탕으로 낙관적 락, JVM monitor, `ReentrantLock`, MySQL named lock, 단일 executor를 같은 예약 로직에 대입해 비교했다.

비교 결과로 단일 JVM에서는 좌석별 공정 `ReentrantLock`을 선택했고, 이 선택만으로는 Tomcat worker가 lock queue를 기다리는 문제가 남는다는 사실을 확인했다. 이어 좌석별 admission을 넣어 대기 요청을 명시적 429로 정리하고, 한 개가 아닌 네 개의 인기 좌석에도 같은 계약이 유지되는지 검증했다.

마지막에는 단일 서버를 유지한 이유와, application을 여러 대로 늘려야 하는 조건을 분리했다. 수평 확장은 락을 더 고급스럽게 바꾸는 작업이 아니라 공유 정합성 경계, connection budget, ingress 정책을 다시 설계하는 일이다. 그리고 선점이 끝난 뒤에도 남는 결제·만료 경쟁과 멱등성까지 연결해 예약 경로를 닫았다.
