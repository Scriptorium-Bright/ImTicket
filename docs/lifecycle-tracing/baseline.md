# 예약·결제 생명주기 추적 Baseline

작성일: 2026-09-19(KST). 기준: 로컬 `develop`, 커밋 `47f97298cebf0fa3426a464d87b6b88b4b8e88b2`.

관련 문서: [문서 지도](README.md) · [요구사항](requirements.md) · [SLI·SLO 기준선](sli-slo-baseline.md)

이 문서의 Baseline은 예약 생성부터 결제 결과 반영까지의 생명주기(lifecycle)를 개발자가 조사할 때 필요한 확인 작업의 기준값이다. 원격 브랜치 갱신은 수행하지 않았으며, 분석한 애플리케이션·테스트·배포 설정은 해당 커밋과 차이가 없었다.

**결과:** 아래에 고정한 절차에서는 **5개 Entity/Table, DB 조회 5회, 로그 검색 4회, 업무 식별자 6종, 진단 12단계**가 필요하다. 이 중 DB 조회는 격리된 MySQL에서 직접 실행했다. 로그 검색 4회는 동일 테스트 출력에도 수행했으며, HTTP와 스케줄러를 포함하는 운영 진단에 필요한 검색 절차는 코드·설정으로 대조했다. 사람의 진단 시간은 아직 측정하지 않았다.

상태와 코드로 설명할 수 있는 원인은 “승인 정보는 저장됐고, 예약 완료를 반영할 때 만료 조건에 해당하여 환불 대기로 전환됐다”이다. **개별 운영 건에서 스케줄러가 먼저 만료를 커밋했는지까지 현재 상태와 기본 로그로 확정할 수는 없다.** 아래의 경합 테스트에서는 실행 순서를 직접 제어하여 그 순서를 검증했다.

## 1. 진단 대상 시나리오

### 상태와 처리 순서

`PaymentOrder`는 예약에 대한 결제 주문이고, `PaymentAttempt`는 해당 주문의 결제 시도와 승인 정보를 저장한다. `REFUND_PENDING`은 환불 대기, `PAID`는 승인 정보가 기록된 상태다. 이 경로에서 환불 실행 완료까지 확인한 것은 아니다.

| 순서 | 처리 | Reservation | Seat | PaymentOrder | PaymentAttempt |
| --- | --- | --- | --- | --- | --- |
| 1 | 좌석 선점 및 예약 생성 | `PENDING_PAYMENT` | `LOCKED` | 미생성 | 미생성 |
| 2 | 결제 준비 | `PENDING_PAYMENT` | `LOCKED` | `READY` | `READY` |
| 3 | 만료 처리와 결제 결과 반영이 같은 예약의 행 잠금에서 경합 | 결제 대기 | 선점 중 | 준비 완료 | 준비 완료 |
| 4 | 만료 트랜잭션이 먼저 커밋 | `EXPIRED` | `AVAILABLE` | `READY` | `READY` |
| 5 | 대기하던 결제 결과 반영이 진행되고 커밋 | `EXPIRED` | `AVAILABLE` | `REFUND_PENDING` | `PAID` |

행 잠금은 같은 DB 행을 변경하는 트랜잭션들이 동시에 상태를 갱신하지 못하도록 순서를 정하는 잠금이다. 여기서는 두 서비스가 `Reservation`을 먼저 잠그므로, 뒤에 진행하는 결제 반영이 확정된 만료 상태를 읽는다.

진단 질문은 다음과 같다.

> paymentOrderId 하나만 전달받았다. 이 결제는 승인되었는데 왜 예약은 EXPIRED이고 PaymentOrder는 REFUND_PENDING인가?

### 검증한 경합과 코드 근거

[MySqlReservationStateRaceTest](../../src/test/java/org/example/ticket/reservation/booking/service/MySqlReservationStateRaceTest.java)의 `cleanupWinnerPreservesLateApprovalAsRefundPending()`은 다음을 검증한다(184–234행).

1. 이미 만료 기준 시각이 지난 예약, 선점 좌석 2개, 준비된 주문·결제 시도를 테스트 데이터로 만든다.
2. 만료 서비스가 예약과 좌석을 변경한 뒤, 동기화 도구인 `CountDownLatch`로 커밋을 지연한다.
3. 별도 스레드에서 결제 반영을 시작한다. `performance_schema.data_lock_waits`에서 실제 MySQL 잠금 대기를 확인하고, 결제 작업이 300ms 안에 완료되지 않는지도 확인한다.
4. 만료 트랜잭션의 커밋을 허용한 뒤 두 작업의 완료를 기다린다.
5. 네 상태와 승인 거래 ID 보존을 검증한다.

이 테스트는 HTTP API, 결제대행사(PG) 조회, 주기 실행 스케줄러를 거치지 않고 두 서비스를 직접 호출한다. 승인 결과를 담은 `VerifiedPaymentSnapshot`도 경합 시작 전에 만든다. 따라서 검증한 시간 순서는 **만료 커밋 → 승인 결과의 예약·결제 반영**이다. PG의 실제 승인 발생 시각이나 HTTP 도착 순서는 별도 관측 대상이다.

## 2. 현재 시스템에서 확인해야 하는 데이터

### 필수 테이블과 상태

