# ImTicket에서 MySQL을 선택한 이유

## 결론

ImTicket이 MySQL을 선택한 이유를 단순히 "무료 오픈소스라서"라고만 설명하면 부족하다. PostgreSQL도 오픈소스이고, SQL Server와 Oracle도 개발 및 학습에 사용할 수 있는 무료 에디션이 있기 때문이다.

이 프로젝트에서 방어 가능한 설명은 다음과 같다.

```text
초기 개발 단계에서 팀이 가장 익숙하게 사용할 수 있었고, Spring Data JPA와 Docker 환경에서 빠르게 구성할 수 있는 관계형 데이터베이스가 MySQL이었습니다. 예매 서비스는 회원, 공연 회차, 좌석, 예약 사이의 관계와 트랜잭션 정합성이 중요했기 때문에 관계형 DB가 필요했습니다. MySQL InnoDB는 트랜잭션과 row-level lock을 제공해 동일 좌석 중복 선점을 막는 현재 구조를 구현할 수 있었고, performance_schema와 EXPLAIN을 이용해 lock wait와 인덱스 병목을 분석할 수 있었습니다.

PostgreSQL도 요구사항을 충분히 만족하는 대안이었지만, 당시 프로젝트에는 PostgreSQL 고유 기능이 필요한 요구사항이 없었습니다. SQL Server와 Oracle 역시 기술적으로 가능하지만, 팀의 경험과 개발 환경을 고려했을 때 추가 학습 및 운영 복잡도를 감수할 근거가 없었습니다. 따라서 제품 간 절대적인 우열보다 요구사항 충족 여부, 팀 숙련도, 구축 비용을 기준으로 MySQL을 선택했습니다.
```

## 당시 선택 이유와 현재 평가를 분리한다

면접에서는 처음부터 모든 DB를 정밀 비교하고 MySQL을 선택한 것처럼 말하면 안 된다. 저장소에는 그런 사전 비교 기록이 없다.

### 당시 선택 이유

- 팀이 MySQL과 Spring Data JPA 조합에 상대적으로 익숙했다.
- 로컬 및 Docker Compose 환경을 빠르게 구성할 수 있었다.
- 별도의 상용 라이선스 구매 없이 개발을 시작할 수 있었다.
- 서비스 데이터가 회원, 공연, 회차, 좌석, 예약처럼 관계가 명확했다.
- 예매 생성과 좌석 상태 변경을 하나의 트랜잭션으로 처리할 관계형 DB가 필요했다.

### 개발 이후 확인한 적합성

- InnoDB의 row-level lock을 이용해 좌석 선점 구간에 비관적 락을 적용했다.
- `performance_schema.data_lock_waits`, `data_locks`, `SHOW ENGINE INNODB STATUS`로 lock wait를 관측할 수 있었다.
- `EXPLAIN ANALYZE`와 인덱스를 이용해 예약 만료 조회의 병목 후보를 분석했다.
- MySQL Connector/J, Hibernate, HikariCP를 현재 Spring Boot 구조에서 사용할 수 있었다.
- Docker의 MySQL 8.0 이미지로 팀 개발 환경을 통일할 수 있었다.

두 번째 목록은 "처음 선택한 이유"라기보다, 프로젝트를 진행하면서 MySQL이 요구사항을 충족한다는 것을 확인한 근거다.

## 왜 관계형 데이터베이스가 필요했는가

ImTicket의 핵심 데이터는 서로 독립적인 문서가 아니라 명확한 관계와 생명주기를 가진다.

```text
Performance
-> PerformanceTime
-> Seat
-> ReservedSeat
-> Reservation
-> Member
```

예매 선점에서는 다음 작업이 함께 성공하거나 함께 실패해야 한다.

1. 요청 좌석을 조회하고 잠근다.
2. 좌석이 예약 가능한지 확인한다.
3. 좌석 상태를 `LOCKED`로 변경한다.
4. `Reservation`과 `ReservedSeat`를 저장한다.

이 흐름에서는 외래 키 관계, 트랜잭션, 일관된 상태 변경이 중요하다. 따라서 NoSQL보다 관계형 DB가 자연스러운 선택이었다.

