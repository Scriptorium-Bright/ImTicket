# 🚀 ImTicket: 인프라 및 아키텍처 고도화 전략

## 1. 🎯 전략적 방향성: "Why not Kafka?"에 대한 답변
이 프로젝트는 **'기술을 위한 기술 도입'**이 아닌, **'비즈니스 요구사항과 제약 조건에 맞춘 최적화된 아키텍처'**를 지향합니다.

### 🛡 핵심 논리 (Defense Logic)
*   **문제 의식**: Kafka는 강력하지만, 현재 프로젝트 규모(Monolith, 단일 개발자) 및 트래픽 특성상 운영 오버헤드가 과도합니다. 이는 전형적인 **Over-Engineering** 사례가 될 수 있습니다.
*   **해결책**: **Redis**를 단순 캐시 용도가 아닌, **In-Memory Message Broker**로 확장하여 활용합니다.
*   **전문성 어필 포인트**:
    *   "단순히 몰라서 안 쓴 것이 아닙니다."
    *   "Kafka의 내구성(Durability)과 확장성(Scalability)이 필요한 지점과, Redis의 경량성(Lightweight)과 속도(Speed)가 필요한 지점을 명확히 구분했습니다."
    *   "현재의 모놀리식 구조와 제한된 리소스 내에서 **결합도(Coupling)를 낮추고 데이터 유실을 방지**하기 위해 **Redis Streams**를 선택했습니다."

---

## 2. 🏗 아키텍처 업그레이드: From @Async to Redis Streams

현재 `VenueHallService`와 `SeatService`에서 사용 중인 `@Async`는 편리하지만, **애플리케이션 재시작 시 작업 유실 위험**이 있습니다. 이를 Redis Streams로 대체하여 안정성을 확보합니다.

### 🔄 변경 전 (Current)
*   **Flow**: Client Request -> Controller -> Service(`@Async` Method) -> DB Save
*   **Risk**: 대량의 좌석 생성 중 서버가 배포되거나 비정상 종료되면, 작업이 중간에 끊기고 복구가 불가능합니다. (데이터 정합성 깨짐)

### ✅ 변경 후 (Target) - Redis Streams 도입
*   **Flow**:
    1.  **Producer**: Client Request -> Controller -> **Redis Stream (Topic: `seat-creation`)** 에 이벤트 발행 (ACK 반환)
    2.  **Broker**: Redis가 메시지를 영구 저장 (AOF/RDB 설정으로 내구성 보장 가능)
    3.  **Consumer**: `StreamListener` -> Service -> DB Save
*   **Benefit**:
    *   **신뢰성**: 서버가 죽어도 Redis에 메시지가 남아있어, 재기동 시 **Consumer Group**이 마지막 지점부터 다시 처리합니다.
    *   **확장성**: 추후 좌석 생성 서버를 스케일 아웃하더라도 중복 처리 없이 병렬 처리가 가능합니다.

### 🛠 구현 계획 (Implementation)
1.  **Redis Config**: `Lettuce` 라이브러리를 사용해 Redis Template 및 StreamListenerContainer 설정.
2.  **Producer**: `VenueHallService`, `SeatService`의 로직 호출 대신 `SeatCreationEvent` 객체를 발행하는 코드로 대체.
3.  **Consumer**: `SeatCreationEventListener`를 구현하여 실제 DB 작업 로직(`processSeats` 등) 이관.
4.  **Error Handling**: 처리 실패 시 Dead Letter Queue 역할을 할 별도 Stream 또는 Retry 로직 구현.

---

## 3. ⚡ Redis Caching: "성능 최적화의 꽃" (가성비 전략)

단순한 Key-Value 저장을 넘어, **전략적 캐싱 패턴**을 적용하여 실질적인 성능 개선을 증명합니다.

### 🎯 전략 선택: Look-aside (Lazy Loading) Pattern
*   **Target**: **공연 상세 정보(Performance Info)** 등 조회 빈도는 높으나 변경 빈도는 낮은 데이터.
*   **Why Look-aside?**:
    *   **안전성**: Redis가 다운되더라도 DB에서 데이터를 가져올 수 있어 서비스 장애(SPOF)를 방지합니다.
    *   **효율성**: 실제 요청이 있는 데이터만 캐싱되므로 불필요한 메모리 낭비를 막습니다.

### 🔄 Flow
1.  **Client Request**: 공연 정보 요청.
2.  **Cache Check**: Redis에 데이터가 있는지 확인 (Cache Hit).
3.  **DB Fallback**: 없으면(Cache Miss) DB 조회 후 Redis에 저장(TTL 설정 필수).
4.  **Return**: 결과 반환.

### 📈 증명 포인트 (For Portfolio)
*   **"DB 부하 감소 증명"**:
    *   k6 부하 테스트 시, 캐시 적용 전후의 **DB CPU Usage** 및 **Query Throughput** 비교.
    *   멘트 예시: *"메인 페이지 접속 시 발생하는 공연 목록 조회 쿼리에 Look-aside 캐싱을 적용하여, 동시 접속자 1,000명 상황에서 DB CPU 부하를 80% -> 20%로 감소시켰습니다."*

---

## 4. ♾️ DevOps: "운영 환경을 이해하는 개발자" (Docker + CI/CD)

