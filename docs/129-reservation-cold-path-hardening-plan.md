# JFR 이후, 예약 경로를 다음에 어떻게 다듬을 것인가

2,000 VU JFR 실행은 좌석 락의 처리시간을 재는 실험이 아니었다. 같은 좌석을 향한 요청이 모두 timeout으로 끝났을 때, 그 시간이 MySQL row lock이나 공정 `ReentrantLock`에서 쓰인 것인지 먼저 확인하는 진단이었다. 기록은 다른 답을 보여줬다. `pre-reserve`의 첫 대량 요청에서 Tomcat worker가 10개에서 200개까지 늘고, class loading·reflection·JWT·Spring MVC 경로가 겹쳤다. 좌석 permit은 그 뒤에 있으므로, permit에 도달하기 전의 HTTP worker 포화를 막을 수 없었다.

이 문서는 그 관찰 뒤에 무엇을 바꾸고, 무엇을 아직 바꾸지 않을지를 정리한다. 목표는 timeout 숫자를 좋게 보이게 만드는 것이 아니라, 이후의 예약 경로 비교가 같은 출발점에서 시작하도록 만들고 실제로 줄일 수 있는 비용만 먼저 없애는 것이다.

## 1. 이번 변경에서 해결하는 범위

이번 변경은 네 가지를 연결한다. 매 요청마다 만들어지던 JWT parser를 application 생명주기로 올리고, application health만 확인하던 runner에 예약 route warm-up을 추가한다. 동시에 process 시작부터 기록하는 JFR 경로를 만들고, 그 기록에서 확인된 `SecurityConfig`의 사용하지 않는 `MemberService` 주입을 제거한다. 네 변경 모두 예약 상태·멱등성·좌석 락의 의미를 바꾸지 않는다.

반대로 admission을 idempotency claim보다 앞으로 옮기거나 Tomcat의 최대 worker 수를 늘리는 일은 이번에 하지 않는다. 둘 다 timeout을 줄일 수 있다는 추측만으로 넣기에는 요청 재시도 계약과 CPU 경쟁의 의미를 바꾸는 변경이기 때문이다.

## 2. JWT parser를 application 생명주기로 올린다

현재 `JwtUtil.parseClaims()`는 요청마다 다음 객체 그래프를 만든다.

```text
Jwts.parser()
  → verifyWith(secretKey)
  → build()
  → parseSignedClaims(token)
```

secret key와 검증 알고리즘은 application이 살아 있는 동안 바뀌지 않는다. 따라서 parser도 `JwtUtil` 생성 시 한 번 만들고 모든 요청이 같은 parser를 사용하도록 바꾼다. 요청마다 남는 일은 token 자체의 서명 검증과 claims 해석뿐이다.

이 변경은 JWT의 claim, 만료, 예외 처리, filter의 인증 실패 동작을 바꾸지 않는다. 유효한 token의 wallet address와 role이 이전과 동일하게 읽히는지, 같은 `JwtUtil` 인스턴스에서 여러 요청이 반복 검증돼도 결과가 유지되는지 단위 테스트로 확인한다.

## 3. 부하 실행기의 준비 상태를 reservation path까지 넓힌다

기존 runner는 `/actuator/health`와 HikariCP의 max connection만 확인했다. 이 확인은 JVM이 기동됐고 DB pool이 만들어졌다는 뜻이지만, 인증을 거친 예약 controller mapping이 이미 실행됐다는 뜻은 아니다. 실제 실행에서는 이 차이가 burst 순간의 class loading과 thread 생성으로 드러났다.

그래서 runner는 health 확인 뒤 authenticated `GET /api/reservation/pre-reserve`를 한 번 보낸다. 이 URI는 POST만 허용하므로 정상 결과는 `405 Method Not Allowed`다. 요청은 JWT filter와 Spring Security, DispatcherServlet, `ReservationController`의 handler mapping을 지나지만 reservation request body를 읽거나 idempotency claim·좌석 상태를 만들지는 않는다.