| Entity / 실제 Table | 확인 데이터 | 판단에 필요한 이유 |
| --- | --- | --- |
| `PaymentOrder` / `payment_order` | `id`, `reservation_id`, `merchant_order_id`, `status`, 금액·통화, 생성·갱신 시각 | 전달받은 ID의 출발점. 환불 대기 상태와 예약 연결을 확인한다. |
| `Reservation` / `Reservation` | `id`, `reservation_status`, `reservation_date`, `reservation_expired_time` | 예약 상태와 만료 기준 시각을 확인한다. 실제 만료 처리 시각은 저장하지 않는다. |
| `PaymentAttempt` / `payment_attempt` | `attempt_id`, `provider_transaction_id`, `status`, 승인 금액·통화·시각, 생성·갱신 시각 | 승인 기록이 남아 있는지, 주문과 승인 내용이 일치하는지 확인한다. |
| `ReservedSeat` / `ReservedSeat` | `reservation_id`, `seat_id` | 예약과 좌석의 연결 테이블이다. `Reservation`에서 좌석으로 이동할 때 필요하다. |
| `Seat` / `Seat` | `id`, `seat_status`, `version` | 해당 예약에 연결된 모든 좌석의 현재 해제 상태를 확인한다. |

상태를 확인하는 Entity는 4개이며, 연결 Entity까지 포함한 고유 테이블 수는 5개다. `Reservation`, `Seat`, `PaymentOrder`, `PaymentAttempt`의 상태를 각각 확인해야 요청한 최종 상태 전체를 검증할 수 있다. SQL을 조합하여 조회 횟수를 줄여도 이 네 상태와 연결 관계를 읽어야 한다.

근거: [PaymentOrder](../../src/main/java/org/example/ticket/payment/model/PaymentOrder.java), [PaymentAttempt](../../src/main/java/org/example/ticket/payment/model/PaymentAttempt.java), [Reservation](../../src/main/java/org/example/ticket/reservation/booking/domain/Reservation.java), [ReservedSeat](../../src/main/java/org/example/ticket/reservation/booking/domain/ReservedSeat.java), [Seat](../../src/main/java/org/example/ticket/reservation/booking/domain/Seat.java). 테이블 이름의 대소문자는 [application.properties](../../src/main/resources/application.properties) 26행의 물리 이름 지정 전략과 실제 MySQL 조회 결과를 따른다.

`Member`·공연·공연 회차 테이블은 이 질문의 필수 조회에 포함하지 않는다. 예약 생성 요청의 재시도 여부까지 조사할 때는 [ReservationIdempotency](../../src/main/java/org/example/ticket/reservation/booking/domain/ReservationIdempotency.java)의 `reservation_id`, 상태, 저장된 응답 등을 추가 확인할 수 있다. 이 경우 테이블과 조회가 각각 1개 이상 늘어난다. 멱등성은 같은 요청을 반복해도 같은 처리 결과를 제공하는 성질이며, 해당 테이블에도 HTTP 추적 ID와 만료·결제의 전체 실행 이력은 없다.

### 필요한 식별자와 이동

| 식별자 종류 | 확보 경로 | 용도 |
| --- | --- | --- |
| `paymentOrderId` | 최초 전달값 | 주문과 결제 시도를 조회하고 결제 확인 요청 경로를 검색한다. |
| `reservationId` | `payment_order.reservation_id` | 예약 및 예약·좌석 연결을 조회한다. |
| `seatId` | `ReservedSeat.seat_id` | 연결된 좌석 상태를 조회한다. 여러 좌석도 식별자 종류는 1종이다. |
| `attemptId` | `payment_attempt.attempt_id` | 결제 시도를 구별하고 해당 값이 로그에 남았는지 검색한다. |
| `merchantOrderId` | `payment_order.merchant_order_id` | 애플리케이션 주문과 결제대행사 결제 식별의 연결을 확인한다. |
| `providerTransactionId` | `payment_attempt.provider_transaction_id` | 저장된 승인 거래를 확인하고 관련 로그를 검색한다. |
| HTTP `correlationId` — 조건부 | 응답 헤더 또는 이미 보관된 요청 기록 | 같은 HTTP 요청에 속한 로그를 묶는다. DB에서 바로 복원할 수 없다. |
| 스케줄러 `runId` — 조건부 | 만료 정리 로그 | 동일 스케줄러 실행의 시작·종료·실패를 묶는다. |

DB 연결만 따라가는 데 사용하는 최소 식별자는 앞의 3종이다. 본 문서의 승인 확인·로그 조사 절차에서는 업무 식별자 6종을 취급한다. 조건부 로그 식별자 2종을 확보하면 총 8종이다. `payment_attempt.id`는 조회 결과의 행 식별에 사용하며, 주문에서 시도를 찾아가는 별도 검색 키로 요구하지 않는다. 예약 코드·회원 ID·멱등 키도 이번 기본 절차의 검색 키 수에 포함하지 않는다.

이동 경로는 `paymentOrderId → reservationId → seatId 목록`과 `paymentOrderId → attemptId / providerTransactionId`, `paymentOrderId → merchantOrderId`다. **`paymentOrderId → HTTP correlationId`, `reservationId → scheduler runId`의 영속적인 연결은 없다.**

### 로그 위치와 기록 내용

상관관계 식별자(Correlation ID)는 관련 로그를 같은 값으로 묶는 식별자다. 이 프로젝트는 HTTP 요청 단위 값과 스케줄러 실행 단위 값을 사용한다. MDC는 현재 스레드의 로그에 이러한 부가 정보를 붙이는 문맥 저장소다.