## MySQL 기능과 프로젝트 요구사항의 연결

| 프로젝트 요구사항 | MySQL에서 사용한 방식 |
| --- | --- |
| 동일 좌석 중복 선점 방지 | InnoDB transaction, `SELECT ... FOR UPDATE`에 대응하는 JPA `PESSIMISTIC_WRITE` |
| 예약과 좌석 상태의 원자적 변경 | Spring `@Transactional` + InnoDB |
| 관계형 데이터 모델링 | PK/FK 기반 `Member`, `Reservation`, `ReservedSeat`, `Seat` 관계 |
| 만료 예약 조회 최적화 | B+Tree 인덱스, `EXPLAIN ANALYZE` |
| 락 경합 분석 | `performance_schema.data_lock_waits`, `data_locks`, InnoDB status |
| 개발 환경 재현 | Docker Compose의 MySQL 8.0 |

MySQL을 선택한 핵심 이유를 "빠르기 때문"이라고 말하지 않는다. 실제 비교 벤치마크 없이 DB 제품 전체의 성능 우위를 주장할 수 없으며, 성능은 데이터 모델, 쿼리, 인덱스, 설정, 워크로드에 따라 달라진다.

## 다른 DB를 선택하지 않은 이유

### PostgreSQL

PostgreSQL도 트랜잭션, row-level lock, 복잡한 SQL을 지원하므로 ImTicket을 구현하는 데 충분하다. PostgreSQL을 선택하지 않은 이유를 기능 부족으로 설명하면 틀리다.

당시 프로젝트에는 PostgreSQL을 우선 선택하게 만들 다음 요구사항이 없었다.

- 복잡한 분석 SQL 중심 워크로드
- PostgreSQL 확장 기능 의존
- 고급 JSONB 연산 중심 모델
- PostgreSQL 운영 환경과의 조직 표준화

이미 익숙한 MySQL로 요구사항을 충족할 수 있었기 때문에 전환 비용을 감수하지 않았다. 다시 시작한다면 MySQL과 PostgreSQL 모두 후보가 될 수 있다.

### Microsoft SQL Server

SQL Server도 관계형 트랜잭션과 락을 지원하며 무료 Developer/Express 에디션도 있다. 따라서 "유료라서 제외했다"라고 단정하지 않는다.

이 프로젝트는 Java/Spring Boot와 Docker 중심의 소규모 팀 개발이었다. 당시 팀에는 SQL Server 운영 경험이나 Microsoft 데이터 플랫폼을 선택해야 할 요구사항이 없었다. 새로운 관리 도구와 제품별 운영 지식을 추가하는 것보다 기존 MySQL 환경을 유지하는 편이 비용이 낮았다.

SQL Server가 더 적합할 수 있는 조건:

- 조직이 Microsoft 기술 스택을 표준으로 사용한다.
- 기존 시스템이 SQL Server에 구축되어 있다.
- SQL Server 전용 운영 도구와 기능을 활용해야 한다.

### Oracle Database

Oracle Database도 강력한 트랜잭션 및 엔터프라이즈 기능을 제공하고 무료 개발용 제품이 있다. 하지만 ImTicket은 교육 목적의 소규모 서비스였으며 Oracle 고유 기능이나 상용 지원이 필요한 요구사항이 없었다.

Oracle이 더 적합할 수 있는 조건:

- 조직의 핵심 시스템과 운영 인력이 Oracle을 표준으로 사용한다.
- Oracle 전용 기능, 상용 지원, 기존 자산과의 통합이 필요하다.
- 높은 도입 및 운영 복잡도를 감수할 사업적 근거가 있다.

현재 프로젝트에는 이러한 조건이 없었으므로 Oracle 도입은 요구사항 대비 과한 선택이었다.

## 비용에 대한 정확한 표현

사용 가능한 답변:

```text
비용도 고려 요소였습니다. 별도 상용 라이선스 구매 없이 팀 개발 환경을 구성할 수 있다는 점이 MySQL의 장점이었습니다. 다만 PostgreSQL도 오픈소스이고 SQL Server와 Oracle에도 무료 개발 옵션이 있으므로, 비용만으로 결정한 것은 아닙니다. 팀 숙련도와 구축 난이도, 프로젝트 요구사항을 함께 고려했습니다.
```