```text
application health
  → HikariCP max 확인
  → 인증된 GET /api/reservation/pre-reserve
  → 405 확인
  → idle 관측 구간
  → 새 fixture를 향한 scheduled burst
```

warm-up이 405가 아니면 runner는 burst를 보내지 않는다. 이 규칙은 유효하지 않은 JWT나 routing 실패를 부하 결과에 섞지 않기 위한 것이다. warm-up은 transaction·JSON 역직렬화·idempotency 저장까지 미리 수행하는 작업은 아니다. 상태를 바꾸지 않는 범위에서, 이번 JFR에서 관찰된 인증과 MVC 진입 비용을 분리하는 준비 단계다.

같은 runner의 health 대기 시간도 90초 고정값에서 환경변수 `APP_READY_TIMEOUT_SECONDS`로 분리하고 기본값을 240초로 둔다. 초기 기록에서 application boot와 reservation path cold start가 섞여 있었고, 실제 boot 시간이 환경에 따라 크게 달라질 수 있었기 때문이다. 이 값은 성능 수치가 아니라, container가 아직 준비되지 않았다는 이유로 진단 자체가 시작되지 않는 일을 막는 실행 조건이다.

health 대기 시간과 route warm-up은 여기까지다. 둘은 부하 테스트가 준비되지 않은 application을 대상으로 시작하지 않게 만들고, 첫 요청에만 붙는 비용을 비교 조건 밖으로 옮긴다. application boot 시간 자체는 launch-time JFR로 별도 측정하고, 그 결과에 해당하는 초기화 단계만 고친다.

## 4. application boot는 별도 시간축으로 측정한다

처음 기록한 `164.578초`는 application이 시작된 뒤 붙은 idle JFR과 container ready 시점을 혼합한 값이었다. 이 값은 boot 원인을 설명하는 수치로 사용할 수 없으므로 폐기했다. `APP_READY_TIMEOUT_SECONDS=240`은 부팅 중인 container를 성능 실패로 판단하지 않도록 실행 조건을 고친 것이며, boot 시간을 줄인 결과가 아니다.

application boot와 첫 reservation 요청은 다른 시간축이다. 전자는 새 JVM이 context와 외부 연결을 준비하는 시간이고, 후자는 준비된 JVM에 첫 요청이 들어올 때의 class loading·JWT·MVC 비용이다. 둘을 하나의 cold-start로 묶지 않고 각각 기록해야 lock 대기와 초기화 비용을 섞지 않는다.

과거 thread dump에서 Spring AOP advisor discovery가 보였지만, 그 stack만으로 전체 boot 원인을 정할 수는 없었다. 그래서 이번에는 JVM 시작 시점부터 `-XX:StartFlightRecording`을 켜고, `FlightRecorderApplicationStartup`으로 Spring startup step을 같은 시간축에 남겼다.

container의 `java` command에 1회성 `-XX:StartFlightRecording`을 붙여 process 생성부터 `Started TicketApplication`까지 class loading, thread start, GC, socket connect, monitor contention을 남긴다. `FlightRecorderApplicationStartup`은 bean factory·configuration class parsing·repository 초기화의 이름과 부모 관계를 기록한다. 이번 캡처 스크립트의 기본 recording 시간은 180초로 두어, 느린 환경에서도 `Started TicketApplication` 이후까지 파일에 포함되게 했다.

```java
SpringApplication application = new SpringApplication(TicketApplication.class);
application.setApplicationStartup(new FlightRecorderApplicationStartup());
application.run(args);
```

이 instrumentation은 상시 기능이 아니다. 정상 실행에서는 환경변수 기본값이 `false`라 JFR을 켜지 않는다. 같은 image와 MySQL·Redis 조건에서 기준 boot을 기록하고, 선택한 개선을 적용한 뒤 한 번 다시 확인해 원인에 해당하는 변경만 남긴다.

기록에서 확인할 후보와 조치는 다음처럼 연결한다.

