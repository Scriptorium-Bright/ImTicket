# JFR로 확인한 2,000 VU timeout: 좌석 락보다 먼저 막힌 곳

same hot-seat에 2,000개의 `pre-reserve` 요청을 한 번에 보냈을 때, 모든 클라이언트 요청이 15초 안에 응답을 받지 못했다. 처음에는 같은 좌석을 두고 경합하는 `ReentrantLock` 또는 MySQL의 row lock 대기가 원인처럼 보였다. JFR을 붙인 이유는 이 timeout이 실제로 어느 층에서 만들어졌는지 JVM 안쪽까지 확인하기 위해서였다.

실행 조건은 단일 JVM, `reentrant` 전략, 좌석별 admission permit 1개, HikariCP 30개, 15초 HTTP timeout이었다. 부하는 host k6에서 발생시켰고, JFR은 application JVM에 `profile` 설정으로 약 52초간 기록했다.

## 먼저 나온 결과

| 항목 | 관측값 |
| --- | ---: |
| 예약 시도 | 2,000건 |
| 클라이언트 transport timeout | 2,000건 |
| reservation duration p95 | 14.684초 |
| HTTP connection p95 | 3.952초 |
| HTTP waiting p95 | 14.610초 |
| 사후 DB 상태 | 예약 1건, `ReservedSeat` 1건 |

DB에는 좌석을 선점한 예약이 정확히 한 건만 남았다. 정합성은 유지됐지만, 클라이언트가 결과를 받기 전에 deadline이 지나버린 실행이다. 이 기록은 “2,000 VU에서 좌석 락이 느리다”는 성능 비교 수치가 아니라, 왜 한 번의 burst가 전부 timeout으로 보였는지를 설명하는 진단 기록으로 사용한다.

## burst가 시작된 직후의 JVM

Actuator health와 HikariCP max 확인을 통과한 뒤 요청을 시작했지만, `pre-reserve` 경로 자체는 아직 대량 요청을 처리한 적 없는 상태였다. burst가 시작된 뒤 Tomcat worker는 10개에서 200개까지 늘어났고, 그 사이 190개의 `http-nio-10080-exec-*` thread가 약 11.4초 동안 새로 생성됐다.

동시에 JFR의 class loading 통계는 24,272개에서 24,583개로 311개 증가했다. 특히 `TomcatEmbeddedWebappClassLoader.loadClass`에서 monitor 진입 대기가 1,023건 기록됐고, 여러 worker의 대기 시간을 합산하면 1,071.748초였다. 이는 한 요청이 1,071초를 기다렸다는 뜻이 아니라, 많은 worker가 동시에 class loading 경합에 묶였다는 뜻이다. 개별 대기는 최대 3.223초였고, `Class.getDeclaredConstructors0`에서도 최대 1.070초의 reflection 대기가 나타났다.

JFR execution sample에는 Spring Security filter chain, `DefaultJwtParser.parse`, Spring MVC handler mapping, request/response converter가 함께 잡혔다. 현재 `JwtUtil.parseClaims()`는 요청마다 `Jwts.parser().verifyWith(...).build()`를 새로 수행한다. 평상시에는 작아 보이는 인증·라우팅·리플렉션 비용이, 처음 들어온 2,000개의 인증 요청에서는 thread 생성과 class loading 비용과 한꺼번에 겹쳤다.

Docker 관측도 같은 흐름을 보였다. application CPU는 최대 931.94%까지 올라갔고, container memory는 약 690.7 MiB에서 898.1 MiB까지 증가했다. G1 old collection도 burst 중 1.474초 발생했다. GC가 14초 전체를 만들었다고 볼 수는 없지만, 이미 포화된 CPU 구간의 회복을 더 늦춘 요소였다.

## admission이 즉시 429를 만들지 못한 이유

현재 요청은 socket을 받은 직후 admission을 통과하는 구조가 아니다. 실제 호출 순서는 다음과 같다.

```text
HTTP 수신
  → Spring Security / JWT 검증
  → 회원 ID 조회
  → idempotency claim 생성 및 flush
  → SeatAdmissionService.tryAcquire()
  → 공정 ReentrantLock
  → 좌석 상태 변경과 예약 저장
```

`SeatAdmissionService`는 좌석 락과 이후의 DB 변경 경로에는 진입 인원을 하나로 제한한다. 그러나 그 앞의 인증, MVC dispatch, idempotency claim 생성까지는 모든 요청이 지나간다. 즉 현재 admission은 **좌석 경합을 위한 보호막**이지, 2,000개의 HTTP 요청이 Tomcat worker를 점유하기 전에 거르는 ingress admission은 아니다.

이 차이가 이번 JFR 결과를 설명한다. 같은 좌석에서 지는 요청도 admission에 도달해야 429를 받을 수 있는데, 이번 burst에서는 그 이전 단계에서 worker가 200개까지 차고 CPU와 class loading 경합이 먼저 커졌다. 그래서 429 대신 client timeout이 관측됐다.

## DB lock이나 공정 ReentrantLock이 주원인이 아닌 근거

MySQL `performance_schema.data_lock_waits`는 실행 내내 0이었다. Prometheus가 응답한 구간에서 HikariCP는 active connection 최대 2개, pending 0개였고, JFR이 business worker에서 잡은 MySQL socket read도 3건·최대 48ms였다. 반대로 Tomcat은 busy/current 모두 200까지 도달했다. 이번 timeout의 중심이 DB connection pool이나 InnoDB row lock이 아니라 HTTP worker와 JVM 실행 경로였다는 근거다.

