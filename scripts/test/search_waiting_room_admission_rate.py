#!/usr/bin/env python3
"""Waiting Room admission rate의 parametric search 실행기.

최적화 문제를 다음 결정 문제로 바꾼다.

    P(rate) = protected API SLO와 자원 보호 조건을 모두 만족하는가?

P(rate)가 통과하는 가장 큰 정수 rate를 이진 탐색으로 찾는다. 기존 raw
result를 재사용할 수 있어, 이미 측정한 rate를 다시 부하하지 않고 탐색
알고리즘과 판정 기준을 재현할 수 있다.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import shutil
import subprocess
import sys
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any
from urllib.error import URLError
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[2]
LOAD_RUNNER = ROOT / "scripts/test/run_waiting_room_load.sh"
RESET_RUNNER = ROOT / "scripts/test/reset_waiting_room_fixture.sh"
COMPOSE_FILE = ROOT / "docker-compose.yml"


@dataclass
class SearchConfig:
    performance_time_id: int
    cohort_size: int
    max_active_sessions: int
    hikari_pool_size: int
    pre_reserve_p95_ms: float
    pre_reserve_p99_ms: float
    low_rate: int
    high_rate: int
    boundary_repeats: int
    existing_root: str | None
    execute_missing: bool


@dataclass
class Observation:
    rate: int
    label: str
    source: str
    result_dir: str
    passed: bool
    pre_reserve_p95_ms: float | None
    pre_reserve_p99_ms: float | None
    queue_wait_p95_ms: float | None
    journey_p95_ms: float | None
    hikari_pending_peak: float | None
    tomcat_busy_peak: float | None
    contract_success: float | None
    db_consistency: bool
    failure_reasons: list[str]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--low", type=int, default=10)
    parser.add_argument("--high", type=int, default=200)
    parser.add_argument("--max-high", type=int, default=2000)
    parser.add_argument("--boundary-repeats", type=int, default=3)
    parser.add_argument("--pt-id", type=int, default=900000001)
    parser.add_argument("--cohort-size", type=int, default=2000)
    parser.add_argument("--member-id-base", type=int, default=900000000)
    parser.add_argument("--max-active-sessions", type=int, default=100)
    parser.add_argument("--hikari-pool-size", type=int, default=30)
    parser.add_argument("--p95-limit-ms", type=float, default=2000)
    parser.add_argument("--p99-limit-ms", type=float, default=5000)
    parser.add_argument(
        "--existing-root",
        type=Path,
        default=ROOT / "build/k6-results",
        help="기존 k6 raw result를 찾을 루트. --no-reuse와 함께 끌 수 있다.",
    )
    parser.add_argument("--no-reuse", action="store_true")
    parser.add_argument(
        "--run-root",
        type=Path,
        default=ROOT / "build/k6-results/146.6.5-parametric-search",
    )
    parser.add_argument("--build-app", action="store_true")
    parser.add_argument("--execute-missing", action="store_true")
    parser.add_argument(
        "--single-rate",
        type=int,
        help="이진 탐색 없이 지정한 rate 한 건만 실행하고 판정한다.",
    )
    # rate=10, cohort=2,000에서는 queue wait가 약 200초까지 늘어날 수 있다.
    # 5회 polling은 약 10초 뒤 대부분의 VU를 timeout시키므로 전체 cohort 판정에 사용할 수 없다.
    parser.add_argument("--status-polls", type=int, default=180)
    parser.add_argument("--max-duration", default="8m")
    return parser.parse_args()


def load_dotenv(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for raw_line in path.read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[key] = value
    return values


def base_environment() -> dict[str, str]:
    env = load_dotenv(ROOT / ".env")
    env.update(os.environ)
    return env


def run_command(
    command: list[str],
    *,
    env: dict[str, str] | None = None,
    log_path: Path | None = None,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    if log_path:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with log_path.open("w") as log_file:
            return subprocess.run(
                command,
                cwd=ROOT,
                env=env,
                stdout=log_file,
                stderr=subprocess.STDOUT,
                text=True,
                check=check,
            )
    return subprocess.run(command, cwd=ROOT, env=env, text=True, check=check)


def json_metric(summary: dict[str, Any], name: str) -> dict[str, Any]:
    return summary.get("metrics", {}).get(name, {})


def metric_count(summary: dict[str, Any], name: str) -> float:
    metric = json_metric(summary, name)
    return float(metric.get("count", metric.get("value", 0)) or 0)


def metric_value(summary: dict[str, Any], name: str) -> float | None:
    metric = json_metric(summary, name)
    value = metric.get("value")
    return float(value) if isinstance(value, (int, float)) else None


def trend_percentile(summary: dict[str, Any], name: str, percentile: str) -> float | None:
    value = json_metric(summary, name).get(percentile)
    return float(value) if isinstance(value, (int, float)) else None


def metric_table_peak(result_dir: Path, column: str) -> float | None:
    path = result_dir / "app-metrics.tsv"
    if not path.exists():
        return None
    values: list[float] = []
    with path.open(newline="") as file:
        for row in csv.DictReader(file, delimiter="\t"):
            raw = row.get(column, "")
            try:
                values.append(float(raw))
            except (TypeError, ValueError):
                continue
    return max(values) if values else None


def parse_db_verification(text: str) -> bool:
    values = dict(re.findall(r"([a-z_]+)=([0-9]+)", text))
    return (
        values.get("fixture_total") == "2000"
        and values.get("fixture_locked") == "2000"
        and values.get("reservation_count") == "2000"
        and values.get("reserved_seat_count") == "2000"
    )


def evaluate_result(
    *,
    rate: int,
    label: str,
    result_dir: Path,
    source: str,
    cohort_size: int,
    p95_limit_ms: float,
    p99_limit_ms: float,
    db_consistency: bool,
) -> Observation:
    summary_path = result_dir / "k6-summary.json"
    if not summary_path.exists():
        raise RuntimeError(f"k6 summary가 없습니다: {summary_path}")
    summary = json.loads(summary_path.read_text())

    pre_p95 = trend_percentile(summary, "waiting_room_pre_reserve_duration", "p(95)")
    pre_p99 = trend_percentile(summary, "waiting_room_pre_reserve_duration", "p(99)")
    queue_p95 = trend_percentile(summary, "waiting_room_queue_wait_duration", "p(95)")
    journey_p95 = trend_percentile(summary, "waiting_room_total_journey_duration", "p(95)")
    contract = metric_value(summary, "waiting_room_contract_success")
    pending_peak = metric_table_peak(result_dir, "hikari_pending")
    tomcat_peak = metric_table_peak(result_dir, "tomcat_busy")

    reasons: list[str] = []
    if pre_p95 is None or pre_p95 > p95_limit_ms:
        reasons.append(f"pre-reserve p95 {pre_p95}ms > {p95_limit_ms}ms")
    if pre_p99 is None or pre_p99 > p99_limit_ms:
        reasons.append(f"pre-reserve p99 {pre_p99}ms > {p99_limit_ms}ms")
    if contract is None or contract < 1:
        reasons.append(f"contract success={contract}")
    for name, expected in (
        ("waiting_room_join_success", cohort_size),
        ("waiting_room_status_admitted", cohort_size),
        ("waiting_room_seat_map_success", cohort_size),
        ("waiting_room_pre_reserve_expected", cohort_size),
    ):
        actual = metric_count(summary, name)
        if actual != expected:
            reasons.append(f"{name}={actual}, expected={expected}")
    for name in (
        "waiting_room_join_unexpected",
        "waiting_room_status_unexpected",
        "waiting_room_unexpected_response",
        "waiting_room_admission_timeout",
    ):
        actual = metric_count(summary, name)
        if actual != 0:
            reasons.append(f"{name}={actual}")
    if pending_peak is None or pending_peak > 0:
        reasons.append(f"Hikari pending peak={pending_peak}")
    if not db_consistency:
        reasons.append("DB consistency failed")

    return Observation(
        rate=rate,
        label=label,
        source=source,
        result_dir=str(result_dir),
        passed=not reasons,
        pre_reserve_p95_ms=pre_p95,
        pre_reserve_p99_ms=pre_p99,
        queue_wait_p95_ms=queue_p95,
        journey_p95_ms=journey_p95,
        hikari_pending_peak=pending_peak,
        tomcat_busy_peak=tomcat_peak,
        contract_success=contract,
        db_consistency=db_consistency,
        failure_reasons=reasons,
    )


def existing_result_path(root: Path, rate: int, repeat: int) -> Path | None:
    if rate == 10 and repeat == 1:
        candidates = [
            root / "146.6.5-A1-full-flow-1-retry",
            root / "146.6.5-rate-10",
            root / "rate-10",
        ]
    elif repeat == 1:
        candidates = [root / f"146.6.5-rate-{rate}", root / f"rate-{rate}"]
    else:
        candidates = [
            root / f"146.6.5-rate-{rate}-rep{repeat}",
            root / f"rate-{rate}-rep{repeat}",
        ]
    return next((path for path in candidates if (path / "k6-summary.json").exists()), None)


def compose_environment(base: dict[str, str], args: argparse.Namespace, rate: int) -> dict[str, str]:
    env = dict(base)
    env.update(
        {
            "LOCK_STRATEGY": "reentrant",
            "LOCK_REENTRANT_WAIT_TIMEOUT_MILLIS": "1000",
            "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE": str(args.hikari_pool_size),
            "MANAGEMENT_SERVER_PORT": "10081",
            "MANAGEMENT_HOST_PORT": "10081",
            "MANAGEMENT_BIND_ADDRESS": "127.0.0.1",
            "RESERVATION_WAITING_ROOM_ENABLED": "true",
            "RESERVATION_WAITING_ROOM_MAX_ACTIVE_SESSIONS": str(args.max_active_sessions),
            "RESERVATION_WAITING_ROOM_ADMIT_PER_INTERVAL": str(rate),
            "RESERVATION_WAITING_ROOM_PROMOTION_INTERVAL": "1s",
            "RESERVATION_WAITING_ROOM_STATUS_POLL_AFTER": "2s",
            "RESERVATION_WAITING_ROOM_ENABLED_PERFORMANCE_TIME_IDS": str(args.pt_id),
            "RESERVATION_WAITING_ROOM_PASS_SECRET": env.get(
                "RESERVATION_WAITING_ROOM_PASS_SECRET",
                "146.6.5-parametric-search-waiting-room-pass-secret-0123456789",
            ),
        }
    )
    return env


def wait_for_health(url: str, timeout_seconds: int = 90) -> None:
    deadline = time.time() + timeout_seconds
    last_error = "unknown"
    while time.time() < deadline:
        try:
            with urlopen(url, timeout=3) as response:
                if response.status == 200:
                    return
        except (OSError, URLError) as error:
            last_error = str(error)
        time.sleep(1)
    raise RuntimeError(f"application health 대기 실패: {url}, last_error={last_error}")


def mysql_query(base: dict[str, str], sql: str) -> str:
    user = base.get("MYSQL_USER", "capstone")
    database = base.get("MYSQL_DATABASE", "capstone")
    password = base.get("MYSQL_PASSWORD", base.get("MYSQL_LOCK_TEST_PASSWORD", ""))
    use_docker = base.get("MYSQL_USE_DOCKER", "auto").lower()
    if not password:
        raise RuntimeError("MYSQL_PASSWORD 또는 MYSQL_LOCK_TEST_PASSWORD가 필요합니다.")

    local = [
        "mysql",
        "--host=" + base.get("MYSQL_HOST", "127.0.0.1"),
        "--port=" + base.get("MYSQL_PORT", "10047"),
        "--user=" + user,
        "--database=" + database,
        "--batch",
        "--skip-column-names",
        "-e",
        sql,
    ]
    docker = [
        "docker",
        "compose",
        "exec",
        "-T",
        "-e",
        "MYSQL_PWD=" + password,
        "mysql",
        "mysql",
        "--user=" + user,
        "--database=" + database,
        "--batch",
        "--skip-column-names",
        "-e",
        sql,
    ]
    if use_docker in {"true", "1", "yes"}:
        command = docker
        command_env = base
    elif use_docker in {"false", "0", "no"}:
        command = local
        command_env = dict(base, MYSQL_PWD=password)
    else:
        try:
            result = subprocess.run(
                docker,
                cwd=ROOT,
                env=base,
                text=True,
                capture_output=True,
                check=True,
            )
            return result.stdout.strip()
        except subprocess.CalledProcessError:
            command = local
            command_env = dict(base, MYSQL_PWD=password)
    result = subprocess.run(
        command,
        cwd=ROOT,
        env=command_env,
        text=True,
        capture_output=True,
        check=True,
    )
    return result.stdout.strip()


def query_seat_ids(base: dict[str, str], pt_id: int, expected: int) -> str:
    sql = (
        "SET SESSION group_concat_max_len = 200000; "
        "SELECT GROUP_CONCAT(id ORDER BY id SEPARATOR ',') "
        f"FROM Seat WHERE performance_time_id={pt_id} AND seat_status='AVAILABLE';"
    )
    result = mysql_query(base, sql)
    seat_ids = result.splitlines()[-1].strip() if result.splitlines() else ""
    if len(seat_ids.split(",")) != expected:
        raise RuntimeError(f"AVAILABLE seat 수가 {expected}가 아닙니다: {len(seat_ids.split(','))}")
    return seat_ids


def verify_db(base: dict[str, str], pt_id: int) -> bool:
    sql = f"""