| 확인 위치 | 현재 기록 | 개별 건 진단에서 얻는 정보 |
| --- | --- | --- |
| 애플리케이션 표준 출력. Compose 실행 시 `docker compose logs app` | `[corr:… run:…]` 형식. 별도 파일 목적지를 지정하는 설정은 저장소에 없다. | 요청 문맥이 있는 로그의 연결에 사용할 수 있다. |
| 같은 출력의 `ReservationService` 로그 | 만료 정리 시작·종료·실패, `batchSize`, `reservationCount`, `seatCount` | 실행 단위와 처리 건수. 예약·좌석·결제 주문 ID 목록은 기록하지 않는다. |
| 결제 준비·검증·반영 및 만료 서비스 | 정상 처리의 업무 식별자·상태 변경 사유를 출력하는 로그문이 없다. | 이번 정상 환불 대기 분기를 직접 검색할 기록이 없다. |
| `GlobalExceptionHandler` | 예외 코드·메시지 등 | `REFUND_PENDING`을 정상 반환하는 이번 경로는 예외 로그가 발생할 필요가 없다. |
| 선택적으로 사용하는 Nginx 게이트웨이 표준 출력 | 요청 경로, HTTP 상태, 처리 시간, Nginx `$request_id` | `/api/payments/{id}/verify` 요청 여부를 찾을 수 있다. 요청·응답 본문 및 `X-Correlation-Id`는 현재 형식에 없다. |
| 이번 재현의 JUnit XML `system-out` | 테스트 실행 출력 | 경합 테스트의 실행 근거. HTTP와 스케줄러 실행 기록은 포함하지 않는다. |

주문·예약·스케줄러 로그가 서로 다른 파일에 반드시 나뉘는 구조는 아니다. 기본 진단은 **애플리케이션 로그 1개 계열 안의 여러 코드 위치**를 확인한다. 복수 인스턴스라면 모든 인스턴스와 보관 기간을 검색 범위로 지정해야 한다. 게이트웨이를 경유한 경우에만 게이트웨이 로그 계열을 추가한다.

근거: [ReservationService](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationService.java) 49–83행, [로그 설정](../../src/main/resources/application.properties) 27–50행, [GlobalExceptionHandler](../../src/main/java/org/example/ticket/common/exception/GlobalExceptionHandler.java), [Compose](../../docker-compose.yml), [Nginx 설정](../../infra/nginx/waiting-room-gateway.conf) 10–15행. 기본 설정은 Hibernate SQL 출력을 비활성화하며, 이 경로의 SQL 매개변수·커밋 이력을 수집하도록 구성되어 있지 않다.

## 3. 실제 추적 절차

### 집계 기준

- DB 조회 1회는 진단자가 실행하는 `SELECT` 1문장이다. 애플리케이션 내부 SQL 수, 테스트 준비·검증 쿼리, 연결·세션 설정은 별도로 구분한다.
- 로그 검색 1회는 정해진 로그 범위에 하나의 검색 조건을 적용하는 행위다. 같은 목적의 여러 식별자를 OR 조건으로 묶은 검색은 1회다. 결과가 없어도 1회로 센다.
- 진단 단계는 아래 표의 번호 1개다. 기본 경로는 12단계로 고정한다. 추가 검색·반복 조회·환경 준비는 별도 기록한다.
- 기준 환경은 최종 상태가 안정된 격리 데이터와 예약 애플리케이션 로그다. 다른 사용자의 재선점과 환불 후속 처리로 상태가 바뀌는 운영 조사에서는 일관된 시점의 조회 결과를 보존한다.

### 고정된 12단계

| 단계 | 수행 작업 | 얻는 증거 또는 판단 | 횟수 |
| --- | --- | --- | --- |
| 1 | Q1: 주문 조회 | `REFUND_PENDING`, 예약 ID, 주문 번호, 금액·통화 | DB 1 |
| 2 | Q2: 예약 조회 | `EXPIRED`, 예약 생성 및 만료 기준 시각 | DB 1 |
| 3 | Q3: 주문의 결제 시도 조회 | `PAID`, 승인 거래·금액·통화·시각 | DB 1 |
| 4 | Q4: 예약·좌석 연결 조회 | 좌석 ID 목록 | DB 1 |
| 5 | Q5: 연결된 모든 좌석 조회 | 전부 `AVAILABLE`인지 확인 | DB 1 |
| 6 | L1: 결제 주문 ID/verify 경로 검색 | 주문에서 HTTP 실행 기록으로 이동 가능한지 확인 | 로그 1 |
| 7 | L2: 예약 ID 검색 | 예약 생성·만료의 개별 기록을 찾는다. | 로그 1 |
| 8 | L3: 주문 번호·시도 ID·승인 거래 ID를 함께 검색 | 승인과 주문을 연결하는 개별 기록을 찾는다. | 로그 1 |
| 9 | L4: 만료 정리 로그 검색 | 조사 시간대의 실행 ID·처리 건수 확보 가능 여부 | 로그 1 |
| 10 | 결제 검증·반영 분기와 시도 갱신 코드를 읽는다. | 승인 저장, 만료 조건 평가, 환불 대기 전환의 의미 | 코드 확인 |
| 11 | 만료 처리·잠금 코드와 경합 테스트를 읽는다. | 만료 우선 경합이 해당 결과를 만드는지 확인 | 코드 확인 |
| 12 | Correlation ID 연결을 평가하고 결론을 작성한다. | 상태의 원인 설명과 실제 순서 확정 가능 여부를 기록 | 판단 |