단순한 코드 실행이 아닌, **'서비스 운영'** 관점의 포트폴리오를 완성합니다.

### 📦 Docker & Compose
*   **목표**: "Local == Production". 개발 환경과 배포 환경의 차이로 인한 버그(Works on my machine) 원천 차단.
*   **Action**:
    *   `docker-compose.yml`: Spring Boot + Redis + (Optional: MySQL) 통합 구성.
    *   Application Health Check 설정.

### 🚀 CI/CD Pipeline (Github Actions)
*   **Flow**:
    1.  **Push**: Main 브랜치 푸시
    2.  **Build**: Gradle Build (Test 포함)
    3.  **Image Build**: Docker Image 빌드 및 Docker Hub 푸시
    4.  **Deploy**: (Self-Hosted Runner 또는 SSH) 운영 서버에서 `docker-compose pull && docker-compose up -d`
*   **Point**: "테스트가 통과하지 않으면 배포되지 않는다"는 안전 장치를 보여줍니다.

---

## 4. 📊 Monitoring: "문제 해결의 시각화"

화려한 그래프보다 **'병목 지점 발견 및 해결 증거'**로서 활용합니다.

### 🎯 핵심 지표 (Key Metrics)
1.  **HikariCP Connection Pool**: DB 커넥션 고갈로 인한 지연 확인 (N+1 문제나 트랜잭션 범위 문제 발견 시 유용).
2.  **CPU / Memory Usage**: 대량의 좌석 생성(`Stream Consumer`) 시 부하 모니터링.
3.  **Slow Query**: 쿼리 실행 시간 모니터링.

### 🛠 구성
*   **Prometheus**: Spring Boot Actuator 지표 수집.
*   **Grafana**: 시각화 대시보드.
*   **k6 (Load Testing)**: 트래픽을 발생시켜 그래프의 변화(CPU 스파이크, 커넥션 풀 부족)를 캡처 -> 포트폴리오의 '문제 해결' 섹션 이미지로 활용.

---

## 📅 Action Plan (1 Week)

| Day | Focus | Tasks |
| :--- | :--- | :--- |
| **Day 1** | **Plan & Redis Set** | 현재 계획 확정, Redis Docker 설치 및 Spring Boot 연동 |
| **Day 2** | **Refactor Code** | `@Async` 제거 -> Redis Streams (Producer/Consumer) 구현 |
| **Day 3** | **Dockerize** | `Dockerfile`, `docker-compose.yml` 작성 및 로컬 구동 테스트 |
| **Day 4** | **CI/CD** | Github Actions 스크립트 작성, 자동 배포 파이프라인 구축 |
| **Day 5** | **Monitoring** | Prometheus + Grafana 연동, 대시보드 구성 |
| **Day 6** | **Testing** | k6 부하 테스트 수행, 모니터링 지표 캡처 (Before/After) |
| **Day 7** | **Documentation** | README, 포트폴리오 업데이트 (의도적 배제 논리 서술) |

---

## 5. ✅ 진행 상황 로그 (완료된 변경 사항)

### 🔑 보안 (JWT 리팩토링)
*   **더 깔끔한 아키텍처**: 불필요한 `JwtTokenProvider`를 제거하고 로직을 `JwtUtil`로 통합했습니다.
*   **향상된 응답**: 타입 안전성이 보장된 API 응답을 위해 `TokenResponse` DTO를 도입했습니다.
*   **보안 모범 사례 적용**: 민감한 토큰 로깅을 제거하고, 토큰 파싱에 대한 강력한 예외 처리를 추가했습니다.

### 🌊 Redis Streams 구현
*   **이벤트 기반 아키텍처**: `SeatCreationProducer` (발행자)와 `SeatCreationConsumer` (구독자)를 구현했습니다.
*   **신뢰성 (내구성)**: 서버 재시작 후에도 메시지가 처리되도록 Pub/Sub 대신 **Consumer Groups** (`seat-creation-group`)을 사용했습니다.
*   **확장성**:
    *   **UUID Consumer Name**: 충돌 없이 여러 인스턴스가 병렬로 스트림을 처리할 수 있도록 고유한 Consumer ID(예: `consumer-a1b2c3d4`)를 할당했습니다.
    *   **명시적 JSON 직렬화**: Java 직렬화 문제를 피하고 호환성을 보장하기 위해 명시적인 `ObjectMapper` 직렬화로 전환했습니다.
*   **비교 모드**: `VenueController`가 `?type=stream` 파라미터를 지원하도록 업데이트하여, 기존 `@Async` 방식과 새로운 `Redis Streams` 로직을 모두 검증할 수 있게 했습니다.
*   **인프라**: 시작 시 Stream과 Consumer Group이 없으면 자동으로 생성하도록 `RedisConfig`를 구성했습니다.

### 🚀 CI/CD (GitHub Actions)
*   **워크플로우 생성**: `.github/workflows/ci.yml` 파일을 추가했습니다.
    *   **빌드**: JDK 21에서 `./gradlew clean build`를 자동화합니다.
    *   **Docker 통합**: `main` 브랜치 병합 시 Docker 이미지 빌드 및 Docker Hub 푸시를 자동화합니다.