SELECT CONCAT(
  'fixture_total=', (SELECT COUNT(*) FROM Seat WHERE performance_time_id={pt_id}),
  ',fixture_locked=', (SELECT COUNT(*) FROM Seat WHERE performance_time_id={pt_id} AND seat_status='LOCKED'),
  ',reservation_count=', (SELECT COUNT(DISTINCT rs.reservation_id) FROM ReservedSeat rs JOIN Seat s ON s.id=rs.seat_id WHERE s.performance_time_id={pt_id}),
  ',reserved_seat_count=', (SELECT COUNT(*) FROM ReservedSeat rs JOIN Seat s ON s.id=rs.seat_id WHERE s.performance_time_id={pt_id})
);"""
    return parse_db_verification(mysql_query(base, sql))


def reset_fixture(base: dict[str, str], args: argparse.Namespace, log_path: Path) -> None:
    env = dict(base)
    env.update(
        {
            "PT_ID": str(args.pt_id),
            "MEMBER_ID_START": str(args.member_id_base + 1),
            "MEMBER_COUNT": str(args.cohort_size),
        }
    )
    run_command([str(RESET_RUNNER)], env=env, log_path=log_path)


def configure_app(base: dict[str, str], args: argparse.Namespace, rate: int, run_dir: Path) -> None:
    env = compose_environment(base, args, rate)
    run_command(
        ["docker", "compose", "-f", str(COMPOSE_FILE), "up", "-d", "--force-recreate", "--no-deps", "app"],
        env=env,
        log_path=run_dir / "compose-up.log",
    )
    wait_for_health("http://127.0.0.1:10081/actuator/health")


def stop_app(base: dict[str, str], run_dir: Path) -> None:
    run_command(
        ["docker", "compose", "-f", str(COMPOSE_FILE), "stop", "app"],
        env=base,
        log_path=run_dir / "compose-stop.log",
    )


def build_app(base: dict[str, str], run_root: Path) -> None:
    run_command(
        ["docker", "compose", "-f", str(COMPOSE_FILE), "build", "app"],
        env=base,
        log_path=run_root / "compose-build.log",
    )


def execute_candidate(
    base: dict[str, str],
    args: argparse.Namespace,
    rate: int,
    repeat: int,
    run_root: Path,
    seat_ids: str | None,
) -> Observation:
    label = f"rate-{rate}" if repeat == 1 else f"rate-{rate}-rep{repeat}"
    result_dir = run_root / label
    result_dir.mkdir(parents=True, exist_ok=True)

    if not args.no_reuse:
        existing = existing_result_path(args.existing_root, rate, repeat)
        if existing:
            return evaluate_result(
                rate=rate,
                label=label,
                result_dir=existing,
                source="existing-raw-result",
                cohort_size=args.cohort_size,
                p95_limit_ms=args.p95_limit_ms,
                p99_limit_ms=args.p99_limit_ms,
                db_consistency=True,
            )

    if not args.execute_missing:
        raise RuntimeError(
            f"rate={rate}, repeat={repeat}의 raw result가 없습니다. "
            "--execute-missing을 지정하거나 기존 결과 재사용 범위를 확인하십시오."
        )

    # scheduler가 실행 중이면 reset 직후 Redis active ZSET을 다시 채울 수 있다.
    stop_app(base, result_dir)
    reset_fixture(base, args, result_dir / "fixture-reset.log")
    configure_app(base, args, rate, result_dir)
    current_seat_ids = seat_ids or query_seat_ids(base, args.pt_id, args.cohort_size)
    load_env = dict(base)
    load_env.update(
        {
            "BASE_URL": "http://127.0.0.1:10080",
            "MANAGEMENT_BASE_URL": "http://127.0.0.1:10081",
            "PT_ID": str(args.pt_id),
            "JWT_SECRET": base.get("JWT_SECRET", base.get("SPRING_JWT_SECRET", "")),
            "MODE": "full-flow",
            "FLOW": "waiting-room",
            "CONCURRENCY": str(args.cohort_size),
            "MEMBER_ID_BASE": str(args.member_id_base),
            "WALLET_ID_BASE": "0",
            "STATUS_POLLS": str(args.status_polls),
            "STATUS_POLL_INTERVAL_MS": "1000",
            "STATUS_POLL_JITTER_RATIO": "0.1",
            "SEAT_IDS": current_seat_ids,
            "REQUEST_TIMEOUT": "10s",
            "MAX_DURATION": args.max_duration,
            "RUN_NAME": f"146.6.5-parametric-search-{label}",
            "RUN_DIR": str(result_dir),
        }
    )
    if not load_env["JWT_SECRET"]:
        raise RuntimeError("JWT_SECRET 또는 SPRING_JWT_SECRET가 필요합니다.")
    run_command([str(LOAD_RUNNER)], env=load_env, log_path=result_dir / "runner.log")
    db_ok = verify_db(base, args.pt_id)
    (result_dir / "db-verification.txt").write_text(f"db_consistency={db_ok}\n")
    return evaluate_result(
        rate=rate,
        label=label,
        result_dir=result_dir,
        source="automated-parametric-search",
        cohort_size=args.cohort_size,
        p95_limit_ms=args.p95_limit_ms,
        p99_limit_ms=args.p99_limit_ms,
        db_consistency=db_ok,
    )


def write_outputs(
    run_root: Path,
    config: SearchConfig,
    observations: list[Observation],
    trace: list[dict[str, Any]],
    final_rate: int,
    adjacent_failure_rate: int,
    decision: str,
    boundary_stable: bool,
) -> None:
    run_root.mkdir(parents=True, exist_ok=True)
    payload = {
        "config": asdict(config),
        "optimization_problem": "max rate subject to the decision predicate P(rate)=true",
        "decision_predicate": {
            "pre_reserve_p95_ms": f"<= {config.pre_reserve_p95_ms}",
            "pre_reserve_p99_ms": f"<= {config.pre_reserve_p99_ms}",
            "contract_success": "== 1.0",
            "hikari_pending_peak": "== 0",
            "db_consistency": "true",
        },
        "trace": trace,
        "observations": [asdict(item) for item in observations],
        "decision": {
            "technical_admission_candidate": final_rate,
            "adjacent_failure_rate": adjacent_failure_rate,
            "boundary_repeats_stable": boundary_stable,
            "decision": decision,
            "reason": (
                "boundary candidate repeated runs all satisfied the predicate"
                if boundary_stable
                else "boundary candidate repeated runs were inconsistent"
            ),
        },
    }
    (run_root / "search-results.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")

    with (run_root / "search-results.tsv").open("w", newline="") as file:
        writer = csv.writer(file, delimiter="\t")
        writer.writerow(
            [
                "rate",
                "label",
                "source",
                "passed",
                "pre_reserve_p95_ms",
                "pre_reserve_p99_ms",
                "queue_wait_p95_ms",
                "hikari_pending_peak",
                "contract_success",
                "db_consistency",
                "failure_reasons",
            ]
        )
        for item in observations:
            writer.writerow(
                [
                    item.rate,
                    item.label,
                    item.source,
                    item.passed,
                    item.pre_reserve_p95_ms,
                    item.pre_reserve_p99_ms,
                    item.queue_wait_p95_ms,
                    item.hikari_pending_peak,
                    item.contract_success,
                    item.db_consistency,
                    "; ".join(item.failure_reasons),
                ]
            )

    lines = [
        "# Waiting Room Admission Rate Parametric Search Result",
        "",
        f"- technical admission candidate: **{final_rate}명/s**",
        f"- adjacent failing rate: **{adjacent_failure_rate}명/s**",
        f"- decision: **{decision}**",
        f"- boundary repeat stability: **{boundary_stable}**",
        "- search implementation: integer binary search over the decision predicate",
        "",
        "## Decision Predicate",
        "",
        f"`P(rate) = pre-reserve p95 ≤ {config.pre_reserve_p95_ms}ms and p99 ≤ {config.pre_reserve_p99_ms}ms and contract success = 100% and Hikari pending peak = 0 and DB consistency = true`",
        "",
        "## Search Trace",
        "",
        "| step | passed rate | failed rate | candidate | result |",
        "| --- | ---: | ---: | ---: | --- |",
    ]
    for step in trace:
        lines.append(
            f"| {step['step']} | {step['passed_rate']} | {step['failed_rate']} | {step['candidate']} | {step['result']} |"
        )
    lines.extend(
        [
            "",
            "## Evidence",
            "",
            "| rate | source | pre-reserve p95 | pre-reserve p99 | queue wait p95 | Hikari pending | decision |",
            "| ---: | --- | ---: | ---: | ---: | ---: | --- |",
        ]
    )
    for item in sorted(observations, key=lambda value: (value.rate, value.label)):
        decision = "pass" if item.passed else "fail: " + ", ".join(item.failure_reasons)
        lines.append(
            f"| {item.rate} | {item.source} | {item.pre_reserve_p95_ms}ms | {item.pre_reserve_p99_ms}ms | {item.queue_wait_p95_ms}ms | {item.hikari_pending_peak} | {decision} |"
        )
    (run_root / "decision-log.md").write_text("\n".join(lines) + "\n")


def write_single_rate_output(
    run_root: Path,
    config: SearchConfig,
    observation: Observation,
) -> None:
    run_root.mkdir(parents=True, exist_ok=True)
    payload = {
        "config": asdict(config),
        "measurement": {
            "type": "single-rate-validation",
            "rate": observation.rate,
            "frontend_poll_contract": "join response pollAfterMs is applied before the first status request",
        },
        "observation": asdict(observation),
    }
    (run_root / "single-rate-result.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    )
    verdict = "PASS" if observation.passed else "FAIL"
    lines = [
        "# Waiting Room Admission Rate Single-Case Result",
        "",
        f"- rate: **{observation.rate}명/s**",
        f"- result: **{verdict}**",
        "- measurement contract: join response의 `pollAfterMs`를 첫 status 요청 전에 적용",
        "",
        "## Evidence",
        "",
        f"- pre-reserve p95: {observation.pre_reserve_p95_ms}ms",
        f"- pre-reserve p99: {observation.pre_reserve_p99_ms}ms",
        f"- queue wait p95: {observation.queue_wait_p95_ms}ms",
        f"- journey p95: {observation.journey_p95_ms}ms",
        f"- Hikari pending peak: {observation.hikari_pending_peak}",
        f"- Tomcat busy peak: {observation.tomcat_busy_peak}",
        f"- contract success: {observation.contract_success}",
        f"- DB consistency: {observation.db_consistency}",
    ]
    if observation.failure_reasons:
        lines.extend(["", "## Failure Reasons", ""])
        lines.extend(f"- {reason}" for reason in observation.failure_reasons)
    (run_root / "decision-log.md").write_text("\n".join(lines) + "\n")


def main() -> int:
    args = parse_args()
    if args.single_rate is not None and args.single_rate <= 0:
        raise SystemExit("single-rate는 양의 정수여야 합니다.")
    if args.single_rate is None and (args.low <= 0 or args.high <= args.low):
        raise SystemExit("low < high인 양의 정수 구간이 필요합니다.")
    if args.boundary_repeats < 1:
        raise SystemExit("boundary-repeats는 1 이상이어야 합니다.")
    if args.cohort_size <= 0:
        raise SystemExit("cohort-size는 양의 정수여야 합니다.")

    args.run_root.mkdir(parents=True, exist_ok=True)
    base = base_environment()
    observations: list[Observation] = []
    observed_by_key: dict[tuple[int, int], Observation] = {}
    trace: list[dict[str, Any]] = []
    seat_ids: str | None = None

    if args.build_app and args.execute_missing:
        build_app(base, args.run_root)

    if args.single_rate is not None:
        observation = execute_candidate(
            base,
            args,
            args.single_rate,
            1,
            args.run_root,
            None,
        )
        config = SearchConfig(
            performance_time_id=args.pt_id,
            cohort_size=args.cohort_size,
            max_active_sessions=args.max_active_sessions,
            hikari_pool_size=args.hikari_pool_size,
            pre_reserve_p95_ms=args.p95_limit_ms,
            pre_reserve_p99_ms=args.p99_limit_ms,
            low_rate=args.single_rate,
            high_rate=args.single_rate,
            boundary_repeats=1,
            existing_root=str(args.existing_root) if not args.no_reuse else None,
            execute_missing=args.execute_missing,
        )
        write_single_rate_output(args.run_root, config, observation)
        print(
            f"rate={args.single_rate} result={'PASS' if observation.passed else 'FAIL'} "
            f"result_root={args.run_root}"
        )
        return 0

    def probe(rate: int, repeat: int = 1) -> Observation:
        key = (rate, repeat)
        if key in observed_by_key:
            return observed_by_key[key]
        item = execute_candidate(base, args, rate, repeat, args.run_root, seat_ids)
        observations.append(item)
        observed_by_key[key] = item
        print(
            f"rate={rate} repeat={repeat} result={'PASS' if item.passed else 'FAIL'} "
            f"source={item.source} hikari_pending={item.hikari_pending_peak} "
            f"pre_p95_ms={item.pre_reserve_p95_ms}",
            flush=True,
        )
        return item

    low = probe(args.low)
    if not low.passed:
        raise SystemExit(f"통과 하한 rate={args.low}가 실패했습니다: {low.failure_reasons}")

    high_rate = args.high
    high = probe(high_rate)
    while high.passed and high_rate < args.max_high:
        low = high
        high_rate = min(high_rate * 2, args.max_high)
        high = probe(high_rate)
    if high.passed:
        raise SystemExit(f"실패 상한을 찾지 못했습니다. max-high={args.max_high}")

    passed_rate = low.rate
    failed_rate = high.rate
    step = 0
    trace.append(
        {
            "step": step,
            "passed_rate": passed_rate,
            "failed_rate": failed_rate,
            "candidate": failed_rate,
            "result": "FAIL (upper bound)",
        }
    )
    while failed_rate - passed_rate > 1:
        step += 1
        candidate = (passed_rate + failed_rate) // 2
        item = probe(candidate)
        if item.passed:
            passed_rate = candidate
            result = "PASS"
        else:
            failed_rate = candidate
            result = "FAIL"
        trace.append(
            {
                "step": step,
                "passed_rate": passed_rate,
                "failed_rate": failed_rate,
                "candidate": candidate,
                "result": result,
            }
        )

    adjacent_failure_rate = passed_rate + 1
    adjacent = probe(adjacent_failure_rate)
    if adjacent.passed:
        raise SystemExit(
            f"인접 실패 rate 검증이 통과했습니다: {adjacent_failure_rate}. 결정 predicate의 단조 경계를 재검토해야 합니다."
        )
    for repeat in range(2, args.boundary_repeats + 1):
        probe(passed_rate, repeat)

    boundary_observations = [
        item for item in observations
        if item.rate == passed_rate and item.label.startswith(f"rate-{passed_rate}")
    ]
    boundary_stable = len(boundary_observations) >= args.boundary_repeats and all(
        item.passed for item in boundary_observations
    )
    decision = "adopt" if boundary_stable else "hold"

    config = SearchConfig(
        performance_time_id=args.pt_id,
        cohort_size=args.cohort_size,
        max_active_sessions=args.max_active_sessions,
        hikari_pool_size=args.hikari_pool_size,
        pre_reserve_p95_ms=args.p95_limit_ms,
        pre_reserve_p99_ms=args.p99_limit_ms,
        low_rate=args.low,
        high_rate=args.high,
        boundary_repeats=args.boundary_repeats,
        existing_root=str(args.existing_root) if not args.no_reuse else None,
        execute_missing=args.execute_missing,
    )
    write_outputs(
        args.run_root,
        config,
        observations,
        trace,
        passed_rate,
        adjacent_failure_rate,
        decision,
        boundary_stable,
    )
    print(f"technical_admission_candidate={passed_rate}")
    print(f"adjacent_failure_rate={adjacent_failure_rate}")
    print(f"decision={decision}")
    print(f"result_root={args.run_root}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
