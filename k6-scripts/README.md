# k6 부하 테스트 기준

## 현재 판단

| 스크립트 | 상태 | 이유 |
|---|---|---|
| `01-ticket-open-run.js` | 현재 기준 | 같은 한 좌석으로 단발 burst를 만들고 결과를 성공/충돌/내부 오류로 분리한다. |
| `02-seat-check-spike.js` | 실행 금지 | 현재 존재하지 않는 `/empty-seat` 경로와 8080 포트를 사용한다. 좌석 캐시는 현재 범위에서도 제외됐다. |
| `04-sms-auth-spike.js` | 별도 검토 | 비관적 락 기준선과 무관하다. |
| `05-cache-stampede.js` | 수정 필요 | 현재 존재하지 않는 공연 상세 경로와 8080 포트를 사용한다. |
| 루트 `load-test-reservation.js` | 실행 금지 | 무작위 좌석으로 hot-row 경합이 희석되고, 3천~3만 constant VU는 로컬 등급 모델로 적절하지 않다. |

## 등급 1~3 해석

공연 등급의 동시접속자 수를 로컬 k6 VU와 동일하게 사용하지 않는다.

| 등급 | 사용자 시나리오 | 로컬 hot-seat 동시 요청 기본값 |
|---|---:|---:|
| 1 | 500~5,000명 | 50 VU |
| 2 | 5,000~30,000명 | 100 VU |
| 3 | 20,000~100,000명 | 200 VU |

이 값은 운영 수용량이 아니라 같은 좌석에 대한 lock 경합 강도를 단계적으로 높이기 위한 축소 프로필이다. 실제 사용자 수 모델에는 도착 시간창, 좌석 선택 분포, 사용자별 polling, 요청률을 별도로 정의해야 한다.

## Pessimistic Lock 기준선

fixture 생성 결과에서 `performance_time_id`와 등급별 `seat_id`를 확인한다.

```bash
MYSQL_PASSWORD='로컬 테스트 DB 비밀번호' \
scripts/load/seed_pessimistic_lock_fixture.sh
```

기준선 실행:

```bash
k6 run \
  -e GRADE=1 \
  -e PT_ID=... \
  -e SEAT_ID=... \
  -e JWT_SECRET='서버와 같은 테스트용 secret' \
  --summary-export build/k6-results/pessimistic-grade-1.json \
  k6-scripts/01-ticket-open-run.js
```

강제 timeout 실행은 별도 터미널에서 먼저 row lock을 보유한 뒤 시작한다.

```bash
MYSQL_PASSWORD='로컬 테스트 DB 비밀번호' \
SEAT_ID=... \
HOLD_SECONDS=6 \
scripts/load/hold_pessimistic_seat_lock.sh
```

```bash
MYSQL_PASSWORD='로컬 테스트 DB 비밀번호' \
MODE=forced-timeout \
GRADE=1 \
PT_ID=... \
SEAT_ID=... \
BASE_URL=http://127.0.0.1:10080 \
JWT_SECRET='서버와 같은 테스트용 secret' \
scripts/load/run_pessimistic_lock_k6.sh
```

## 결과 해석

- baseline에서는 성공이 정확히 한 건이어야 한다.
- 나머지는 `409 SEAT_ALREADY_RESERVED`가 정상적인 business conflict다.
- forced-timeout의 500/503은 k6 응답만으로 lock timeout이라고 단정하지 않는다.
- forced-timeout에서 내부 오류가 반드시 나야 한다는 threshold는 두지 않는다. 실제 timeout 정책이 적용되는지 자체가 측정 대상이다.
- 같은 시각의 서버 예외 로그, MySQL `data_lock_waits`, Hikari pending을 함께 확인한다.
- 같은 한 row의 경합은 lock wait/timeout 시나리오다. deadlock은 좌석 여러 개를 반대 순서로 잠그는 MySQL 통합 테스트에서 별도로 검증한다.
