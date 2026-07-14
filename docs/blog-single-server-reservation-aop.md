# 단일 서버 예매 시스템에서 AOP와 단일 워커로 동시성 제어하기

## 같은 좌석을 동시에 예매하면 어떻게 될까?

예매 오픈 순간에는 여러 요청이 같은 좌석을 동시에 조회한다. 모든 요청이 좌석을 `AVAILABLE`로 읽은 뒤 예약을 저장하면, 한 좌석에 여러 예약이 만들어질 수 있다.

ImTicket의 예약 흐름은 다음과 같다.

```text
예약 요청
 → 좌석 조회
 → 좌석 상태 확인
 → 좌석 LOCKED 변경
 → 예약 및 예약 좌석 저장
```

이 흐름 전체가 하나의 트랜잭션 안에서 실행되기 때문에, 좌석을 확인하고 상태를 변경하는 구간을 어떻게 직렬화할지가 핵심이었다.

## 단일 서버에서 선택할 수 있는 방법

단일 서버, 정확히는 Spring Boot 프로세스가 하나라는 조건에서는 예약 요청을 하나의 워커에서 순서대로 처리할 수 있다.

전체 웹 서버를 단일 스레드로 만드는 방법도 있지만, 그러면 좌석 조회나 공연 상세 조회까지 모두 느려진다. 그래서 예약 처리만 별도 워커로 분리했다.

```text
HTTP 요청 스레드
  → 예약 전용 BlockingQueue
  → 단일 워커 스레드
  → createReservation()
  → HTTP 응답
```

여기서 `BlockingQueue`는 예약 로직을 보호하는 락이 아니다. 큐에 작업을 넣고 꺼내는 자료구조를 안전하게 만드는 역할만 한다. 실제로 한 번에 한 예약만 처리되는 이유는 워커가 한 개이기 때문이다.

## 왜 AOP를 사용했을까?

단일 워커 처리는 예약 동시성 제어를 위한 부가 관심사다. `ReservationService` 안에 큐 제출, `Future` 대기, 예외 변환 코드를 직접 넣으면 예약 비즈니스 로직과 실행 제어 로직이 섞인다.

그래서 `ReservationLockAspect`를 만들고 `createReservation()` 호출 앞뒤에 전략을 적용했다.

Spring AOP 기능을 사용하기 위해 `spring-boot-starter-aop` 의존성도 추가했다. Aspect에는 `@Order(Ordered.HIGHEST_PRECEDENCE)`를 지정해 예약 실행 제어가 먼저 동작하도록 했다. 단일 워커에서 `joinPoint.proceed()`를 호출하면 그 스레드에서 `@Transactional` 인터셉터도 실행되므로 트랜잭션 컨텍스트가 워커 스레드에 만들어진다.

```java
@Around("execution(* org.example.ticket.reservation.service.ReservationService.createReservation(..))")
public Object lockReservationSeats(ProceedingJoinPoint joinPoint) throws Throwable {
    // 설정된 전략에 따라 예약 실행 방식을 선택한다.
}
```

Spring AOP는 Spring 컨테이너가 관리하는 `ReservationService` 프록시를 통해 호출될 때 적용된다. 따라서 컨트롤러에서 주입받은 서비스를 호출하는 현재 API 흐름에서는 적용되지만, 같은 클래스 내부에서 `this.createReservation()`으로 호출하거나 `new ReservationService(...)`로 직접 만든 객체에는 적용되지 않는다.

## 단일 워커를 어떻게 만들었나?

`AsyncConfig`에 예약 전용 `ThreadPoolTaskExecutor`를 추가했다.

```java
@Bean(name = "reservationSingleThreadTaskExecutor")
public ThreadPoolTaskExecutor reservationSingleThreadTaskExecutor(
        TaskDecorator mdcTaskDecorator,
        @Value("${reservation.lock.single-thread.queue-capacity:1000}") int queueCapacity
) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(queueCapacity);
    executor.setThreadNamePrefix("ReservationSingle-");
    executor.setTaskDecorator(mdcTaskDecorator);
    executor.initialize();
    return executor;
}
```

