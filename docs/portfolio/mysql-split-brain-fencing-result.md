# MySQL split-brain fencing and safe rejoin result

작성일: 2026-09-20  
기준 브랜치: `experiment/mysql-split-brain-fencing`  
GitHub Actions run: `35477231428`  
실행 환경: GitHub Actions `ubuntu-24.04`, Docker, MySQL 8.0

## 1. 검증 질문

primary process가 죽은 것이 아니라 네트워크에서만 고립된 상황에서는 old primary가 계속 write를 받을 수 있다. 이때 replica를 곧바로 promote하면 두 writable primary가 동시에 존재할 수 있다.

이번 시험은 다음 순서를 검증한다.

1. old primary를 data network에서 분리하되 process는 살아 있게 유지
2. 격리된 old primary가 실제로 local write를 받아들일 수 있는지 확인
3. replica promotion 전에 old primary를 `read_only=ON`, `super_read_only=ON`으로 fencing
4. fencing 이후 old primary write가 read-only 오류로 거절되는지 확인
5. replica를 writable primary로 promote
6. promote 이후에도 old primary write가 계속 차단되는지 확인
7. 네트워크 복구 후 old primary를 GTID replica로 다시 붙이고 데이터가 수렴하는지 확인

## 2. 실행 결과

| 항목 | 결과 |
|---|---:|
| workflow | success |
| replica → old primary data-plane probe | 실패(exit 1), 네트워크 단절 확인 |
| partition 직후 old primary `read_only` | `0` |
| partition 직후 old primary `super_read_only` | `0` |
| fencing 전 transaction write + rollback | 성공 |
| rollback 후 probe row | `0` |
| fencing 소요 | `83ms` |
| fencing 후 old primary `read_only` | `1` |
| fencing 후 old primary `super_read_only` | `1` |
| fencing 후 old primary write | 실패 |
| fencing 후 오류 | read-only 오류 확인 |
| fence 완료 후 promote 시작 | `true` |
| replica promotion | `85ms` |
| new primary `read_only` | `0` |
| new primary `super_read_only` | `0` |
| new primary write | 성공 1건 |
| promotion 후 old primary write | 실패 |
| promotion 후 old primary 오류 | read-only 오류 확인 |
| old primary safe rejoin | `391ms` |
| rejoin 후 old primary `read_only` | `1` |
| rejoin 후 old primary `super_read_only` | `1` |
| rejoin replication IO/SQL | `Yes / Yes` |
| GTID old ⊆ new | `1` |
| GTID new ⊆ old | `1` |
| 최종 old/new probe row count | `2 / 2` |
| 최종 판정 | `OK` |

## 3. 핵심 해석

### 3.1 network partition은 primary failure와 다르다

old primary container는 data network에서 분리된 뒤에도 process 자체는 살아 있었다.

직접 SQL connectivity probe는 실패해 replica에서 old primary로 접근할 수 없었지만, old primary 내부에서 수행한 transaction write는 fencing 전 정상적으로 실행됐다.

즉 다음 상태가 실제로 재현됐다.

```text
Replica / service network
        X
        |
Old Primary
process alive
read_only = 0
super_read_only = 0
locally writable
```

이 상태에서 replica를 먼저 promote하면 old primary에 접근 가능한 다른 client 또는 복구된 network path를 통해 두 writable primary가 생길 수 있다.

### 3.2 promotion보다 fencing 순서가 먼저다

이번 실행에서는 old primary에 먼저 다음 상태를 적용했다.

```sql
SET GLOBAL read_only = ON;
SET GLOBAL super_read_only = ON;
```

fencing에는 `83ms`가 걸렸다.

이후 application user의 write는 실제 read-only 오류로 실패했고 row도 생성되지 않았다. 그 다음에 replica를 promote했으며 promotion 자체는 `85ms`였다.

검증 순서는 다음이었다.

