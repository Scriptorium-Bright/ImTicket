# Waiting Room 패키지

작성일: 2026-09-04

146.1부터 147까지의 Waiting Room 실행·결정 문서를 단계와 주제별로 모은다.

전체 시스템 흐름은 [현재 티켓팅 아키텍처 전체 흐름](../../architecture/current-ticketing-architecture-flow.md)에서 확인한다.

| 범위 | 문서 주제 |
| --- | --- |
| 146.1~146.3 | contract, Redis admission, API/pass |
| 146.4~146.5 | admission hardening, protected zone |
| 146.6 | 부하 기준선, admission cohort/rate, status polling |
| 146.6.6 | Waiting Room SSE notification |
| 146.7~146.8 | entry preflight, ingress rate limit |
| 146.9 | 병목 재정의와 해결책 재검토 |
| 146.10 | 동기 join·Ticket SSE 완료 매뉴얼 |
| 146.11~146.14 | 통합 판단, ADR, 의사결정 로그, 전체 흐름 |
| 146.15 | C 이후 구현·검증·운영값 결정 순서 |
| 146.16 | Java Queue·MySQL·Redis·Kafka Waiting Room 대안 의사결정 |
| 146.18 | 단일 서버 메모리 대기열 대안, 저장소·embedded Tomcat API 비교 |
| 147 연계 | 입장 처리 용량, 서비스 수준 지표(SLI), 사용자 경험 설계 |

처음 읽을 때는 [잠금부터 Waiting Room·좌석 캐시까지 끝내는 안내](../../portfolio/lock-to-waiting-room-reading-guide.md)에서 문제와 판단 전환을 확인한다. 현재 수치의 시작점은 [성능 개선 요약과 종료 기준](../../portfolio/waiting-room-performance-improvement-summary.md)이다. 상세 시험 절차는 [146.10 완료 매뉴얼](146.10-waiting-room-sync-join-sse-completion-manual.md), 상태별 실행 순서는 [146.15 완료 순서](146.15-waiting-room-post-contention-completion-order.md), 저장소 선택 근거는 [146.16 대안 의사결정](146.16-waiting-room-queue-alternatives-decision.md), 단일 서버 메모리 대안과 저장소·API 비교는 [146.18 의사결정](146.18-single-server-memory-queue-decision.md)에 보존한다. C 동일 좌석 경합 정합성 결과는 [2026-08-24 C 결과](../../test/2026-08-24-waiting-room-c-same-seat-contention.md)에 기록했다. Ticket SSE의 Redis Pub/Sub은 입장 상태 알림을 담당한다. 좌석 현황의 내구성 메시지 전달은 [도입 판정 결과](../seat-availability/148.2-message-bus-adoption-gate-test-result.md)에 따라 보류했다.

2026-08-24에는 활성 세션 30과 2,000명 전체 흐름이 당시 종료 조건을 통과했다. 좌석 캐시 변경 이후 최신 통합 재검증에서는 2,000 VU가 용량 기준을 충족하지 못했고 1,500 VU가 업무 흐름을 통과했다. 활성 세션 30은 Reservation Application 보호값으로 유지하며, 전체 대기 인원의 최종 운영선은 승급 스케줄러와 별도 부하 발생기 검증 뒤 확정한다. 상세한 시간순 해석은 안내 문서 6.4절을 따른다.
