# ReentrantLock만으로 끝나지 않았던 이유

## 1. 2,000 VU 재측정에서 나타난 대기열

`ReentrantLock`을 적용한 뒤 same-seat 2,000 VU를 독립적으로 세 번 실행했다. 모든 실행에서 예약 성공은 한 건, 사후 DB의 `ReservedSeat`도 한 건이었다. 하지만 응답이 끝나는 방식은 실행마다 달랐다.

| 구분 | R1 | R2 | R3 |
| --- | ---: | ---: | ---: |
| 예약 성공 | 1 | 1 | 1 |
| 409 충돌 | 273 | 253 | 223 |
| lock timeout 429 | 1,726 | 1,614 | 970 |
| transport timeout | 0 | 132 | 806 |
| reservation p95 | 11.75초 | 14.95초 | 14.53초 |
| Tomcat busy / current peak | 150 / 200 | 145 / 200 | 193 / 200 |
| Hikari active / pending peak | 1 / 0 | 1 / 0 | 1 / 0 |
| MySQL row lock wait peak | 0 | 0 | 0 |

이 결과는 비관적 락에서 보였던 Hikari pool 대기와 다르다. Hikari pending과 MySQL row lock wait가 모두 0인데 Tomcat current thread는 세 실행 모두 200에 도달했다. DB 앞의 줄은 사라졌지만, 그 줄이 JVM 안으로 이동한 것이다.

thread dump에서도 같은 경로가 반복됐다.

```text
http-nio-10080-exec-*
  → ReservationLockAspect.withReentrantLocks
  → ReentrantLock.tryLock
  → AbstractQueuedSynchronizer.tryAcquireNanos
  → LockSupport.park
```

R1과 R2에서 각각 다수의 Tomcat worker가 AQS queue에 parked 상태로 잡혔다. R3은 부하 발생기의 request start lag가 p95 1.412초까지 늘어 dump 시점과 요청 도착 시점을 정밀하게 맞추기 어려웠지만, 세 실행의 Tomcat·Hikari·MySQL 지표는 같은 방향을 가리켰다.

## 2. 문제를 lock timeout으로 끝내지 않은 이유

`tryLock(1초)`은 한 요청이 끝없이 기다리지 않도록 만든다. 그러나 2,000명이 거의 동시에 1초씩 기다리면 lock을 얻지 못한 요청도 그 1초 동안 Tomcat worker를 점유한다. worker 200개가 모두 대기 상태가 되면, lock timeout 429를 만들기 위한 요청 처리 자체가 늦어지고 일부 클라이언트는 HTTP 응답을 받기 전에 timeout된다.

여기서 필요한 것은 lock 대기 시간을 더 짧게 조절하는 것만이 아니었다. 이미 처리 중인 좌석에는 새 요청을 lock queue에 넣지 않고, 예약 transaction으로 들어가기 전에 끝내는 별도 경계가 필요했다.

## 3. 좌석별 admission control을 추가했다

`SeatAdmissionService`는 공연 회차와 좌석 ID를 key로 하여 좌석당 permit 하나를 둔다. 새 요청은 idempotency claim을 통과한 뒤 admission을 시도한다. 이미 같은 좌석 요청이 처리 중이면 lock queue에 들어가지 않고 `SEAT_ADMISSION_REJECTED` 429로 종료한다. permit을 획득한 요청만 `ReentrantLock`과 예약 transaction을 거친다.

이 순서가 중요하다. admission은 동일 key 재시도의 결과 replay를 먼저 보장하기 위해 idempotency claim 뒤에 놓여 있다. 그래서 이 단계는 HTTP socket을 받는 순간의 전역 입구가 아니라, 좌석 lock과 DB 예약 경로 앞의 경계다.

```text
새로운 pre-reserve 요청
      │
      ├── 동일 Idempotency-Key의 완료 요청 → 저장된 결과 replay
      │
      └── 새로운 의도
            │
            ├── 좌석 permit 없음 → 429 SEAT_ADMISSION_REJECTED
            │
            └── 좌석 permit 획득
                    → ReentrantLock
                    → Reservation transaction
                    → permit 반환
```

이 제어는 예약 성공 수를 제한하는 기능이 아니다. same-seat 경쟁에서는 원래 한 명만 예약할 수 있다. 목적은 실패가 예정된 요청을 JVM lock queue와 DB transaction까지 보내지 않고, 현재 좌석이 처리 중이라는 사실을 빠르게 반환하는 것이다. JWT 검증과 idempotency claim보다 앞에서 요청을 거르는 제어는 아니므로, Tomcat worker 전체를 보호하는 ingress 정책과는 구분한다.

admission 뒤에 409가 완전히 사라지는 것은 아니다. 선점 transaction이 끝난 뒤 permit을 얻은 요청은 이미 `LOCKED`가 된 좌석을 읽고 409를 받을 수 있다. 429는 처리 중인 좌석에 대한 즉시 거절이고, 409는 처리 완료 뒤 확인한 좌석 충돌이다. 두 응답을 합쳐야 성공하지 못한 요청의 전체 결과가 된다.

## 4. 같은 2,000 VU에서 다시 측정한 결과

