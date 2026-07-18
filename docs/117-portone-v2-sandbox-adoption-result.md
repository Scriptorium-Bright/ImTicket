# PortOne V2 server-side 검증 adapter 구현 결과

> 계획: [116-portone-v2-sandbox-adoption-plan.md](116-portone-v2-sandbox-adoption-plan.md)
>
> Fake PG 서사: [115-fake-pg-payment-narrative.md](115-fake-pg-payment-narrative.md)
> 구현일: 2026-07-18

## 결과

PortOne V2 인증 결제의 **서버 조회 검증 adapter**를 추가했다. 기본 실행은 계속 Fake PG이며, `PAYMENT_GATEWAY_PROVIDER=portone`일 때만 PortOne V2 API를 사용한다. API Secret이 없으면 portone provider로 기동하지 않아, 실 연동이 의도치 않게 Fake로 fallback되는 일이 없다.

현재 환경에는 `PORTONE_API_SECRET`이 설정되어 있지 않아 실제 sandbox 카드 승인은 수행하지 않았다. 대신 PortOne HTTP 응답을 mock한 adapter 테스트와 전체 Gradle test를 통과시켰다. 실제 결제는 아래 수동 acceptance 절차로 별도 기록해야 한다.

## 구현한 변경

| 영역 | 변경 | 의도 |
|---|---|---|
| gateway 계약 | `provider()`, `providerPaymentId()`, `verify()`로 확장 | Fake와 PortOne이 같은 prepare/verify 흐름을 공유 |
| Fake 기본값 | Spring bean을 설정에서 `fake` provider로 생성 | 로컬·단위 테스트의 결정성 유지 |
| PortOne V2 | `GET /payments/{paymentId}`를 `Authorization: PortOne <secret>`으로 호출 | browser callback 대신 서버가 실제 provider 상태를 확인 |
| 검증 | `PAID`, payment ID, 금액, 통화, transaction ID, 승인 시각 확인 | 다른 주문·변조 금액·미완료 결제를 예약 확정에 사용하지 않음 |
| transaction 경계 | PortOne HTTP 호출 뒤 기존 completion transaction 진입 | 외부 I/O 중 reservation/order lock을 잡지 않음 |
| legacy 정리 | 사용되지 않는 Iamport V1 client/Retrofit/Gson/OkHttp 의존성과 주석 config 제거 | V2 REST 계약과 실제 코드의 혼선을 제거 |

PortOne 조회가 성공해도 `ReservationCompletionService`가 merchant order ID·금액·통화를 한 번 더 대조하고, provider transaction ID의 타 주문 재사용을 막는다. adapter 검증과 completion 검증을 둘 다 둔 이유는 provider API mapping 오류가 좌석 확정으로 이어지지 않게 하기 위해서다.

## API 계약 변경

`POST /api/payments/prepare` 응답의 Fake 전용 `fakeProviderTransactionId`를 provider 공통 `providerPaymentId`로 바꿨다.

| provider | `providerPaymentId` | verify 뒤 저장되는 `providerTransactionId` |
|---|---|---|
| `FAKE` | `fake:<merchantOrderId>` | 같은 Fake token |
| `PORTONE` | `merchantOrderId`와 같은 PortOne `paymentId` | PortOne 응답의 실제 `transactionId` |

`POST /api/payments/{paymentOrderId}/verify`의 권장 request field는 `providerPaymentId`다. 기존 `providerTransactionId` 입력도 JSON alias로 받아 Fake client의 요청 호환성은 유지한다. 다만 frontend는 prepare 응답의 새 `providerPaymentId`로 전환해야 한다.

## 설정

```text
# 기본값: Fake PG
PAYMENT_GATEWAY_PROVIDER=fake

# PortOne V2 sandbox 또는 실연동
PAYMENT_GATEWAY_PROVIDER=portone
PORTONE_API_SECRET=<V2 API Secret>
PORTONE_API_BASE_URL=https://api.portone.io
```

API Secret은 backend 환경 변수에만 둔다. Store ID와 channel key는 browser SDK가 쓰는 공개 식별자이며, backend API Secret과 같은 파일·응답·로그에 넣지 않는다.

## 검증 결과

`./gradlew test`가 성공했다.

- 기존 Fake gateway token 검증과 prepare 멱등성·서버 산정 금액 test를 유지했다.
- PortOne adapter는 `PAID`·일치한 ID·금액·통화·transaction ID일 때만 snapshot을 만든다.
- `READY` 같은 미완료 상태, 금액 불일치, PortOne 4xx는 각각 예약 completion 전에 거절되는 test를 추가했다.
- 기존 reservation completion test도 함께 통과해 좌석 `RESERVED`, reservation `SUCCESS`, payment `APPLIED` 전이가 유지됨을 확인했다.

## sandbox 수동 acceptance 절차

1. PortOne console에서 V2 테스트 Store ID, test channel key, V2 API Secret을 발급한다.
2. backend를 `PAYMENT_GATEWAY_PROVIDER=portone`과 Secret으로 기동한다.
3. `prepare` 응답의 `providerPaymentId`, 서버가 준 `amount`, `currency`를 browser SDK `requestPayment`에 넣는다. amount를 browser에서 새로 계산하지 않는다.
4. browser 결제 성공 후 받은 `paymentId`를 `verify` API의 `providerPaymentId`로 보낸다.
5. API 응답과 DB에서 `PaymentAttempt.provider=PORTONE`, PortOne `transactionId`, 승인 금액·통화, `PaymentOrder=APPLIED`, reservation=`SUCCESS`, 좌석=`RESERVED`를 대조한다.
6. 다른 payment ID, 다른 금액, 결제 실패/취소 payment ID를 보내도 좌석이 확정되지 않는지 확인한다.

## 아직 하지 않은 것

webhook endpoint, raw body signature 검증, webhook event 멱등성, 결제 대사, 취소·환불은 이번 변경에 넣지 않았다. PortOne webhook은 browser callback과 순서가 보장되지 않고 재전송될 수 있으므로, 다음 작업에서 signature 검증과 provider 재조회·주문 상태 경합을 함께 설계해야 한다. 검증되지 않은 webhook endpoint를 먼저 열지 않는 것이 이번 범위의 안전 경계다.
