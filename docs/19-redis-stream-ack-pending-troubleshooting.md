# Redis Stream ACK / Pending Troubleshooting

## 목적

이 문서는 ImTicket의 좌석 생성 Redis Stream 흐름에서 ACK 전 장애가 발생했을 때 메시지가 pending 상태로 남는 상황을 재현하고 분석하기 위한 기준 문서입니다.

핵심은 Redis Stream을 단순 비동기 처리 도구가 아니라 장애 전제 메시징으로 이해하는 것입니다.

## 2.1 현재 Redis Stream 흐름 기준선

### 진입점

- Producer: `SeatCreationProducer`
- Consumer: `SeatCreationConsumer`
- Config: `RedisConfig#seatCreationSubscription`
- Stream key: `seat-creation-stream`
- Consumer group: `seat-creation-group`
- Consumer name: `consumer-{random}`

### 현재 흐름

1. 좌석 템플릿 생성 요청이 들어오면 `SeatCreationProducer#publishEvent`가 `XADD`로 Redis Stream에 메시지를 발행합니다.
2. 메시지 payload에는 `SeatCreationEvent` JSON이 들어갑니다.
3. `RedisConfig#seatCreationSubscription`은 `seat-creation-group` consumer group을 생성하고 `ReadOffset.lastConsumed()`로 메시지를 읽습니다.
4. `SeatCreationConsumer#onMessage`는 payload를 역직렬화한 뒤 `venueHallService.allocateSeatsInternal(...)`을 호출합니다.
5. 현재 consumer 코드에는 명시적인 `XACK` 호출이 없습니다.

### 분석 포인트

- Redis Stream consumer group에서 메시지를 읽은 뒤 ACK를 보내지 않으면 pending entries list에 메시지가 남습니다.
- consumer가 DB 작업을 완료한 뒤 ACK 전에 죽으면 같은 메시지는 pending으로 남고, recovery consumer가 다시 처리할 수 있습니다.
- 같은 좌석 생성 메시지가 재처리될 수 있으므로 실제 운영 보강에서는 jobId, business key, unique constraint 기반 멱등성이 필요합니다.

## 2.2 Pending 재현 도구

### 스크립트

- `scripts/troubleshooting/reproduce_stream_pending.sh`

### 안전한 기본값

기본 stream은 실제 애플리케이션 stream이 아니라 `seat-creation-stream:troubleshooting`입니다.
운영 중인 consumer가 테스트 payload를 처리하지 않도록 기본값을 분리했습니다.

실제 stream key에 대해 확인하려면 명시적으로 `STREAM_KEY=seat-creation-stream`을 지정합니다.

### 실행 명령

```bash
REDIS_HOST=127.0.0.1 \
REDIS_PORT=6379 \
scripts/troubleshooting/reproduce_stream_pending.sh
```

실제 애플리케이션 stream key로 확인하는 예시는 아래와 같습니다.

```bash
REDIS_HOST=127.0.0.1 \
REDIS_PORT=6379 \
STREAM_KEY=seat-creation-stream \
CONSUMER_GROUP=seat-creation-group \
CONSUMER_NAME=pending-debugger \
scripts/troubleshooting/reproduce_stream_pending.sh
```

### 재현 방식

1. `XGROUP CREATE ... MKSTREAM`으로 consumer group을 준비합니다.
2. `XADD`로 troubleshooting payload를 발행합니다.
3. `XREADGROUP`으로 메시지를 읽습니다.
4. 의도적으로 `XACK`를 보내지 않습니다.
5. `XPENDING`으로 pending entries list에 메시지가 남았는지 확인합니다.

### 기대 결과

- `XPENDING` summary에서 pending count가 1 이상으로 증가합니다.
- pending detail에서 message id, consumer name, idle time, delivery count를 확인할 수 있습니다.
- 이 상태가 ACK 전 장애 후 메시지가 pending에 남은 상황을 단순화한 재현입니다.

## 2.3 Pending 관측 절차

작성 예정.

## 2.4 ACK 장애 시나리오 검증

작성 예정.

## 포트폴리오 문장 초안

Redis Stream consumer group에서 ACK 전 장애가 발생하면 메시지가 pending entries list에 남고 이후 재처리될 수 있음을 재현했습니다. 이를 기반으로 좌석 생성 작업은 at-least-once 전달을 전제로 jobId와 business key 기반 멱등 처리가 필요하다고 분석했습니다.