피해야 할 답변:

```text
MySQL만 무료라서 선택했습니다.
Oracle과 SQL Server는 무조건 유료라 제외했습니다.
MySQL이 PostgreSQL보다 빠르기 때문에 선택했습니다.
MySQL이 예매 시스템에 가장 좋은 DB입니다.
```

## 면접 답변

### 20초 답변

```text
무료라는 점도 있었지만 그것만이 이유는 아닙니다. 예매 도메인은 좌석, 회차, 예약 사이의 관계와 트랜잭션 정합성이 중요했고, 팀이 Spring Data JPA와 함께 가장 빠르게 구성할 수 있는 관계형 DB가 MySQL이었습니다. InnoDB의 row lock으로 중복 선점을 방지하고 performance_schema로 lock wait를 분석할 수 있어 현재 요구사항에도 적합했습니다.
```

### 40초 답변

```text
초기에는 팀 숙련도와 개발 환경 구축 비용을 우선해 MySQL을 선택했습니다. ImTicket은 회원, 공연 회차, 좌석, 예약 사이의 관계가 명확하고 예매 생성과 좌석 상태 변경이 하나의 트랜잭션으로 처리돼야 해서 관계형 DB가 필요했습니다.

MySQL InnoDB는 트랜잭션과 row-level lock을 지원해 좌석 선점에 비관적 락을 적용할 수 있었고, 이후에는 performance_schema와 EXPLAIN ANALYZE로 lock wait와 인덱스 병목도 분석했습니다. PostgreSQL도 충분한 대안이었지만 PostgreSQL 고유 기능이 필요한 요구가 없었고, SQL Server나 Oracle로 전환할 운영상 근거도 없어서 익숙하고 요구사항을 충족하는 MySQL을 유지했습니다.
```

### 꼬리 질문: 다시 시작해도 MySQL을 선택하겠는가

```text
현재 요구사항이 동일하고 팀 구성도 같다면 MySQL을 선택할 수 있습니다. 다만 무조건 MySQL을 고르지는 않습니다. 복잡한 분석 SQL이나 PostgreSQL 확장이 중요하면 PostgreSQL을 검토하고, 회사의 표준 플랫폼이 SQL Server나 Oracle이라면 기존 운영 역량과 자산을 우선하겠습니다. DB 선택은 제품의 절대 우열보다 워크로드와 조직의 운영 조건에 맞춰야 한다고 생각합니다.
```

### 꼬리 질문: MySQL의 단점은 무엇이었는가

```text
비관적 락을 사용하면서 InnoDB의 lock 범위와 인덱스 조건을 정확히 이해해야 했습니다. 쿼리 조건과 인덱스에 따라 record lock뿐 아니라 gap 또는 next-key lock이 발생할 수 있고, 경합 요청이 DB connection을 점유할 수 있습니다. 그래서 단순히 락을 적용하는 데서 끝내지 않고 data_lock_waits와 HikariCP 지표를 함께 확인하는 방향으로 보완하고 있습니다.
```

## 답변 원칙

1. MySQL을 사전에 완벽하게 비교 선정했다고 과장하지 않는다.
2. "팀 숙련도와 빠른 구축"을 정직한 초기 이유로 말한다.
3. 관계형 모델과 트랜잭션 요구를 기술적 이유로 연결한다.
4. InnoDB lock과 성능 분석 경험은 개발 이후 확인한 적합성으로 설명한다.
5. PostgreSQL도 유효한 대안이었다고 인정한다.
6. SQL Server와 Oracle을 단순히 "유료라서 탈락"으로 설명하지 않는다.
7. 제품의 절대 성능 우위를 주장하지 않는다.

## 공식 참고 자료

- MySQL 8.0 InnoDB Locking: https://dev.mysql.com/doc/refman/8.0/en/innodb-locking.html
- PostgreSQL Explicit Locking: https://www.postgresql.org/docs/current/explicit-locking.html
- SQL Server Downloads and free editions: https://www.microsoft.com/en-us/sql-server/sql-server-downloads
- Oracle Database Free: https://www.oracle.com/database/free/