| JFR과 startup step에서 보이는 흐름 | 적용할 변경 | 부팅 경로에서 없어지는 일 |
| --- | --- | --- |
| AOP advisor 탐색과 class loading이 길게 이어짐 | 실제 reservation aspect가 필요한 package·type만 후보가 되도록 pointcut과 component scan 범위를 좁히고, 사용하지 않는 starter·auto-configuration을 제거한다. | 모든 bean을 proxy 후보로 확인하는 범위와 application class loading을 줄인다. |
| Hibernate metadata 또는 schema update가 대부분을 차지함 | 배포 중 schema 변경은 Flyway migration으로 분리하고, application은 `ddl-auto=validate`로 시작한다. | 매 application boot마다 entity schema를 비교·변경하는 일을 request-serving process에서 제거한다. |
| MySQL·Redis·외부 API 연결 또는 재시도가 길게 이어짐 | 예약 API가 시작에 반드시 필요로 하는 의존성과 그렇지 않은 의존성을 나누고, 후자는 ready 이전의 동기 초기화에서 분리한다. | 외부 시스템의 일시적 지연이 API process 전체의 기동을 붙잡지 않게 한다. |
| DispatcherServlet과 MVC mapping 초기화가 첫 요청에 남아 있음 | `spring.mvc.servlet.load-on-startup=1`로 servlet과 handler mapping을 application startup 시점에 올리고, readiness는 그 초기화 뒤에 열리게 한다. | 첫 사용자가 MVC 초기화 비용을 지불하지 않게 한다. |

이 표의 항목을 한꺼번에 넣지는 않는다. 이번 recording에서 가장 선명했던 것은 `SecurityConfig` 생성 시 사용하지 않는 `MemberService`까지 생성자 의존성으로 끌어오던 경로였다. 그 필드만 제거하고 다시 기록했으며, AOP·schema·Tomcat 설정은 손대지 않았다. 실제 이벤트 결과는 별도 기록 문서에 남겼다.

부팅이 끝났다는 판단도 TCP port가 열렸다는 사실만으로 두지 않는다. 최종 흐름은 아래처럼 둔다.

```text
JVM process 시작
  → JVM boot JFR + Spring startup timeline
  → application context / DB 연결 / MVC eager initialization
  → readiness 통과
  → 외부 트래픽 수신
```

부하 runner의 authenticated GET warm-up은 이 흐름의 마지막 단계 뒤에서만 사용한다. 그것은 benchmark마다 같은 reservation path 상태에서 burst를 시작하기 위한 장치이고, production boot를 숨기기 위한 장치는 아니다.

## 5. admission을 앞당기는 일은 왜 바로 하지 않는가

현재 신규 요청은 아래 순서로 처리된다.

```text
JWT 검증
  → 회원 ID 조회
  → idempotency claim 생성
  → seat admission
  → ReentrantLock
  → reservation transaction
```

같은 좌석의 1,999개 요청이 admission 429를 받더라도, 그 전에 JWT 검증과 claim 생성은 이미 수행한다. 따라서 admission은 lock queue와 DB 예약 transaction을 보호하지만 HTTP ingress 전체를 보호하지는 않는다. 이 사실은 JFR로 확인됐고, 단일 JVM admission의 역할을 더 정확하게 설명하게 해준다.

그러나 단순히 `seat admission → claim 생성`으로 순서를 뒤집으면 같은 idempotency key의 동시 재시도 계약이 달라질 수 있다. 현재는 최초 요청이 claim을 만들면 뒤따른 같은 key 요청이 그 claim을 읽어 성공 결과를 replay하거나 `PROCESSING` 상태를 돌려준다. admission을 먼저 통과시키면, 아직 claim이 보이지 않는 아주 짧은 경쟁 구간의 재시도가 `IDEMPOTENCY_PROCESSING` 대신 `SEAT_ADMISSION_REJECTED`를 받을 수 있다.

이 변경을 시작할 때의 목표 흐름은 다음과 같다.

```text
회원 식별
  → 기존 idempotency claim 조회
      ├── 성공 claim: 저장된 결과 replay
      ├── 처리 중 claim: 처리 중 결과 반환
      └── 없는 claim: seat admission
            → 신규 claim 생성
            → reservation transaction
```