JFR에는 business worker에서 `ReentrantLock$NonfairSync` park event가 771건 보였지만, 이것은 현재 좌석별로 생성하는 `new ReentrantLock(true)`와 다른 lock이다. 공정 `ReentrantLock`은 `FairSync`를 사용한다. profile 기록의 stack depth가 짧아 해당 non-fair lock의 소유 라이브러리까지는 특정하지 못했지만, 적어도 이 event를 좌석 선점용 공정 lock 대기라고 해석할 수는 없다.

이번 실행에서 좌석 lock이 원인이라면 공정 lock의 wait 또는 JDBC/Hikari/MySQL 대기가 앞에 나타나야 했다. 실제로 먼저 나타난 것은 Tomcat thread 증설, class loader monitor contention, JWT·Spring MVC 경로의 CPU 사용이었다.

## 이 기록이 바꾼 판단

이 결과만으로 `ReentrantLock`을 바꾸거나 application을 여러 대로 늘릴 이유는 없다. 기존 single-JVM 선택의 정합성 근거와도 충돌하지 않는다. 오히려 이번 기록은 새로운 container가 health 상태가 된 것과 핵심 예약 경로가 burst를 받을 준비가 된 것은 다른 문제임을 보여준다.

Tomcat `max-threads`는 이미 200까지 도달해 있었다. 이 값을 더 키우면 더 많은 worker를 CPU 경쟁에 올릴 뿐이며, 이번 문제를 해결하는 수단이 아니다. worker를 미리 확보하는 설정은 cold burst의 thread 생성 구간을 줄일 수 있지만, 인증·idempotency claim·admission의 경계가 어디에 있어야 하는지는 별도의 설계 문제로 남는다.

따라서 이 실행의 결론은 단순하다. **14.684초는 좌석 락 처리시간이 아니라, cold reservation path에 2,000개의 인증 요청이 동시에 진입했을 때 Tomcat과 JVM이 먼저 포화된 시간이다.** 이 JFR 진단은 여기서 종료하며, 포트폴리오에는 DB lock 병목으로 잘못 해석하지 않고 “lock 비교 전 JVM 실행 경로를 분리해 확인했다”는 근거로만 사용한다.

여기서 말하는 reservation path cold 상태는 application boot와 구분한다. 초기 `164.578초` 기록은 application이 시작된 뒤 붙은 idle JFR과 container ready 시점을 섞은 값이라 boot 원인 수치로 사용하지 않는다. runner의 health timeout을 늘린 것은 부팅 중인 container를 성능 실패로 오인하지 않기 위한 조치다. application boot은 JVM 시작부터 recording하는 별도 실행으로 측정했고, 그 결과와 `SecurityConfig` 의존성 제거 근거는 `docs/130-startup-jfr-security-wiring-result.md`에 남겼다.

## 여기서 결정한 다음 순서

이번 기록으로 지금 당장 바꿀 것과 바꾸지 않을 것이 분명해졌다.

먼저 좌석 정합성 경계는 그대로 유지한다. 단일 JVM 안에서는 좌석별 공정 `ReentrantLock`과 MySQL의 예약 상태 변경이 서로 역할을 나눠 갖는다. 이 상태에서 application을 여러 대로 늘리거나 분산 락·대기열을 먼저 넣지 않는다. 이번 timeout은 그 전환을 요구한 문제가 아니었기 때문이다.

다음으로, 이후에 락 전략이나 응답 시간을 비교해야 할 일이 생기면 `pre-reserve`의 인증 경로까지 한 번 통과시킨 뒤 burst를 시작한다. Actuator health와 connection pool 준비만으로는 예약 API의 class loading, JIT, JWT parser 경로가 준비됐다고 볼 수 없다는 사실을 이번 JFR이 확인했다. 이는 2,000 VU를 다시 만들기 위한 이유가 아니라, 앞으로 성능 비교의 출발 조건을 고정하기 위한 규칙이다.

application 경로에서는 세 가지를 분리해서 다룬다. `JwtUtil`이 요청마다 parser를 새로 만드는 부분은 재사용 가능한 객체를 application 시작 시점에 만들 수 있는지 검토한다. Tomcat은 `max-threads`를 더 키우지 않고, cold burst에서 worker 생성이 어떤 영향을 주는지에 한해 최소 idle worker 설정을 검토한다. 마지막으로 seat admission을 idempotency claim보다 앞에 둘지 결정한다. 이 마지막 항목은 단순한 순서 변경이 아니다. 같은 idempotency key의 재시도는 기존 결과를 반환해야 하므로, 기존 claim 조회와 신규 요청의 빠른 거절을 함께 보장하는 흐름으로 설계해야 한다.

여러 application instance로의 전환은 다음 조건이 생길 때만 검토한다. 실제 배포가 둘 이상의 JVM을 요구하거나, warm reservation path와 고정된 부하 발생 조건에서도 목표 응답 계약을 지키지 못하고, 같은 좌석의 보호 경계를 JVM 밖으로 공유해야 할 때다. 그때는 JVM-local `ReentrantLock`과 local admission을 공통 저장소 기반의 제어로 교체하고, MySQL은 최종 정합성 경계로 남긴다.

정리하면 현재 단계의 작업은 **단일 서버 설계를 유지한 채 reservation path의 첫 요청 비용과 admission 위치를 정리하는 것**이다. application boot 시간은 별도 기록으로 다루고, 분산 구조는 이 작업의 대체안이 아니라 실제로 여러 JVM이 필요해진 뒤의 다음 단계다.

## 기록 위치

JFR, k6 summary, JVM·Docker·MySQL 관측 파일은 다음 디렉터리에 남겼다.

```text
build/k6-results/jfr-2000vu-live/reentrant-admission-2000vu-20260721T061628Z
```

별도로 남아 있는 `build/k6-results/jfr-startup` recording은 application startup이 끝난 뒤 시작된 idle recording이다. 이 문서의 판단에는 사용하지 않았다.
