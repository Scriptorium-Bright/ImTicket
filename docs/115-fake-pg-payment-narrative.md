# Fake PG가 먼저 만든 결제 서사

## 한 문장 요약

현재 Fake PG는 "결제 성공 버튼을 누르면 예약을 완료한다"는 UI mock이 아니다. 서버가 예약 금액과 주문을 먼저 고정하고, provider의 승인 결과를 다시 대조한 뒤에만 예약·좌석·결제 주문을 한 transaction으로 확정하는 **결제 vertical slice**다.

실 PG를 붙일 때 바꿔야 하는 것은 `fake:...` 토큰을 검증하는 provider adapter뿐이다. 서버 금액, 주문 소유권, 멱등성, 좌석 확정의 경계는 유지한다.

## 1. 결제는 좌석 선점 뒤에 시작한다

hot-seat 요청은 먼저 좌석 admission과 lock을 거쳐 `LOCKED + PENDING_PAYMENT` 예약을 만든다. 이 단계에서 성공한 좌석은 결제 만료 시간 동안만 임시로 잡혀 있다. 결제 API는 이 예약을 새로 만들지 않고, 이미 선점된 예약을 확인해 결제 주문을 만든다.

```text
pre-reserve
  → seat LOCKED + reservation PENDING_PAYMENT
  → payment prepare
  → provider 결제창/승인
  → server-side verify
  → reservation SUCCESS + seat RESERVED + payment APPLIED
```

따라서 외부 PG 호출은 hot-seat admission·좌석 lock 안에 없다. 결제창 대기나 provider API 지연이 좌석 경합 worker를 붙잡지 않게 하기 위한 경계다.

## 2. `prepare`에서 서버가 고정하는 것

`POST /api/payments/prepare`는 `Idempotency-Key`와 `reservationId`를 받는다. 서버는 로그인 지갑 주소로 member를 찾고, 해당 예약이 그 사용자의 아직 결제 가능한 `PENDING_PAYMENT` 예약인지 검증한다.

새 요청이면 서버가 다음을 생성한다.

| 데이터 | 역할 |
|---|---|
| `PaymentOrder` | reservation·member·서버 산정 금액·통화(KRW)·merchant order ID·멱등성 키를 보존 |
| `PaymentAttempt` | provider와 시도 ID, 이후 provider transaction ID·승인 금액·승인 시각을 보존 |
| request hash | 같은 멱등성 키에 다른 reservation을 보내는 요청을 `409 IDEMPOTENCY_CONFLICT`로 막음 |

같은 member·멱등성 키·reservation의 재시도는 기존 `PaymentOrder`를 돌려준다. 그래서 browser 재전송이 주문을 여러 개 만들지 않는다. 금액도 client 요청에서 받지 않고 `Reservation.totalPrice`에서 읽는다.

## 3. Fake PG가 하는 일과 하지 않는 일

Fake PG의 provider 이름은 `FAKE`다. `prepare` 응답에는 `fake:<merchantOrderId>`라는 결정적 approval token을 넣고, `verify`는 이 token이 정확히 같은 merchant order ID에서 만들어졌는지만 확인한다.

```text
merchantOrderId = imt-<reservationCode>-<UUID>
expected token  = fake:<merchantOrderId>
```

일치하면 Fake PG는 다음 snapshot을 돌려준다.

```text
merchantOrderId / providerTransactionId / amount / currency / approvedAt
```

이 방식은 결제수단, 실제 카드 승인, callback, webhook, 취소·환불을 흉내 내지 않는다. 대신 **외부 provider가 준 값은 믿기 전에 내부 주문과 다시 맞춰야 한다**는 결제 핵심 규칙을 테스트 가능한 형태로 고정한다.

## 4. `verify`와 예약 확정은 두 단계다

`POST /api/payments/{paymentOrderId}/verify`는 먼저 주문 소유자를 확인한다. 아직 `APPLIED`가 아니라면 gateway adapter로 provider transaction ID를 검증하고, 그 adapter가 돌려준 snapshot을 `ReservationCompletionService`에 전달한다.

완료 service의 transaction 안에서는 다음 순서를 지킨다.

1. reservation과 `PaymentOrder`를 `for update`로 다시 읽는다.
2. 주문 상태가 `READY` 또는 재시도 가능한 `PAID_UNAPPLIED`인지 확인한다.
3. merchant order ID·승인 금액·통화가 내부 `PaymentOrder`와 정확히 같은지 확인한다.
4. 같은 provider transaction ID가 다른 주문에 이미 쓰이지 않았는지 확인한다.
5. `PaymentAttempt`를 `PAID`로 기록하고, `PaymentOrder`를 `PAID_UNAPPLIED`로 바꾼다.
6. reservation을 `SUCCESS`, 좌석을 `RESERVED`, 주문을 `APPLIED`로 확정한다.

이 순서 덕분에 provider 승인 정보가 금액·통화·주문 ID 중 하나라도 다르면 좌석은 확정되지 않는다. 이미 `APPLIED`인 주문의 재시도는 같은 완료 응답으로 수렴한다.

## 5. 이 기반이 PortOne으로 이어지는 이유

실 PG 연동에서 browser는 결제 결과의 `paymentId`만 서버에 전달한다. server는 provider API를 직접 호출해 `PAID` 상태, provider payment ID, 금액, 통화를 읽고 위와 동일한 snapshot으로 변환해야 한다. browser가 보낸 성공 여부나 금액을 예약 확정 근거로 쓰지 않는다.

즉 Fake PG에서 PortOne으로 바뀌어도 아래 규칙은 바뀌지 않는다.

- 금액은 서버 reservation에서만 산정한다.
- member·reservation·merchant order의 소유 관계를 먼저 확인한다.
- provider 승인 데이터는 서버가 조회하고 내부 주문과 대조한다.
- 외부 HTTP 호출은 reservation completion transaction 밖에서 수행한다.
- provider transaction ID의 전역 중복을 막고, 완료 처리는 멱등적으로 수렴시킨다.

아직 없는 범위는 실 provider sandbox, 브라우저 결제창, 검증된 webhook, 결제 대사, 환불이다. 이 중 다음 단계는 PortOne V2 sandbox의 server-side 조회 검증이며, 상세 범위는 [116-portone-v2-sandbox-adoption-plan.md](116-portone-v2-sandbox-adoption-plan.md)에 고정한다.