### Q1–Q5: 실행 SQL

아래 값 `1`, 좌석 `1, 2`는 이번 격리 재현에서 확인한 실제 값이다. 다음 재현에서는 Q1·Q4 결과에서 받은 값으로 바꾼다. 이번 주문 ID와 예약 ID가 같은 숫자인 것은 테스트 데이터의 채번 결과다.

```sql
-- Q1. 최초 입력: paymentOrderId
SELECT id, reservation_id, merchant_order_id, status, amount, currency,
       created_at, updated_at
FROM payment_order WHERE id = 1;

-- Q2. Q1의 reservation_id 사용
SELECT id, reservation_status, reservation_date, reservation_expired_time
FROM Reservation WHERE id = 1;

-- Q3. 최초 paymentOrderId 사용. 연결된 시도를 모두 확인한다.
SELECT id, payment_order_id, attempt_id, provider, provider_transaction_id,
       status, approved_amount, approved_currency, approved_at,
       created_at, updated_at
FROM payment_attempt WHERE payment_order_id = 1
ORDER BY created_at DESC, id DESC;

-- Q4. Q1의 reservation_id 사용
SELECT reservation_id, seat_id
FROM ReservedSeat WHERE reservation_id = 1 ORDER BY seat_id;

-- Q5. Q4의 모든 seat_id 사용
SELECT id, seat_status, version
FROM Seat WHERE id IN (1, 2) ORDER BY id;
```

Q3에서 승인 금액·통화를 Q1과 대조한다. 서비스는 [PaymentAttemptRepository](../../src/main/java/org/example/ticket/payment/repository/PaymentAttemptRepository.java)의 `findTopByPaymentOrderIdOrderByCreatedAtDesc()`로 최신 시도를 선택한다. 위 진단 SQL은 시도를 모두 반환하여 다른 시도 존재 여부도 확인한다. 조회 결과 1행이라는 사실과 여러 시도 중 하나를 선택했다는 사실을 구분해 기록한다.

### L1–L4: 로그 검색

실제 실행 환경에서 보관된 예약 애플리케이션 로그를 `APP_LOG`로 지정한다. 여러 파일이면 동일 범위를 모두 넘긴다. 첫 조회 때는 주문 생성부터 마지막 갱신까지에 여유를 둔 구간을 사용하고, 시각 기준을 먼저 확인한다. 해당 구간에서 찾지 못하면 보관된 전체 구간으로 확장하고 추가 검색 1회로 센다.

```bash
# 환경에 맞는 기존 로그 파일과 Q1–Q3에서 확보한 값을 지정한다.
APP_LOG=/path/to/reservation-application.log
ORDER_ID=1
RESERVATION_ID=1
MERCHANT_ORDER_ID=merchant-94164479-5b24-46bd-964d-f55d6268568e
ATTEMPT_ID=attempt-94164479-5b24-46bd-964d-f55d6268568e
PROVIDER_TRANSACTION_ID=provider-94164479-5b24-46bd-964d-f55d6268568e

# L1: 업무 필드 또는 경로를 지정하여 단순 숫자 일치를 줄인다.
rg -n -F -e "paymentOrderId=$ORDER_ID" \
  -e "/api/payments/$ORDER_ID/verify" "$APP_LOG"
# L2
rg -n -F -e "reservationId=$RESERVATION_ID" "$APP_LOG"
# L3: 같은 승인 건의 식별자들을 OR 조건으로 검색한다.
rg -n -F -e "$MERCHANT_ORDER_ID" -e "$ATTEMPT_ID" \
  -e "$PROVIDER_TRANSACTION_ID" "$APP_LOG"
# L4
rg -n -F -e 'Starting expired reservation cleanup.' \
  -e 'Completed expired reservation cleanup.' \
  -e 'No expired reservations found.' \
  -e 'Failed expired reservation cleanup.' "$APP_LOG"
```

`rg`의 종료 코드 1은 검색 결과 없음이다. 파일 접근 실패와 구분한다. 짧은 숫자 ID 검색은 다른 ID의 일부와 일치할 수 있으므로 결과의 필드 전체 값을 대조한다. 로그에 값이 없다는 결과는 이벤트가 실행되지 않았다는 증거로 사용하지 않는다.

기본 4회 뒤의 추가 조건은 다음과 같다. 게이트웨이 로그에서 verify 경로 검색은 +1회, 확보한 HTTP `correlationId`별 검색은 각각 +1회, 후보 `runId`별 재검색은 각각 +1회다. 처리 건수만 있는 스케줄러 로그에서 특정 예약의 포함 여부를 알 수 없으면 “실행 후보 확인, 개별 예약 연결 불가”로 종료한다. 검색을 반복해도 없는 연결 정보가 생기지는 않는다.

### 10–12단계에서 내려야 하는 결론

[PaymentVerificationService](../../src/main/java/org/example/ticket/payment/service/PaymentVerificationService.java) 29–55행은 PG 검증 결과를 결제 반영 서비스로 전달한다. 이미 `REFUND_PENDING`인 주문에 대한 재요청은 기존 결과를 반환할 수 있으므로, verify 요청 로그의 존재만으로 최초 반영 시점을 단정할 수 없다.

