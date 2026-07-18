# PortOne V2 sandbox 연동 계획

> 선행 서사: [115-fake-pg-payment-narrative.md](115-fake-pg-payment-narrative.md)

## 결정

이번 변경은 **PortOne V2 인증 결제의 server-side 조회 검증 adapter**를 추가한다. Fake PG는 기본값으로 남겨 단위 테스트와 로컬 개발을 결정적으로 유지한다. PortOne sandbox 자격 증명을 넣을 때만 provider를 `portone`으로 선택한다.

frontend 결제창·webhook·환불을 한 번에 넣지 않는다. backend의 `prepare → browser paymentId 전달 → server verify → reservation completion` 계약을 먼저 실제 provider 조회로 연결하는 것이 이번 범위다.

## 공식 계약에서 가져올 사실

PortOne V2 인증 결제는 browser SDK의 `paymentId`로 결제를 요청하고, 성공 후 그 `paymentId`를 서버에 전달한다. 서버는 `GET https://api.portone.io/payments/{paymentId}`를 호출해 결제 상태와 금액을 확인해야 한다. `paymentId`는 고객사가 채번하는 고유 주문 식별자이며 같은 ID에서 최종 결제 성공은 한 번만 가능하다. [PortOne 인증 결제 가이드](https://developers.portone.io/opi/ko/integration/start/v2/checkout), [PortOne V2 REST API](https://developers.portone.io/api/rest-v2/payment)가 이 계약의 근거다.

V2 API Secret은 `Authorization: PortOne <secret>` 헤더로만 서버에서 전송한다. 브라우저에 노출하면 안 된다. [PortOne 연동 준비 가이드](https://developers.portone.io/opi/ko/integration/ready/readme)의 보안 요구를 따른다.

## 이번 구현 범위

| 항목 | 구현 | 이번에는 하지 않음 |
|---|---|---|
| provider 선택 | `PAYMENT_GATEWAY_PROVIDER=fake|portone`, 기본 fake | Fake PG 제거 |
| prepare 응답 | provider와 provider payment ID를 일반 필드로 반환 | browser SDK UI 구현 |
| PortOne 검증 | `GET /payments/{paymentId}` + `PAID`·ID·금액·통화 검증 | client 성공 값 신뢰 |
| 보안 | API secret 환경 변수, 로그 미기록, completion transaction 밖 HTTP | secret을 설정 파일·응답에 저장 |
| 테스트 | Mock HTTP 응답으로 paid/non-paid/오류/불일치 검증 | 실제 sandbox 승인 자동화 |
| webhook | 후속 범위로 문서화 | signature 미검증 webhook 수신 |
| 환불·대사 | 후속 범위 | 결제 취소 API 호출 |

## 설계

### 1. provider adapter의 공통 계약

`PaymentGatewayClient`는 provider 이름, browser에 전달할 provider payment ID, provider 검증을 제공한다.

```text
PaymentOrder.merchantOrderId
  ├─ Fake: fake:<merchantOrderId> 토큰
  └─ PortOne: merchantOrderId 자체를 paymentId로 사용
```

PortOne `paymentId`에 내부 merchant order ID를 그대로 쓰면 browser와 server가 같은 식별자를 보며, 별도의 ID mapping table 없이 provider 응답 ID와 내부 주문 ID를 대조할 수 있다. `PaymentAttempt.providerTransactionId`에는 PortOne 조회 응답의 실제 `transactionId`를 저장한다.

### 2. 검증 흐름

```text
POST /api/payments/{paymentOrderId}/verify { providerPaymentId }
  → 주문 소유자 확인
  → PortOne GET /payments/{providerPaymentId}
  → status == PAID
  → response.id == PaymentOrder.merchantOrderId
  → response.amount.total == PaymentOrder.amount
  → response.currency == PaymentOrder.currency
  → response.transactionId를 snapshot에 보관
  → 기존 ReservationCompletionService transaction으로 확정
```

PortOne HTTP 조회는 `ReservationCompletionService` transaction 전에 끝낸다. 그러므로 provider가 느리거나 일시 실패해도 reservation/order row lock을 잡은 상태로 외부 I/O를 기다리지 않는다.

### 3. 환경 변수

```text
PAYMENT_GATEWAY_PROVIDER=fake              # 기본값
PORTONE_API_SECRET=...                     # portone일 때만 필수
PORTONE_API_BASE_URL=https://api.portone.io # 테스트에서만 override 가능
```

Store ID와 channel key는 browser SDK용 공개 식별자다. backend secret과 섞지 않고 frontend 환경에서 관리한다. 실제 sandbox 실행 전에는 콘솔에서 V2 테스트 채널·Store ID·channel key·V2 API Secret을 발급한다.

## 검증 기준

1. 기본 `fake` profile에서 기존 prepare·verify test가 그대로 통과한다.
2. `portone` adapter는 `PAID`와 같은 payment ID·금액·통화를 받았을 때만 `VerifiedPaymentSnapshot`을 만든다.
3. `PAID`가 아니거나 provider 응답 ID/금액/통화가 다르거나 PortOne HTTP가 4xx·5xx이면 예약 확정으로 진행하지 않는다.
4. API secret은 코드·문서 예시·로그·HTTP 응답에 포함하지 않는다.
5. 실제 sandbox 결제는 별도 수동 acceptance로 기록한다. 자격 증명이 없는 CI에서는 mock HTTP test만 수행한다.

## webhook을 다음 단계로 미루는 이유

PortOne은 webhook signature 검증과 server-side 결제 조회를 함께 요구하고, 브라우저 callback과 webhook 도착 순서는 보장되지 않는다. 또 webhook 실패 시 재전송이 일어난다. [PortOne V2 webhook 가이드](https://developers.portone.io/opi/ko/integration/webhook/readme-v2)는 서명 검증과 내부 주문 대조를 요구한다.

따라서 webhook은 raw body 보존, signature 검증, event id 멱등성, provider 재조회, 이미 `APPLIED`인 주문과의 경합을 한 변경으로 다루는 다음 작업이다. 이번 adapter에 검증되지 않은 `/webhook` endpoint를 추가하지 않는다.
