# application boot에서 확인한 SecurityConfig 의존성

## 기록을 다시 시작한 이유

2,000 VU cold reservation 실행에서 `14.684초`의 p95가 관측됐다. 이 값은 좌석 lock의 처리시간인지, 요청이 좌석 제어에 도달하기 전에 JVM과 Tomcat에서 소비된 시간인지 구분하기 어려웠다. 그래서 먼저 application boot와 첫 reservation 요청을 분리하기로 했다.

초기 문서에 적었던 `164.578초`는 boot 원인으로 사용할 수 없는 값이었다. application이 시작된 뒤 붙은 idle JFR의 시점과 container ready 시점을 함께 읽은 결과였기 때문이다. 그 기록으로는 어느 bean이나 외부 연결이 시간을 사용했는지 알 수 없었다. 해당 수치는 실행기의 health timeout을 정하는 참고로만 남기고, 성능 근거에서는 제외했다.

이번에는 JVM 시작 명령에 `-XX:StartFlightRecording`을 넣고, Spring에는 `FlightRecorderApplicationStartup`을 켰다. JVM event와 Spring startup step이 같은 시간축에 기록되므로, thread가 바쁜 이유와 Spring이 생성하던 bean을 연결해서 볼 수 있다.

```text
JVM process 시작
  → launch-time JFR
  → Spring configuration class parsing
  → repository / JPA / security bean 생성
  → Started TicketApplication
  → health 통과
```

기록 스크립트는 `scripts/test/capture_startup_jfr.sh`로 분리했다. 정상 실행에서는 `TICKET_STARTUP_JFR_ENABLED=false`이고, 진단할 때만 환경변수와 `JAVA_TOOL_OPTIONS`를 주입한다. recording 기본 시간은 180초다. 90초 기록은 이번 환경에서 application이 끝나기 전에 닫혔기 때문에 비교 자료로 쓰지 않았다.

## JFR이 보여준 생성 순서

변경 전 기준 기록은 `uploads/jfr/boot-20260721T073755Z.jfr`이고, 변경 후 기록은 `build/k6-results/startup-jfr/boot-20260721T075532Z/boot.jfr`이다. 두 기록 모두 `profile` 설정과 같은 compose 의존성(MySQL·Redis·Prometheus)을 사용했다.

Spring startup event의 duration은 부모 event 안에 포함될 수 있으므로 단순 합산하지 않았다. 중요한 것은 총합보다 어떤 bean이 어떤 부모 단계에서 생성됐는지였다.

| startup event | 변경 전 | 변경 후 | 읽은 의미 |
| --- | ---: | ---: | --- |
| `securityConfig` | 15.355초 | 0.286초 | 설정 클래스 생성이 JPA 의존성을 직접 끌고 오던 경로가 사라졌다. |
| `metamaskAuthenticationFilter` | 15.608초 | 3.726초 | filter 생성 중 기다리던 security/JPA 경로가 짧아졌다. |
| `memberService` | 13.780초 | 0.019초 | `SecurityConfig` 생성 때문에 조기에 만들어지던 비용이 없어졌다. provider 경로에서 필요한 생성은 남아 있다. |
| `memberRepository` | 13.610초 | 3.324초 | repository와 EntityManagerFactory 초기화가 security 설정 생성과 겹치지 않게 됐다. |
| `entityManagerFactory` | 9.123초 | 2.582초 | JPA 자체를 제거한 것이 아니라, 생성 시점과 부모 경계가 달라졌다. |
| `spring.context.config-classes.parse` | 7.170초 | 1.924초 | configuration parsing 시간은 호스트 상태의 영향도 있으므로 보조 지표로만 사용한다. |

실제 로그의 `Started TicketApplication`도 기준 기록에서는 `44.239초`, 변경 후 기록에서는 `10.424초`였다. 이 두 번의 wall-clock 값만으로 steady-state 성능 향상을 주장하지는 않는다. MySQL 응답과 호스트 자원 사용이 포함된 값이기 때문이다. 대신 `securityConfig`가 `15.355초`에서 `0.286초`로 줄고, 그 안에 있던 `memberService` 생성이 `0.019초`로 이동한 것은 코드 변경과 직접 대응하는 Spring event 근거다.