[ReservationCompletionService](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationCompletionService.java) 47–96행은 예약 → 좌석 → 결제 주문 순서로 잠금을 얻고, 승인 내용을 검증한 뒤 결제 시도를 `PAID`로 바꾼다. 이후 예약이 `EXPIRED`이거나, `PENDING_PAYMENT`이면서 만료 시각이 지났거나 없는 경우 환불 대기로 처리한다(127–132행). 결제대행사의 `approvedAt`은 만료 분기 판단에 사용하지 않으며, 분기 판단은 예약 상태와 처리 시점의 `now`를 기준으로 한다.

[ReservationExpirationService](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationExpirationService.java) 31–64행은 결제 대기 예약 후보를 잠근 후 만료 조건을 다시 평가하고 예약·좌석만 변경한다. 결제 주문과 시도를 변경하지 않는다. 따라서 이 경합에서는 만료 커밋 후에도 주문·시도가 `READY`로 남고, 후속 결제 반영에서 `PAID`와 `REFUND_PENDING`이 기록된다.

진단 결론에는 다음 두 문장을 구분하여 남긴다.

> 확인한 사실: 승인 정보가 PAID로 저장되어 있고, 예약 완료 적용 시 만료 조건에 해당하여 주문이 REFUND_PENDING으로 전환되는 코드 경로와 현재 상태가 일치한다.
>
> 실행 순서: 이번 제어된 경합 테스트에서는 만료 커밋이 먼저임을 검증했다. 같은 최종 상태의 개별 운영 건은 현재 상태·기본 로그만으로 스케줄러 선행 여부를 확정할 수 없다.

## 4. Baseline 측정 결과

### 이번에 실행한 재현과 결과

2026-09-19 23:50–23:51(KST)에 Java 21.0.6, Testcontainers, MySQL 8.0.46 환경에서 대상 테스트 1개를 실행했다. `clearFixture()`의 첫 실행 줄에 디버거 중단점을 두어 검증 완료 후 데이터를 보존하고 Q1–Q5를 실행했다. 테스트 준비자가 주문 ID를 확보하는 조회는 진단 시작 전 준비 작업으로 구분했다.

| 직접 조회한 항목 | 결과 |
| --- | --- |
| `payment_order.id / reservation_id / status` | `1 / 1 / REFUND_PENDING` |
| `Reservation.id / reservation_status` | `1 / EXPIRED` |
| `ReservedSeat` 연결 | 예약 `1` → 좌석 `1, 2` |
| 두 좌석의 `seat_status / version` | 모두 `AVAILABLE / 1` |
| `payment_attempt` | 1행, `id=1`, `status=PAID`, `provider=FAKE` |
| 주문 금액·통화 / 승인 금액·통화 | 양쪽 모두 `90000 / KRW` |
| 업무 문자열 식별자 | Q1–Q3 결과의 접미사는 모두 `94164479-5b24-46bd-964d-f55d6268568e`이며, 접두사는 각각 `merchant-`, `attempt-`, `provider-` |
| 만료 기준 / 승인 기록 시각 | `23:49:40.409955 / 23:50:40.517563` — SQL 반환값 |
| 주문 / 시도 마지막 갱신 시각 | `23:50:41.030511 / 23:50:41.031003` — SQL 반환값 |
| JUnit 결과 | `tests=1`, `skipped=0`, `failures=0`, `errors=0`, `BUILD SUCCESSFUL` |

초기 실행에서는 Docker 데이터 영역이 100% 사용되어 MySQL이 `OS errno 28 - No space left on device`로 시작하지 못했다. 실패한 테스트 실행을 종료한 뒤, 디버거에서 **해당 일회용 MySQL 컨테이너만** `/var/lib/mysql`을 `tmpfs`로 지정하고 같은 테스트를 재실행했다. `tmpfs`는 메모리에 저장하는 파일 시스템이다. 기존 MySQL 데이터와 애플리케이션·테스트 소스는 변경하지 않았다. 재실행 뒤 중단점을 해제했고 테스트 데이터 및 컨테이너의 자동 정리가 완료됐다.

기존 보고서를 보존하기 위해 임시 Gradle 초기화 스크립트로 결과 경로만 바꿨다. 이번 결과 파일은 `/private/tmp/imticket-lifecycle-baseline.kdkYEe/test-results/TEST-org.example.ticket.reservation.booking.service.MySqlReservationStateRaceTest.xml`이다. 이 임시 파일은 재실행 근거이며, 주요 결과는 위 표에 보존한다.

이번 JUnit 출력에 L1–L4를 각각 적용한 결과는 모두 0건이었다. 이 테스트가 HTTP 필터와 `ReservationService.cleanupExpiredReservation()`을 호출하지 않는다는 범위를 함께 기록한다. 실제 HTTP·스케줄러 실행의 검색 성공률을 이 수치로 일반화하지 않는다. 운영 기본 로그에서 개별 상태 변경을 찾기 어려운 이유는 2절의 실제 로그문과 설정으로 확인했다.

JUnit에 기록된 `71.984초`에는 디버거 중단 시간이 포함된다. Gradle 실행 시간 `2분 15초`에는 테스트 환경 시작과 중단이 포함된다. **두 시간 모두 사람의 원인 확인 시간으로 사용하지 않는다.**

### 횟수 해석

