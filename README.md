# ImTicket 🎟️

> **동시접속 트래픽 처리에 집중한 티켓팅 플랫폼 백엔드**
> Spring Boot 3.4 · Java 21 · MySQL · Redis · Metamask Auth

---

## 이 프로젝트가 해결하는 문제

| 문제 상황 | 적용 기술 |
|---|---|
| 수천 명이 동시에 같은 좌석을 예매하면? | MySQL Row-level Lock (`SELECT FOR UPDATE`) |
| 여러 서버 인스턴스가 동시에 만료 예약을 정리하면? | ShedLock (DB 기반 분산 락) |
| 오픈 순간 예매 요청이 폭발하면? | 좌석 경합·admission·대기열을 측정해 DB 유입량을 제어 |
| 티켓 QR이 복사/캡처되어 재사용되면? | HMAC-SHA256 동적 QR (60초 유효) |
| ID/PW 없이 Web3 지갑으로 로그인하고 싶으면? | Metamask ECDSA 서명 검증 (Web3j) |

---

## 기술 스택

```
Backend   : Spring Boot 3.4.4, Java 21
Database  : MySQL 8.0 (JPA/Hibernate)
Cache     : Redis (Lettuce)
Auth      : Spring Security + JWT + Web3j
Async     : @Async + ThreadPoolTaskExecutor
Lock      : ShedLock 5.13 (분산 스케줄러 락)
SMS       : CoolSMS (NuriGo SDK)
Payment   : Iamport (미완성)
Monitoring: Micrometer + Prometheus + Grafana
API Docs  : springdoc-openapi (Swagger UI)
Container : Docker + docker-compose
Load Test : k6
```

---

## 아키텍처 요약

```
Frontend (React)
       │
       ▼
Spring Boot (port: 10080)
  ├── Security Filter (JWT + Metamask)
  ├── CorrelationId Filter (MDC 추적)
  ├── Controllers → Services → Repositories
  ├── Redis (캐시 + SMS 인증코드)
  └── MySQL (메인 DB)

Monitoring
  Actuator → Prometheus → Grafana
```

---

## 빠른 시작

```bash
# 1. MySQL + Redis 실행
docker-compose up mysql redis -d

# 2. 서버 실행
./gradlew bootRun

# 3. API 문서 확인
open http://localhost:10080/swagger-ui/index.html
```

### 환경변수 (`.env`)

```env
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:10046/capstone?useSSL=false&useUnicode=true&serverTimezone=Asia/Seoul&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=capstone
SPRING_DATASOURCE_PASSWORD=cider123
SPRING_JWT_SECRET=your-jwt-secret
TICKET_ENTRY_SECRET=your-entry-secret
COOLSMS_API_KEY=your-coolsms-key
COOLSMS_API_SECRET=your-coolsms-secret
COOLSMS_API_FROM=01000000000
TICKET_SMS_ALLOW_TEST_CODE=true   # 테스트 시 인증코드 123456 고정
```

---

## 주요 API

| 용도 | 메서드 | 경로 |
|---|---|---|
| Nonce 발급 | GET | `/api/user/nonce?walletAddress=` |
| 지갑 로그인 | POST | `/api/user/login` |
| 회원 등록 | POST | `/api/user/register` |
| SMS 발송 | POST | `/api/sms/certificate` |
| 공연 목록 | GET | `/api/performance` |
| 빈 좌석 조회 | GET | `/api/seats/{performanceTimeId}` |
| 예약 생성 | POST | `/api/reservation/pre-reserve` |
| 예약 확정 | POST | `/api/reservation/{id}/confirm` |
| QR 토큰 발급 | GET | `/api/entry/token/{reservationId}` |
| QR 토큰 검증 | POST | `/api/entry/verify` |

---

## 예약 플로우

```
좌석 생성 (비동기)
POST /api/seats/{performanceTimeId}
        │
        ▼
빈 좌석 조회
GET /api/seats/{performanceTimeId}
        │
        ▼
예약 생성 (SELECT FOR UPDATE → LOCKED 상태, 7분 유효)
POST /api/reservation/pre-reserve
        │
        ▼
예약 확정 (SUCCESS 상태)
POST /api/reservation/{id}/confirm
        │
        ▼
QR 토큰 발급 (HMAC-SHA256, 60초 유효)
GET /api/entry/token/{reservationId}
        │
        ▼
입장 검증
POST /api/entry/verify
```

---

## 테스트

```bash
# 전체 테스트
./gradlew test

# 동시성 테스트 (100명 동시 예매)
./gradlew test --tests "*ReservationConcurrencyTest*"

# 분산 환경 시뮬레이션 (ShedLock 검증)
./scripts/test/run-cluster.sh
```

### 테스트 분류

| 유형 | 설명 |
|---|---|
| **동시성 테스트** | 100명 동시 좌석 예매 → 1명만 성공 검증 |
| **배드스멜 테스트** | 실제 버그/성능 문제 재현 (의도적 실패) |
| **분산 환경 테스트** | ShedLock 없이 다중 스케줄러 중복 실행 증명 |
| **단위 테스트** | 서비스/필터 단위 검증 |

---

## 모니터링

```bash
# 최초 실행 시 .env.example을 참고해 .env 작성
docker compose up -d --build
docker compose ps

# 수집 상태와 경보 규칙 확인
curl -fsS 'http://127.0.0.1:9090/api/v1/query?query=up%7Bjob%3D%22imticket-app%22%7D'
open http://127.0.0.1:3000/d/imticket-overview
```

- Grafana의 Prometheus 데이터소스와 `ImTicket Service Overview` 대시보드는 시작 시 자동 등록됩니다.
- 애플리케이션 메트릭은 1초 간격으로 수집하며 Prometheus/Grafana 데이터는 named volume에 보존됩니다.
- Prometheus와 Grafana 포트는 기본적으로 `127.0.0.1`에만 바인딩됩니다.
- Prometheus에는 가용성·5xx·p95·Tomcat·Hikari·JVM 경보 규칙이 포함되며, 외부 알림 전송은 Alertmanager 추가 후 활성화됩니다.
- 상세 실행 및 검증 기준: [`docs/84-prometheus-grafana-monitoring-system.md`](docs/84-prometheus-grafana-monitoring-system.md)

---

## 상세 문서

| 문서 | 내용 |
|---|---|
| [PROJECT_OVERVIEW.md](./PROJECT_OVERVIEW.md) | **전체 문서** (도메인, API, 테스트, 설정 모두 포함) |
| [DB_SCHEMA.md](./DB_SCHEMA.md) | DB 스키마 |
| [docs/03-auth-and-security.md](./03-auth-and-security.md) | 인증 상세 |
| [docs/04-reservation-and-entry.md](./04-reservation-and-entry.md) | 예약/입장 상세 |

---

## Known Issues

| 우선순위 | 문제 |
|---|---|
| 🔴 HIGH | `@SchedulerLock` 비활성화 → 다중 인스턴스에서 중복 스케줄링 |
| 🔴 HIGH | 클린업 시 SUCCESS 예약도 삭제될 수 있는 로직 오류 |
| 🟡 MID | Redis admission·대기열 미구현 (병목 측정 후 도입 여부 결정) |
| 🟡 MID | 결제(Iamport) 미구현 |