## 원인은 사용하지 않는 생성자 의존성이었다

변경 전 `SecurityConfig`는 Lombok의 `@RequiredArgsConstructor`를 통해 아래 필드를 생성자 인자로 가지고 있었다.

```java
private final AuthenticationConfiguration authenticationConfiguration;
private final JwtUtil jwtUtil;
private final ObjectMapper objectMapper;
private final MemberService memberService; // SecurityConfig에서는 사용하지 않음
```

`SecurityConfig`의 filter chain과 bean 정의는 `MemberService`를 참조하지 않았다. 그러나 Spring은 configuration bean을 만들기 위해 이 생성자 인자를 준비해야 했고, 그 과정에서 `MemberRepository`와 JPA EntityManagerFactory가 security filter 생성의 critical path에 들어왔다.

그래서 `SecurityConfig`에서 import와 필드만 제거했다. `MetamaskAuthenticationProvider`가 `MemberService`를 사용하는 부분은 그대로 두었다. provider는 서명 검증 뒤 login nonce를 소비하고 challenge message를 만드는 실제 인증 책임이 있으므로, 그 의존성까지 제거하거나 지연시키면 인증 동작을 바꾸게 된다.

변경 후 JFR에는 여전히 `metamaskAuthenticationProvider → memberRepository / entityManagerFactory / memberService` 경로가 남아 있다. 이 경로는 로그인 provider가 실제로 필요로 하는 의존성이다. 사라진 것은 `SecurityConfig`가 사용하지 않던 직접 의존성이지, 인증에 필요한 member 조회 자체가 아니다.

## 이 결과가 reservation 판단에 미치는 범위

이번 변경은 좌석 정합성 경계를 바꾸지 않는다. 단일 JVM의 좌석별 공정 `ReentrantLock`, seat admission, MySQL 예약 상태 변경은 그대로다. Tomcat `max-threads`를 올리지 않았고, application을 여러 대로 나누거나 분산 lock을 넣지도 않았다.

JFR의 결론은 다음과 같다.

```text
SecurityConfig가 사용하지 않는 MemberService를 직접 주입
  → security filter 생성 시 JPA 초기화가 함께 시작
  → boot critical path가 길어짐

SecurityConfig에서 해당 필드 제거
  → configuration bean은 설정에 필요한 의존성만 생성
  → 인증 provider의 MemberService 경로는 유지
```

이제 reservation 성능을 비교할 때는 새 JVM의 boot 시간을 reservation p95로 섞지 않는다. runner는 health를 확인한 뒤 인증된 `GET /api/reservation/pre-reserve`를 보내 MVC·security 진입 경로를 한 번 준비하고, 405를 확인한 뒤 burst를 시작한다. boot 자체를 분석할 때는 이 warm-up과 별개로 launch-time JFR을 사용한다.

## 재현 위치와 검증

변경 후 원본 JFR과 startup 로그는 다음 위치에 있다.

```text
build/k6-results/startup-jfr/boot-20260721T075532Z/boot.jfr
build/k6-results/startup-jfr/boot-20260721T075532Z/app-startup.log
build/k6-results/startup-jfr/boot-20260721T075532Z/boot-jfr-summary.txt
```

검증은 `./gradlew compileJava`, `bash -n scripts/test/capture_startup_jfr.sh`와 JFR의 `FlightRecorderStartupEvent` 추출로 끝냈다. 캡처 스크립트는 기록이 끝난 뒤 JFR 옵션 없이 app을 다시 생성하도록 되어 있으며, 마지막 실행은 `startup_jfr_capture=passed`로 종료됐다.

이번 기록으로 boot 원인에 대해 선택한 변경은 닫았다. 이후의 과제는 lock 전략을 다시 고르는 일이 아니라, warm reservation path에서 JWT·MVC·admission이 각각 어떤 응답 계약을 만드는지 같은 준비 조건으로 비교하는 일이다.