| 측정 대상 | 기준값 | 근거와 적용 범위 |
| --- | --- | --- |
| 고유 Entity/Table | 5 | 실제 조회한 5개. 상태 보유 4개 + 연결 1개 |
| 진단 SELECT | 5회 | Q1–Q5 직접 실행. 여러 좌석은 `IN`으로 함께 조회 |
| 기본 로그 검색 | 4회 | L1–L4. 테스트 출력에 실행했으며, 운영 절차는 같은 검색 조건을 적용 |
| 업무 식별자 | 6종 | DB 이동 3종 + 승인·시도 로그 검색 3종. 조건부 추적 식별자 포함 시 8종 |
| 기본 진단 단계 | 12단계 | 상태 조회 5 + 로그 검색 4 + 코드 확인 2 + 종합 판단 1 |
| 사람의 진단 시간 | 미측정 | 아래 절차로 별도 측정 |

이 수치는 고정한 조사 절차의 비용이다. SQL에 익숙한 개발자가 예약·좌석을 조인하면 4회로 줄일 수 있고, 5개 테이블을 모두 조인하면 현재 상태를 1회에 읽을 수도 있다. 전체 조인에서는 좌석 수와 시도 수에 따라 행이 중복된다. 조회를 합쳐도 과거 실행 순서와 결정 사유가 새로 복원되는 것은 아니다. 기본값과 조인 사용 결과를 섞지 않고 따로 보고한다.

기존 [결제 상태 API](../../src/main/java/org/example/ticket/payment/response/PaymentStatusResponse.java)는 주문·예약 상태와 PG 거래 ID를 반환한다. 좌석 상태, 시도 상태, 승인 시각, 만료 실행 주체·사유는 포함하지 않으며 주문 소유자의 인증도 필요하다. 운영자가 ID 하나로 원인을 확인하는 전용 조회로 사용하기에는 추가 확인이 필요하다.

### 사람이 직접 진단 시간을 측정하는 절차

**준비자와 진단자를 나누고, 환경 준비 시간과 진단 시간을 분리한다.** 준비자는 정답이 “만료 커밋 선행”임을 알고, 진단자에게는 주문 ID와 접근 환경만 전달한다. 계정 인증 정보는 운영 환경의 기존 접근 수단으로 제공한다.

1. 준비자는 위 커밋과 Java 21, Docker를 준비한다. 아래 명령으로 대상 테스트를 디버그 모드에서 실행한다. `clearFixture()` 첫 줄에 중단점을 설정한 뒤 진행한다. 테스트 데이터는 테스트 종료 시 삭제되므로 중단 전에 진단을 시작하지 않는다.

   ```bash
   ./gradlew --offline --console=plain --info test \
     --tests org.example.ticket.reservation.booking.service.MySqlReservationStateRaceTest.cleanupWinnerPreservesLateApprovalAsRefundPending \
     --debug-jvm
   ```

   `--info`로 테스트 표준 출력을 실행 중인 콘솔에서도 확인한다. 준비자는 IDE나 터미널의 출력 저장 기능으로 검색 가능한 로그를 제공한다. JUnit XML은 테스트가 끝난 뒤 생성되므로 중단 상태의 진단에는 저장된 콘솔 출력을 사용한다. 의존성이 로컬에 준비되지 않았다면 사전 준비 단계에서 다운로드한다. 이번처럼 저장 공간이 부족한 환경의 재현은 `org.testcontainers.mysql.MySQLContainer.configure()`에 시작 전 중단점을 두고 다음 식을 평가한 뒤, 해당 중단점을 해제하여 진행한다. 이 조정은 일회용 테스트 컨테이너에만 적용한다.

   ```java
   this.withTmpFs(java.util.Collections.singletonMap("/var/lib/mysql", "rw,size=1g"))
   ```

