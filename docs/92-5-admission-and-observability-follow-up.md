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

R1과 R2에서 각각 다수의 Tomcat worker가 AQS queue에 parked 상태로 잡혔다. R3은 부하 발생기의 request start lag가 p95 1.41초까지 늘어 dump 시점과 요청 도착 시점을 정밀하게 맞추기 어려웠지만, 세 실행의 Tomcat·Hikari·MySQL 지표는 같은 방향을 가리켰다.

### thread dump는 무엇을 보여주는가

thread dump는 특정 순간 JVM 안의 모든 Java thread 상태와 호출 stack을 찍은 사진이다. 위 stack은 `http-nio-10080-exec-*`라는 Tomcat worker가 controller를 지나 `ReservationLockAspect`에 도착했고, `tryLock`을 기다리며 AQS의 `park`로 잠들었다는 뜻이다. `parked`는 CPU를 계속 태우는 busy spin이 아니라 signal이나 timeout을 기다리는 상태지만, 그 thread는 request 응답을 아직 반환하지 못했으므로 Tomcat worker slot은 계속 점유한다.

한 장은 우연한 순간일 수 있어 부하 중 여러 dump에서 같은 stack이 반복되는지 본다. `BLOCKED`는 `synchronized` monitor 진입 대기를 뜻하고, `ReentrantLock`·Hikari condition은 WAITING/TIMED_WAITING stack으로 보일 수 있다는 점도 구분해야 한다. thread dump와 JFR event의 차이는 [용어 사전](92-appendix-reservation-performance-glossary.md#7-thread-dump와-jfr)에 정리했다.

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

### admission을 넣어도 p95·p99가 남는 이유

여기서 p95·p99는 `SeatAdmissionService.tryAcquire()`의 실행 시간만 잰 값이 아니라, 클라이언트가 요청을 보낸 뒤 HTTP 응답을 받을 때까지의 end-to-end 시간이다. 현재 admission은 Tomcat 진입 직후가 아니라 `ReservationPreReserveService` 안에서 member ID 조회와 idempotency claim 생성 뒤에 실행된다. 요청이 이미 Tomcat worker·Spring Security·MVC 경로에서 밀렸거나, claim을 만들기 위한 DB 작업을 시작했다면 그 대기 시간은 429 응답에도 포함된다.

따라서 admission이 보장하는 것은 “같은 좌석의 경쟁 요청을 JVM lock queue와 reservation transaction에 오래 넣지 않는다”이지, “모든 HTTP 요청이 짧은 p95로 끝난다”가 아니다. 2,000 VU에서 admission 후에도 p95가 4.10초·10.10초·13.37초였던 이유가 여기에 있다. warm path에서는 transport timeout을 없앴지만, cold path/JFR 실행에서는 admission에 도달하기 전 Tomcat이 200까지 늘고 인증·class loading·routing 비용이 겹쳐 모든 요청이 client deadline 안에 응답하지 못한 case도 있었다.

## 5. 관측을 분리해서 본 이유

부하 중에는 business API뿐 아니라 management endpoint도 압박을 받는다. 일부 실행에서 Actuator 수집 표본이 빠졌고, thread dump에는 대량의 409 WARN 로그와 `OutputStreamAppender` 경합도 보였다. 이 값은 좌석 정합성이나 HTTP transport failure와 같은 숫자로 합치지 않았다.

예약 경로의 성공·409·429·transport는 사용자 요청의 결과이고, Actuator·Prometheus 표본 손실은 시스템을 얼마나 정확하게 관찰했는지의 결과다. 두 결과를 분리해 두어야, 429가 많다는 이유로 관측 실패까지 숨기거나 관측 표본 하나가 빠졌다는 이유로 정상 예약 결과까지 실패로 해석하지 않을 수 있다.

## 6. JFR이 다시 보여준 admission의 경계

cold 2,000 VU 실행에서는 정합성이 유지됐지만 Tomcat이 admission 429를 반환하기 전에 포화됐다. JFR에는 worker 증가, class loading, JWT·MVC 경로가 함께 나타났고 MySQL row-lock wait는 0이었다.

따라서 admission은 lock·예약 transaction 진입을 줄이지만 인증·라우팅·claim 이전 비용까지 제거하지는 않는다. startup JFR과 warm-up 보정은 [startup JFR 분석](experiments/runtime/130-startup-jfr-security-wiring-result.md)에 분리했다.

## 7. 후속 2,000 VU에서 대기 method를 다시 특정했다

위 admission 초기 실험은 “좌석 lock queue 진입을 줄였다”는 사실을 보여줬지만, admission 후 p95가 왜 초 단위로 남는지는 method까지 특정하지 못했다. 그래서 4개 hot seat, 좌석당 permit 1, Tomcat 200, Hikari 30, 2,000 VU 조건에서 Prometheus·두 번의 thread dump·JFR를 같은 load window로 다시 수집했다.

| 항목 | stack capture run | repeat run |
| --- | ---: | ---: |
| p95 / p99 | 9.53초 / 9.72초 | 7.54초 / 7.69초 |
| Tomcat busy/current | 200/200 | 200/200 |
| Hikari active/pending | 30/167 | 30/167 |
| Hikari acquire max | 4.32초 | 3.32초 |
| MySQL row lock wait | 0 | 0 |
| `ReservationLockAspect` matching ThreadPark | 0 | 0 |

두 thread dump에서 각각 161개와 168개의 Hikari wait stack이 같은 호출 체인에 있었다.

```text
HikariPool.getConnection()
  → MemberRepository.findIdByWalletAddressIgnoreCase()
  → ReservationPreReserveService.preReserve()
```

동시에 connection을 얻은 일부 stack과 JFR에서는 `ReservationIdempotencyTransactionService.createClaim()`의 `saveAndFlush()`가 Hibernate를 거쳐 MySQL INSERT를 실행하고 있었다. 정확한 해석은 member lookup 하나나 claim 하나가 단독 원인이라는 뜻이 아니다. **대기 위치는 member lookup의 Hikari checkout이고, pool을 함께 요구하고 점유하는 구조적 경로는 admission 이전의 member lookup + claim INSERT/flush다.**

```text
HTTP / JWT / MVC
  → member ID lookup                    // DB, 직접 Hikari wait stack
  → idempotency claim saveAndFlush      // DB, active connection의 work
  → SeatAdmission.tryAcquire            // 여기 도착한 뒤에는 빠른 429
  → ReservationLock / transaction       // 이번 run의 주 대기 아님
```

이 결과는 앞의 cold JFR에서 본 class loading·JWT·routing 비용을 없던 일로 만들지 않는다. cold path는 burst 초반을 키운 보조 요인이었고, 최신 두 실행에서 반복된 자원 signature는 `Tomcat 200 → Hikari 30/167 → row lock 0`이었다. 따라서 현재 블로그의 최종 원인 귀속은 admission 이전 DB-first path이며, 상세 원본은 [2026-07-29 병목 귀속 문서](test/2026-07-29-reservation-pre-admission-bottleneck-attribution.md)에 둔다.
