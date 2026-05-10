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

### 스크립트

- `scripts/troubleshooting/observe_stream_pending.sh`

### 실행 명령

```bash
REDIS_HOST=127.0.0.1 \
REDIS_PORT=6379 \
STREAM_KEY=seat-creation-stream:troubleshooting \
CONSUMER_GROUP=seat-creation-troubleshooting-group \
scripts/troubleshooting/observe_stream_pending.sh
```

### Redis 관측 명령

Stream 자체의 길이, first/last entry, group 수를 확인합니다.

```bash
redis-cli XINFO STREAM seat-creation-stream
```

Consumer group의 pending count, last-delivered-id, lag를 확인합니다.

```bash
redis-cli XINFO GROUPS seat-creation-stream
```

Pending summary를 확인합니다.

```bash
redis-cli XPENDING seat-creation-stream seat-creation-group
```

Pending detail을 확인합니다.

```bash
redis-cli XPENDING seat-creation-stream seat-creation-group - + 10
```

오래 pending 상태인 메시지를 다른 consumer가 회수할 때는 `XAUTOCLAIM`을 사용합니다.

```bash
redis-cli XAUTOCLAIM seat-creation-stream seat-creation-group recovery-consumer 60000 0-0 COUNT 10
```

처리 성공 후에는 `XACK`를 보냅니다.

```bash
redis-cli XACK seat-creation-stream seat-creation-group <message-id>
```

### 판단 기준

- `XPENDING` count가 증가하면 ACK되지 않은 메시지가 있는 상태입니다.
- pending detail의 delivery count가 증가하면 같은 메시지가 재전달된 이력이 있는 상태입니다.
- idle time이 길어지는 메시지는 recovery 대상입니다.
- recovery consumer가 메시지를 처리했다면 성공 후 `XACK`를 보내 pending list에서 제거해야 합니다.

## 2.4 ACK 장애 시나리오 검증

### 수행한 검증

- `command -v redis-cli`
- `scripts/troubleshooting/reproduce_stream_pending.sh`
- `scripts/troubleshooting/observe_stream_pending.sh`
- `redis-cli XACK seat-creation-stream:troubleshooting seat-creation-troubleshooting-group 1778399325599-0`
- `redis-cli XPENDING seat-creation-stream:troubleshooting seat-creation-troubleshooting-group`

### 실행 결과

Redis CLI는 로컬에 설치되어 있었습니다.

```text
/opt/homebrew/bin/redis-cli
```

Troubleshooting 전용 stream에 메시지를 발행했습니다.

```text
Published message: 1778399325599-0
```

`XREADGROUP`으로 메시지를 읽고 의도적으로 `XACK`를 보내지 않았습니다.

```text
seat-creation-stream:troubleshooting
1778399325599-0
payload
{"type":"troubleshooting","scenario":"ack-before-crash","source":"reproduce_stream_pending.sh"}
```

`XPENDING` summary에서 pending message 1건을 확인했습니다.

```text
1
1778399325599-0
1778399325599-0
pending-debugger
1
```

`XPENDING` detail에서 message id, consumer, idle time, delivery count를 확인했습니다.

```text
1778399325599-0
pending-debugger
13
1
```

`XINFO GROUPS`에서도 consumer group pending count가 1로 확인됐습니다.

```text
name
seat-creation-troubleshooting-group
consumers
1
pending
1
last-delivered-id
1778399325599-0
entries-read
1
lag
0
```

마지막으로 `XACK`를 보내 pending list에서 제거되는지 확인했습니다.

```text
redis-cli -h 127.0.0.1 -p 6379 XACK seat-creation-stream:troubleshooting seat-creation-troubleshooting-group 1778399325599-0
1

redis-cli -h 127.0.0.1 -p 6379 XPENDING seat-creation-stream:troubleshooting seat-creation-troubleshooting-group
0
```

### 원인 분석

Redis Stream consumer group은 메시지를 읽은 것과 처리가 끝난 것을 별개로 봅니다.
`XREADGROUP`으로 메시지를 읽으면 해당 메시지는 consumer의 pending entries list에 들어갑니다.
처리 성공 후 `XACK`를 보내야 Redis가 이 메시지를 성공 처리된 것으로 판단합니다.

따라서 consumer가 DB 작업을 끝낸 뒤 `XACK` 전에 죽으면, 메시지는 처리됐을 수 있지만 Redis 입장에서는 pending 상태로 남습니다.
이 메시지를 recovery consumer가 다시 처리하면 같은 작업이 중복 수행될 수 있습니다.

### ImTicket에 대한 의미

현재 `SeatCreationConsumer#onMessage`는 `venueHallService.allocateSeatsInternal(...)`을 호출하지만 명시적인 ACK, pending recovery, DLQ 처리가 없습니다.
이 구조에서는 좌석 생성 작업이 중복 실행될 가능성을 전제로 분석해야 합니다.

운영 보강 방향은 아래와 같습니다.

1. 메시지에 `jobId` 또는 요청 business key를 포함합니다.
2. 좌석 생성 job 상태를 DB에 저장합니다.
3. 이미 완료된 job이면 재처리 시 skip 후 ACK합니다.
4. 성공 후에만 `XACK`를 보냅니다.
5. 오래 pending 된 메시지는 `XAUTOCLAIM`으로 회수합니다.
6. 재시도 횟수 초과 메시지는 DLQ stream으로 이동합니다.

### 대응 방향

- Redis Stream은 at-least-once 전달로 보고 consumer를 멱등하게 설계합니다.
- 좌석 생성에는 `hallId + floor + section + row + seatNumber` 같은 business key unique constraint를 검토합니다.
- pending count, idle time, delivery count를 운영 지표 또는 주기 로그로 남깁니다.
- 실패 메시지는 바로 삭제하지 않고 pending 유지, retry, DLQ 순서로 처리합니다.

## 포트폴리오 문장 초안

Redis Stream consumer group에서 ACK 전 장애가 발생하면 메시지가 pending entries list에 남고 이후 재처리될 수 있음을 재현했습니다. 이를 기반으로 좌석 생성 작업은 at-least-once 전달을 전제로 jobId와 business key 기반 멱등 처리가 필요하다고 분석했습니다.