다만 `기존 claim 조회`와 `신규 claim 생성` 사이에는 여전히 동시 요청이 들어올 수 있다. `(member_id, idempotency_key)` unique constraint가 최종 경계로 남아야 하고, 중복 insert를 만난 요청이 어떤 응답으로 수렴할지까지 테스트로 고정해야 한다. 이 계약을 먼저 정하지 않은 채 admission 위치만 옮기면, 429 비율은 좋아 보여도 재시도 의미가 흐려질 수 있다.

그래서 다음 구현 단위는 admission 순서 변경이 아니라 idempotency 재시도 계약을 먼저 테스트로 고정하는 일이다. 같은 key의 성공 replay, 같은 key·다른 payload 충돌, 처리 중 재시도, admission 거절 뒤 재시도를 각각 정의한 뒤에만 순서를 바꾼다.

## 6. Tomcat과 분산 구조는 무엇을 하지 않는가

JFR 실행에서 Tomcat은 이미 `max-threads=200`에 도달했다. 이 값을 더 크게 잡으면 더 많은 worker가 CPU·class loader·security filter 경합에 동시에 올라갈 수 있다. 따라서 이번 변경은 `max-threads`를 올리는 튜닝이 아니다.

최소 idle worker를 미리 확보하는 설정은 cold burst에서 thread 생성 시간을 줄일 가능성은 있다. 하지만 이를 넣는다면 JWT parser 재사용과 route warm-up이 적용된 뒤, worker 생성만 별도로 보는 control에서 검토한다. thread 수를 바꾸고 timeout이 줄었다는 결과만으로 CPU 여유나 steady-state 용량이 늘었다고 해석하지 않는다.

application을 여러 대로 늘리는 선택도 이번 작업의 대체안이 아니다. 실제 배포가 두 JVM 이상을 요구하거나, warm reservation path와 고정된 부하 발생 조건에서도 응답 계약이 반복해서 깨지고, 같은 좌석의 진입 제어를 JVM 밖에서 공유해야 할 때 전환한다. 그 시점에는 `ReentrantLock`과 local admission을 공통 제어로 바꾸고 MySQL은 최종 정합성 경계로 유지한다.

## 7. 이번 변경의 완료 기준

이번 변경은 다음이 충족되면 닫는다.

| 구분 | 완료 기준 |
| --- | --- |
| JWT | parser가 application 생성 시 한 번 만들어지고 기존 token 검증 결과가 유지된다. |
| runner | 준비 시간은 설정 가능하며, reservation API warm-up이 405를 확인하지 못하면 burst를 시작하지 않는다. |
| boot 진단 | process 시작부터 기록한 JFR에 `Started TicketApplication`과 Spring startup step이 함께 남는다. |
| boot 개선 | `SecurityConfig`가 사용하지 않는 `MemberService`를 주입하지 않고, 인증 provider가 실제 로그인에 필요한 의존성은 그대로 유지한다. |
| 회귀 검증 | JWT 단위 테스트, Gradle 컴파일, warm-up k6 script 구문 검사, runner shell 구문 검사가 통과한다. |
| 문서 | boot JFR의 원시 위치, event 비교, 해석 범위를 기록한다. |

이번 기록에서 선택한 단계는 `SecurityConfig`의 불필요한 생성자 의존성이었다. 2,000 VU를 다시 통과시키는 것이 아니라, process start부터 readiness까지의 흐름을 확인하고 reservation path 비교가 같은 준비 상태에서 시작되게 하는 것으로 이 작업을 닫는다. 측정 원문과 해석은 `docs/130-startup-jfr-security-wiring-result.md`에 정리했다.

이 완료 기준은 2,000 VU를 다시 통과시키는 조건이 아니다. 이번 작업은 JFR이 드러낸 cold path 비용을 제거하고, 다음 성능 비교가 같은 준비 상태에서 시작되게 만드는 것으로 끝낸다.
