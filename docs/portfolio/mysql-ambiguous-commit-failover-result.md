# MySQL ambiguous commit failover rehearsal result

작성일: 2026-09-20  
기준 브랜치: `experiment/mysql-failover-ambiguous-commit`  
GitHub Actions run: `35476503679`  
실행 환경: GitHub Actions `ubuntu-24.04`, Docker, MySQL 8.0.46

## 1. 검증 질문

예약 transaction이 primary에서 commit되고 replica에도 반영됐지만 클라이언트가 성공 응답을 받지 못한 상태에서 primary 장애와 replica 승격이 발생하면, 같은 `Idempotency-Key` 재시도가 기존 예약을 재생하는지 검증한다.

이번 시험은 다음 경계를 확인한다.

- commit 완료와 client acknowledgement를 분리한다.
- failover 전 replica가 해당 commit을 보유하는지 확인한다.
- 동일 요청 재시도에서 Reservation이 추가 생성되지 않는지 확인한다.
- DB 승격 시간과 전체 서비스 복구 시간을 분리한다.

## 2. 실행 시나리오

1. 임시 MySQL primary와 replica를 GTID replication으로 구성한다.
2. 단일 회원, 공연 회차, 좌석 fixture를 primary에 생성한다.
3. fixture가 replica에 복제된 것을 확인한다.
4. Reservation Application을 primary에 연결한다.
5. 좌석 조회 snapshot을 예열한다.
6. Redis `CLIENT PAUSE ALL`로 commit 이후 cache invalidation 경로의 응답 완료를 지연시킨다.
7. 고정된 `Idempotency-Key`로 `POST /api/reservation/pre-reserve`를 시작한다.
8. primary의 `reservation_idempotency`가 `SUCCEEDED`이고 `reservation_id`가 생성된 것을 확인한다.
9. replica에도 같은 `SUCCEEDED / reservation_id`가 반영된 것을 확인한다.
10. primary를 중단한다.
11. 최초 client request가 성공 응답을 받지 못하고 timeout되는 것을 확인한다.
12. replica를 promote하고 application을 새 primary로 전환한다.
13. 같은 JWT, payload, `Idempotency-Key`로 요청을 재전송한다.
14. 재시도 응답과 최종 DB row 수를 검증한다.

## 3. 결과

| 항목 | 결과 |
|---|---:|
| workflow | success |
| 최초 client curl exit | `28` |
| 최초 HTTP code | `000` |
| 최초 client timeout | 약 6.0초, 0 byte 수신 |
| failover 전 Reservation ID | `1` |
| replica commit 확인 | `true` |
| replica promotion | `89ms` |
| 전체 서비스 RTO | `34,770ms` |
| 동일 key 재시도 HTTP | `200` |
| 재시도 Reservation ID | `1` |
| idempotency row | `1` |
| Reservation row | `1` |
| ReservedSeat row | `1` |
| 최종 idempotency 상태 | `SUCCEEDED` |
| 최종 좌석 상태 | `LOCKED` |
| 최종 판정 | `OK` |

최초 요청은 다음 오류로 종료됐다.

```text
curl: (28) Operation timed out after 6002 milliseconds with 0 bytes received
```

failover 이후 동일 key 재시도 응답은 기존 Reservation ID `1`을 반환했다.

```json
{
  "success": true,
  "data": {
    "id": 1,
    "totalPrice": 100000
  }
}
```

재시도 시 application log에는 `reservation_idempotency.uk_reservation_idempotency_member_key` unique constraint 충돌이 기록됐다. 최종 응답과 DB 상태를 함께 확인한 결과 새 예약이 추가 생성된 것이 아니라 기존 idempotency claim을 찾아 같은 Reservation snapshot을 재생하는 경로로 수렴했다.

## 4. 해석

이번 실행에서는 다음을 확인했다.

### 4.1 client acknowledgement가 없어도 DB commit 여부를 별도로 판정해야 한다

최초 client는 HTTP 응답을 전혀 받지 못했지만 primary와 replica에는 이미 동일 Reservation이 존재했다. 따라서 network timeout 또는 장애 구간의 client 결과만으로 transaction 실패를 판단하면 같은 업무 요청을 다시 생성할 위험이 있다.

### 4.2 Idempotency-Key가 failover 이후에도 업무 중복을 막았다

같은 key를 새 primary에 재전송했을 때 신규 Reservation이 만들어지지 않았고 기존 Reservation ID `1`이 반환됐다.

최종 row 수는 모두 한 건이었다.

- `reservation_idempotency = 1`
- `Reservation = 1`
- `ReservedSeat = 1`

### 4.3 이번 RTO의 대부분은 DB promotion이 아니다

replica의 read-only 해제와 promotion 자체는 `89ms`였다. 반면 application을 새 primary로 재연결하고 readiness를 회복하기까지 포함한 전체 RTO는 `34.77초`였다.

현재 구조에서 다음 복구 최적화 대상은 MySQL promotion보다 application connection cutover와 startup 경로다.

## 5. 증거 범위와 한계

이번 결과는 아래 범위 안에서 해석한다.

- 로컬 운영 실적이 아니라 GitHub Actions의 격리 Docker 장애주입 실험이다.
- 한 개의 Reservation 요청을 사용한 결정적 재현이다. 다수 동시 write의 성공률이나 처리량을 의미하지 않는다.
- 응답 유실을 안정적으로 만들기 위해 Redis `CLIENT PAUSE`로 commit 이후 cache invalidation 경로를 지연시켰다. DB 장애 자체가 응답 지연의 원인이라는 결과는 아니다.
- primary를 중단하기 전에 해당 commit이 replica까지 도달한 것을 확인했다. 따라서 이번 시험은 `replicated commit + lost response` 경계이며, 아직 복제되지 않은 commit의 RPO는 별도 시험 대상이다.
- application DB endpoint 전환은 container 재기동으로 수행했다. ProxySQL, HAProxy, MySQL Router 같은 연결 전환 계층의 성능을 검증한 결과는 아니다.
- split-brain이나 old primary fencing은 이번 범위에 포함하지 않았다.

## 6. 다음 실험 후보

현재 결과에서 다음 우선순위가 자연스럽다.

1. application 재기동 없이 DB endpoint를 전환해 `34.77초` RTO를 어디까지 줄일 수 있는지 측정
2. 여러 개의 서로 다른 seat/write가 진행되는 동안 primary를 중단하고 client 결과와 DB 최종 결과를 집합으로 분류
3. replica에 도달하지 않은 commit을 의도적으로 만들어 RPO와 재시도 계약 검증
4. old primary를 종료하지 않은 network partition에서 fencing과 split-brain 방지 검증

## 7. 포트폴리오 표현 기준

사용 가능한 표현:

> GTID replica에 반영된 예약 transaction의 성공 응답이 유실된 상태에서 primary 장애와 replica 승격을 재현하고, 동일 Idempotency-Key 재시도로 기존 Reservation을 재생해 Reservation·ReservedSeat·idempotency row가 각각 1건으로 유지되는 것을 검증했다. 격리 Docker 환경에서 replica promotion은 89ms, application cutover를 포함한 전체 서비스 RTO는 34.77초였다.

피해야 할 표현:

- 실서비스 DB 장애를 복구했다.
- 무손실 HA를 완성했다.
- 모든 장애에서 exactly-once를 보장한다.
- RPO가 항상 0이다.