핵심은 `corePoolSize`와 `maxPoolSize`를 모두 1로 설정한 점이다. 요청 스레드는 `submit()`으로 예약 작업을 큐에 넣고, `Future.get()`으로 처리 결과를 기다린다. 따라서 기존 동기식 예약 API 계약은 유지하면서 예약 실행만 직렬화할 수 있다.

워커에서 예외가 발생하면 `Future.get()`이 `ExecutionException`을 던진다. Aspect에서는 내부 원인을 다시 꺼내 원래 예외 타입으로 전달한다. 그래서 기존의 좌석 충돌 예외 처리와 응답 형식을 그대로 사용할 수 있다.

## 설정으로 전략을 바꾼다

예약 락 전략은 환경변수 하나로 선택한다.

```bash
LOCK_STRATEGY=single-thread
LOCK_SINGLE_THREAD_QUEUE_CAPACITY=1000
```

Docker Compose로 실행할 때는 다음처럼 설정한다.

```bash
LOCK_STRATEGY=single-thread \
LOCK_SINGLE_THREAD_QUEUE_CAPACITY=1000 \
docker compose up -d --build app
```

기본값은 기존 구현과 같은 `pessimistic`이다. 따라서 설정하지 않으면 기존 JPA `PESSIMISTIC_WRITE` 흐름이 동작한다. `single-thread`를 선택하면 `SeatService`는 비관적 락 조회 대신 일반 조회를 사용하고, 예약 메서드 자체가 단일 워커에서 실행되도록 한다.

## 대기열이 가득 차면?

기본 대기열은 1,000건이다. 단일 워커가 처리할 수 있는 속도보다 요청이 빠르게 쌓이면 큐가 가득 찰 수 있다. 현재는 `ThreadPoolTaskExecutor`가 새 작업을 거부하고 공통 예외 처리에서 5xx로 응답한다.

이 응답은 중복 예매가 발생했다는 뜻이 아니라, 단일 워커의 처리 한계를 넘어섰다는 뜻이다. 부하 테스트에서는 성공 예약이 두 건 이상 만들어지는지와 함께 큐 포화 시점, 응답 지연, 5xx 발생량을 같이 확인해야 한다.

## 기존 락 방식과 무엇이 다른가?

`single-thread`는 모든 예약을 순서대로 처리한다. 반면 좌석 ID별 `ReentrantLock`은 같은 좌석을 요청한 작업만 기다리게 하고, 서로 다른 좌석의 예약은 동시에 처리할 수 있다. DB 비관적 락은 트랜잭션과 row를 기준으로 보호한다.

따라서 단일 서버에서 `single-thread`는 구현이 단순한 기준선이지만, 예약 처리량은 가장 낮을 가능성이 높다. 이 전략은 좌석 중복을 막는 최소 구조를 확인하고, 이후 좌석별 락이나 DB 락과 처리량을 비교하기 위한 테스트 기준으로 사용할 수 있다.

## 테스트 방법

앱을 `LOCK_STRATEGY=single-thread`로 재기동한 뒤 기존 fixture와 k6 입력을 그대로 사용한다.

```bash
MYSQL_PASSWORD='<local-db-password>' \
scripts/load/seed_pessimistic_lock_fixture.sh

GRADE=1 \
TRAFFIC_PROFILE=minimum \
PT_ID='<performance-time-id>' \
SEAT_ID='<fresh-seat-id>' \
JWT_SECRET='<same-test-secret-as-server>' \
scripts/load/run_pessimistic_lock_k6.sh
```

전략별 성능 수치는 동일한 좌석 fixture, 동일한 VU 수, 동일한 실행 시간을 사용해 비교해야 한다. 현재 저장소에서는 단일 워커 동작을 검증하는 단위 테스트와 전체 Gradle 테스트를 실행했으며, 실제 MySQL 환경의 대규모 k6 부하는 별도로 실행해야 한다.