2. 준비자는 데이터 정리 전 중단 상태에서 테스트 DB 접속 정보, `paymentOrderId`, 기존 로그 접근 위치, 기준 소스만 제공한다. 테스트명·동기화 순서와 위 결과표는 진단자에게 먼저 보여주지 않는다. 이 구성은 DB·코드 조사 시간 측정용이다. HTTP·스케줄러 로그가 없는 테스트의 범위는 측정 결과에 표시한다.
3. HTTP·스케줄러까지 포함하는 별도 측정은 격리된 실행 환경에서 준비한다. `payment.gateway.provider=fake`, `ticket.application.role=reservation`을 사용하고, 유효한 사용자와 좌석으로 `POST /api/reservation/pre-reserve` → `POST /api/payments/prepare`를 호출한다. 각각의 요청 본문은 `{ "performanceTimeId": ..., "seatIds": [...] }`, `{ "reservationId": ... }`이며 서로 다른 `Idempotency-Key`를 사용한다. 대기실이 활성화된 환경은 입장권 조건도 충족해야 한다.
4. 기본 예약 유효 시간 7분이 지나고 스케줄러가 예약을 `EXPIRED`, 좌석을 `AVAILABLE`로 확정할 때까지 준비자가 확인한다. 스케줄러는 이전 실행 종료 후 30초 간격이며 실제 실행 완료를 기준으로 한다. 그 다음 prepare 응답의 `providerPaymentId`로 `POST /api/payments/{paymentOrderId}/verify`를 호출한다. `{ "providerPaymentId": "prepare 응답값" }`을 사용한다. 이 절차는 HTTP를 통한 지연 승인 반영을 재현한다. 동시 잠금 경합의 증명은 앞의 MySQL 테스트 결과로 별도 유지한다.
5. 각 HTTP 응답의 `X-Correlation-Id`와 전체 애플리케이션 로그를 준비자가 보존한다. 기본 비교에서는 클라이언트가 같은 Correlation ID를 세 요청에 주입하지 않는다. ID를 재사용하는 실험은 별도 조건으로 기록한다. 준비자는 최종 네 상태를 확인하고 이후 변경이 일어나지 않게 측정 데이터를 격리한다.
6. 진단자가 `paymentOrderId`를 전달받는 순간 단조 증가 시계 기반 타이머를 시작한다. DB 연결과 로그 접근은 이미 준비된 상태로 한다. 12단계를 수행하면서 SELECT 수, 검색 수·검색 범위, 사용한 식별자, 추가 코드 확인을 기록한다. 설명을 사전 제공한 측정은 “절차 제공”, ID만 전달한 측정은 “절차 미제공”으로 구분한다.
7. 승인 보존과 만료 시 환불 대기 전환 규칙을 근거로 설명하면 **상태 원인 설명 시간**을 기록한다. 특정 예약의 만료 커밋과 결제 반영 순서를 사건별 증거로 확정할 수 있으면 **실행 순서 확정 시간**을 별도로 기록한다. 근거가 없으면 “확정 불가”로 기록한다. 원래 테스트의 정답을 보았다는 이유로 운영 로그에서 확정한 것으로 기록하지 않는다.
8. 한 회의 최대 조사 시간을 사전에 고정한다(예: 15분). 시간이 끝나거나 근거 부족을 판정하면 실제 경과 시간과 종료 사유를 남긴다. 독립된 진단자·새 데이터로 3회 이상 측정하여 원인 설명 시간의 중앙값, 범위, 순서 확정 성공률을 보고한다. 기존 기능에 대한 숙련도와 절차 제공 여부를 동일하게 맞춘다.

| 회차 / 환경 / 절차 제공 여부 | SELECT 수 | 로그 검색 수 | 추가 단계 | 상태 원인 설명까지 초 | 순서 확정까지 초 또는 불가 | 판단 근거 |
| --- | --- | --- | --- | --- | --- | --- |
| 사람 측정 전 | — | — | — | 미측정 | 미측정 | — |

## 5. 현재 추적 방식의 한계

### 현재 상태에서 복원할 수 있는 범위

동일한 최종 상태를 만드는 경로가 두 가지다.

| 경로 | 만료 상태를 확정하는 코드 | 공통 최종 상태 |
| --- | --- | --- |
| A. 스케줄러의 만료 트랜잭션이 선행 | `ReservationExpirationService` | `EXPIRED / AVAILABLE / REFUND_PENDING / PAID` |
| B. 결제 반영 시 아직 결제 대기 상태이며 만료 기준 초과 | `ReservationCompletionService` 77–85행이 예약·좌석도 변경 | `EXPIRED / AVAILABLE / REFUND_PENDING / PAID` |

두 경로를 구별하는 결정 사유, 변경 주체, 변경 전 상태, 만료 확정 시각이 개별 건 이력으로 저장되지 않는다. 결제 주문의 `PAID_UNAPPLIED` → `REFUND_PENDING` 변경도 같은 트랜잭션 안에서 연속으로 수행되므로 중간 상태가 독립된 이력으로 남지 않는다.

`created_at`, `updated_at`, `version`은 상태 전이 목록이나 커밋 순서를 제공하지 않는다. `approved_at`은 PG 승인 정보를 나타내고, 애플리케이션 수신·검증 시작·잠금 획득·커밋 시각은 각각 별개의 시점이다. 이번 재현에서도 `reservation_date`는 `2026-09-19 14:50:40.495848`, 애플리케이션에서 만든 결제 시도 생성 시각은 `2026-09-19 23:50:40.514302`로 조회됐다. 시간대가 없는 값과 생성 주체가 섞인 시각을 그대로 정렬해 실행 순서로 해석할 수 없다.

좌석의 현재 상태는 이후 다른 예약으로 다시 바뀔 수 있다. 이번 `AVAILABLE` 관찰은 재선점이 없는 격리 재현의 최종 상태다. 스케줄러의 처리 건수나 MySQL 잠금 대기 조회도 특정 주문의 과거 처리 순서를 영속적으로 제공하지 않는다. 별도로 보존된 DB 변경 로그 등의 증거가 있다면 추가 조사 경로로 기록한다.

### HTTP Correlation ID로 연결할 수 있는 범위

| 구간 | 현재 동작 | 연결 범위 |
| --- | --- | --- |
| pre-reserve 내부 | 유효한 헤더를 수용하고, 없으면 UUID 생성. 응답 헤더·요청 속성·MDC에 저장 | 같은 요청 문맥의 로그만 묶을 수 있다. 정상 완료 로그가 자동 생성되지는 않는다. |
| pre-reserve → prepare | 별도 HTTP 요청. DB 관계는 `reservationId`로 연결 | 서버가 앞 요청의 Correlation ID를 저장·복원하지 않는다. |
| prepare → verify | 별도 HTTP 요청. 업무 관계는 `paymentOrderId`로 연결 | 서버가 같은 Correlation ID 사용을 보장하지 않는다. |
| HTTP → expiration scheduler | 스케줄러가 새 UUID `runId`, `correlationId=cleanup:<runId>` 생성 | 개별 예약/주문과 실행 ID의 연결이 없다. |
| 같은 스케줄러 실행 내부 | 동일 `runId`로 시작·완료·실패 출력 | 실행 단위의 처리 건수까지 연결할 수 있다. |