admission permit을 좌석당 1로 적용한 뒤 같은 조건에서 2,000 VU를 세 번 다시 실행했다.

| 구분 | admission 전 | admission 후 |
| --- | --- | --- |
| transport timeout | 0 / 132 / 806 | 0 / 0 / 0 |
| reservation p95 | 11.75 / 14.95 / 14.53초 | 4.10 / 10.10 / 13.37초 |
| 예약 성공 | 각 실행 1건 | 각 실행 1건 |
| 사후 DB 예약 | 각 실행 1건 | 각 실행 1건 |
| MySQL row lock wait | 0 | 0 |

가장 중요한 변화는 `0 / 132 / 806 → 0 / 0 / 0`이다. 앞의 세 숫자는 admission 전 독립 실행 R1, R2, R3에서 HTTP 응답을 받지 못한 요청 수이고, 뒤의 세 숫자는 같은 측정에서 transport timeout이 사라진 결과다. 성공 예약 수와 DB 예약 수는 전후 모두 한 건이므로, timeout 감소를 정합성 완화와 맞바꾼 결과가 아니다.

대부분의 실패 요청은 admission 429로 빠르게 종료됐다. 그 결과 Hikari와 MySQL에는 대기열이 쌓이지 않았고, Tomcat이 **lock 대기 요청**으로 가득 차는 현상도 줄었다. admission은 lock을 대체한 것이 아니라, lock 앞에 요청량을 제한하는 경계를 추가한 것이다.

## 5. 관측을 분리해서 본 이유

부하 중에는 business API뿐 아니라 management endpoint도 압박을 받는다. 일부 실행에서 Actuator 수집 표본이 빠졌고, thread dump에는 대량의 409 WARN 로그와 `OutputStreamAppender` 경합도 보였다. 이 값은 좌석 정합성이나 HTTP transport failure와 같은 숫자로 합치지 않았다.

예약 경로의 성공·409·429·transport는 사용자 요청의 결과이고, Actuator·Prometheus 표본 손실은 시스템을 얼마나 정확하게 관찰했는지의 결과다. 두 결과를 분리해 두어야, 429가 많다는 이유로 관측 실패까지 숨기거나 관측 표본 하나가 빠졌다는 이유로 정상 예약 결과까지 실패로 해석하지 않을 수 있다.

## 6. JFR이 다시 보여준 admission의 경계

admission 뒤의 독립 실행에서는 transport timeout이 사라졌지만, 새 container의 cold reservation path에 2,000 VU를 보낸 JFR 진단에서는 요청 2,000건이 모두 15초 안에 응답을 받지 못했다. 사후 DB에는 예약과 `ReservedSeat`가 각각 한 건만 남았다. 정합성은 유지됐지만, admission 429가 응답으로 돌아오기 전 Tomcat과 JVM이 먼저 포화된 실행이었다.

JFR은 이 차이를 설명했다. burst 직후 Tomcat worker가 10개에서 200개까지 늘면서 190개의 worker thread를 새로 만들었고, class loading 통계도 311개 증가했다. `TomcatEmbeddedWebappClassLoader.loadClass`의 monitor 대기와 JWT·Spring MVC 경로가 동시에 잡혔다. MySQL row lock wait는 0이었고, 현재 좌석별 공정 lock과 다른 `ReentrantLock$NonfairSync` event가 관찰됐다.

따라서 admission의 성과는 "어떤 상황에서도 HTTP timeout을 없앤다"가 아니다. warm reservation path에서 lock queue와 예약 transaction에 들어오는 경쟁을 429로 정리한 것이다. 반면 인증·라우팅·idempotency claim 이전의 비용은 별도로 다뤄야 한다.

이 관찰 뒤에는 JWT parser를 application 생성 시 한 번 만들고, 부하 runner가 health 확인 뒤 인증·routing 경로를 상태 변경 없이 warm-up하도록 바꿨다. admission을 idempotency claim 앞으로 옮기는 문제는 같은 key의 동시 재시도가 어떤 응답으로 수렴해야 하는지부터 고정한 뒤에 다룬다.

새 container boot 시간은 예약 요청 p95와 분리해 launch-time JFR로 기록했다. 초기 `164.578초`는 application이 시작된 뒤 붙은 idle recording과 ready 시점을 섞은 값이라 boot 원인 수치로 사용하지 않는다. process 시작부터 남긴 기록에서는 `SecurityConfig`가 사용하지 않는 `MemberService`를 생성자 의존성으로 받으며 security filter 생성과 JPA 초기화를 함께 끌어오는 경로가 확인됐고, 해당 필드만 제거했다. event 비교와 측정 파일은 `docs/130-startup-jfr-security-wiring-result.md`에 남겼다.

이 단계에서 좌석 선점 경로는 "DB row lock 뒤에서 모두 기다리는 구조"에서 "좌석별로 한 요청만 reservation transaction에 진입시키고 나머지는 즉시 결과를 받는 구조"로 바뀌었다. 이후에는 한 좌석이 아니라 여러 인기 좌석을 동시에 열었을 때도 이 경계가 유지되는지 확인했다.