```text
network partition
  → old primary still writable
  → fence old primary
  → old write rejected
  → promote replica
  → new write accepted
  → old write still rejected
```

이번 실행에서는 promotion 이후 두 노드가 동시에 writable인 상태를 만들지 않았다.

### 3.3 old primary를 폐기하지 않고 replica로 재합류시켰다

network를 복구한 뒤 old primary의 read-only 상태를 유지한 채 새 primary를 source로 지정하고 GTID auto-position으로 replication을 시작했다.

```text
Promoted Primary
       |
       | GTID replication
       v
Old Primary
read_only = 1
super_read_only = 1
```

`PROMOTED_WRITE`가 old primary까지 적용되는 데 포함된 재합류 절차는 `391ms`였다.

재합류 후 양방향 `GTID_SUBSET` 결과가 모두 `1`이었고 old/new의 GTID set과 probe row count가 동일했다. partition 동안 old primary에 commit된 별도 transaction을 만들지 않았기 때문에 errant GTID 없이 안전하게 재합류할 수 있었다.

## 4. Replica_IO_Running 해석 주의

network disconnect 후 2초 시점의 `Replica_IO_Running`은 여전히 `Yes`였다.

따라서 이번 시험에서는 이 값만으로 network partition 여부를 판단하지 않았다.

별도의 replica → old primary SQL connectivity probe를 수행했고 해당 연결이 실패(exit 1)한 것을 data-plane 단절의 직접 증거로 사용했다.

즉 짧은 장애 구간에서 replication thread 상태 변수의 단일 snapshot만 보고 source connectivity를 단정하지 않는 것이 이번 실행에서 확인된 운영 포인트다.

## 5. 주장 범위와 한계

이번 결과는 다음 범위 안에서만 사용한다.

- GitHub Actions의 격리 Docker 환경에서 수행한 장애주입 시험이다.
- Docker control plane의 `docker network disconnect`와 `docker exec`를 fencing channel로 사용했다.
- 실제 클라우드 STONITH, orchestrator, MySQL Router, ProxySQL, consensus 기반 leader election을 구현한 결과가 아니다.
- fencing 권한 자체가 network partition과 함께 상실되는 상황은 검증하지 않았다.
- partition 동안 old primary에 committed errant transaction을 일부러 만들지 않았다. 해당 경우 자동 재합류가 안전한지는 별도 시험 대상이다.
- 이번 시험은 서비스 RTO 측정이 아니라 DB write ownership 안전성 검증이다.

## 6. 다음 실험

후속 가치가 큰 것은 두 가지다.

1. **errant GTID detection**
   - partition 중 old primary에 실제 commit 생성
   - 새 primary도 별도 commit 생성
   - reconnect 시 GTID divergence 탐지
   - 자동 replica rejoin을 거부하고 재구축 필요 상태로 분류

2. **application connection cutover**
   - 앞선 ambiguous commit 시험의 전체 RTO `34.77초` 중 application restart 비용 제거
   - MySQL Router/HAProxy/ProxySQL 또는 stable DB endpoint를 사용
   - application process 재기동 없이 connection recovery 시간 측정

## 7. 포트폴리오 표현 기준

사용 가능한 표현:

> 살아 있는 MySQL primary의 data network를 격리해 split-brain 위험을 재현하고, replica 승격 전에 old primary를 read-only로 fencing했다. 격리 상태에서 old primary가 실제 write를 수용할 수 있음을 확인한 뒤 fencing 후 write가 차단되는 것을 검증했으며, replica promotion 85ms 이후 old primary를 read-only 상태로 GTID replication에 재합류시켜 양 노드의 GTID와 데이터가 다시 수렴하는 것을 확인했다.

피해야 할 표현:

- 완전한 자동 failover를 구현했다.
- 모든 network partition에서 split-brain을 방지한다.
- production-grade STONITH를 구현했다.
- MySQL 자체가 자동 fencing을 제공한다.