클라이언트가 같은 유효 헤더를 세 요청에 직접 넣으면 HTTP 세 요청의 MDC 값은 같게 만들 수 있다. 현행 [프런트엔드 API](../../frontend/src/services/api.ts)와 [결제 부하 스크립트](../../scripts/test/146-popular-payment-load.js)에는 전체 과정을 위해 해당 값을 재사용하는 처리가 없다. 같은 값을 넣어도 개별 정상 처리 로그와 스케줄러의 업무 ID 연결은 별도로 필요하다.

[CorrelationIdFilter](../../src/main/java/org/example/ticket/util/tracing/CorrelationIdFilter.java) 33–62행은 요청 처리가 끝나면 기존 MDC를 복원한다. [MdcTaskDecorator](../../src/main/java/org/example/ticket/util/tracing/MdcTaskDecorator.java)는 작업을 제출한 시점의 MDC를 작업 스레드로 복사하며, 독립된 다음 HTTP 요청이나 예약 행에 문맥을 저장하지 않는다. [ReservationExpirationScheduler](../../src/main/java/org/example/ticket/reservation/booking/service/ReservationExpirationScheduler.java)와 만료 정리의 새 `runId` 생성은 HTTP 요청과 별개다.

따라서 lifecycle 전체 자동 연결은 불가능하며, 요청 내부 또는 스케줄러 한 번의 실행 내부에서 기록된 로그를 연결하는 수준이다. PG 승인과 만료가 경합한 실제 이유를 개별 건으로 확정하려면 사람이 DB 관계를 따라가고 코드를 해석하는 작업이 남는다.

## 6. 향후 개선 기능이 해결해야 할 요구사항

아래는 이번 결과에서 도출한 **설계 요구사항 제안**이며, 현재 구현 사실과 구분한다.

| 요구사항 | 이번 Baseline에서 줄여야 하는 확인 작업 | 검증 기준 |
| --- | --- | --- |
| 결제 주문 ID 하나에서 관련 상태·이력 조회 | 5개 테이블의 관계를 수동으로 이동 | 하나의 조회 시작점에서 주문, 예약, 모든 좌석, 관련 시도를 확인한다. |
| 상태 변경 사유와 변경 전후 상태 보존 | 같은 최종 상태의 A/B 경로 구분을 위한 코드 해석 | 스케줄러 만료와 결제 반영 중 만료를 서로 다른 실행 사실로 식별한다. |
| 승인 발생·검증·예약 반영·커밋의 구분 | `approved_at`과 갱신 시각의 의미를 추론 | 시각의 의미·시간대와 처리 주체를 명시하고, 확정된 변경 순서를 재구성한다. |
| 예약·결제 업무 식별자와 HTTP/스케줄러 실행 연결 | 로그 검색마다 새로운 키 확보 | `paymentOrderId`에서 세 HTTP 요청과 만료 실행의 관련 기록으로 이동한다. |
| 트랜잭션 결과와 일치하는 이력 | 메서드 진입·임시 상태를 최종 결과로 해석할 위험 | 커밋된 상태와 기록이 일치하며, 롤백·재시도·동일 결과 재응답을 구별한다. |
| 재시도와 재선점 이후에도 과거 상태 보존 | 현재 시도·좌석 상태에서 과거를 역추론 | 대상 승인 시도와 해당 예약이 좌석을 점유·해제한 이력을 식별한다. |
| 실제 진단 비용 비교 | 절차에 따라 변하는 조회·검색 횟수 | 같은 입력·환경·종료 조건에서 SELECT, 로그 검색, 단계, 시간과 순서 확정 성공률을 함께 비교한다. |

개선 기능의 완료 조건에는 “승인 기록 보존 → 예약 만료 확인 → 환불 대기 결정”의 근거를 한 건 단위로 제시하는 것과, 스케줄러 선행 여부를 운영 기록으로 판별하는 것을 포함한다. 조회 수와 진단 시간의 목표값은 4절의 사람 측정 결과를 확보한 뒤 정한다.

| 항목 | 현재 Baseline |
| --- | --- |
| 확인 Entity/Table 수 | **5개**: 상태 보유 4개 + 예약·좌석 연결 1개 |
| DB 조회 수 | **5회**: Q1–Q5 실측. 조인으로 현재 상태 조회만 1회까지 통합 가능 |
| 로그 검색 수 | **기본 4회**: L1–L4. 게이트웨이·HTTP ID·실행 ID 추가 검색은 별도 집계 |
| 필요한 식별자 수 | **기본 절차 6종**: DB 연결 최소 3종 포함. 조건부 추적 ID 포함 시 8종 |
| 진단 단계 수 | **기본 절차 12단계**: 상태 원인 설명까지. 개별 운영 건의 실행 순서 확정은 근거 부족으로 종료될 수 있음 |
| Lifecycle 전체 연결 가능 여부 | **자동 연결 불가**. HTTP 요청 내부·스케줄러 실행 내부의 부분 연결 가능 |
| 원인 확인 시간 | **사람 측정 전**. 재현·기록 절차 제공. 현재 상태·기본 로그만으로 특정 운영 건의 선행 실행 순서를 확정하는 시간은 산출 불가 |
