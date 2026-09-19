#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import math
import re
import statistics
import sys
from pathlib import Path

PHASES = [
    "expiry_total",
    "expiry_waiting_lookup",
    "expiry_active_lookup",
    "expiry_transition",
    "candidate_lookup",
    "candidate_metadata",
    "batch_transition",
]

EXPECTED_RUNS = 3

MATRIX_FIELDS = [
    "waiting_admission_rate",
    "waiting_scheduler_duration_max_ms",
    "queue_wait_p95_ms",
    "join_p95_ms",
    "tomcat_busy_max",
    "hikari_pending_max",
]


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--legacy-dir", required=True)
    parser.add_argument("--pipeline-dir", required=True)
    parser.add_argument("--output-dir", required=True)
    return parser.parse_args()


def read_matrix(group_dir: Path):
    path = group_dir / "closure-matrix.tsv"
    if not path.exists():
        return [], {}
    with path.open(encoding="utf-8") as fp:
        rows = list(csv.DictReader(fp, delimiter="\t"))
    result = {}
    for field in MATRIX_FIELDS:
        values = []
        for row in rows:
            try:
                value = float(row[field])
                if math.isfinite(value):
                    values.append(value)
            except (KeyError, TypeError, ValueError):
                pass
        if values:
            result[field] = {
                "values": values,
                "median": statistics.median(values),
                "mean": statistics.fmean(values),
                "min": min(values),
                "max": max(values),
            }
    return rows, result


LABEL_RE = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:\\\\.|[^"])*)"')


def parse_prometheus(group_dir: Path):
    path = group_dir / "waiting-room-prometheus-final.txt"
    if not path.exists():
        return {}
    metrics = {}
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not raw or raw.startswith("#"):
            continue
        try:
            left, value_text = raw.rsplit(" ", 1)
            value = float(value_text)
        except ValueError:
            continue
        if "{" in left:
            name, labels_text = left.split("{", 1)
            labels_text = labels_text.rstrip("}")
            labels = dict(LABEL_RE.findall(labels_text))
        else:
            name = left
            labels = {}
        metrics[(name, tuple(sorted(labels.items())))] = value
    return metrics


def phase_metrics(metrics, mode: str):
    result = {}
    base = "imticket_waiting_room_promotion_phase_duration_seconds"
    for phase in PHASES:
        found = {}
        for (name, labels_tuple), value in metrics.items():
            labels = dict(labels_tuple)
            if labels.get("phase") != phase or labels.get("metadata_mode") != mode:
                continue
            if name == base + "_count":
                found["count"] = value
            elif name == base + "_sum":
                found["sum"] = value
            elif name == base + "_max":
                found["max"] = value
        count = found.get("count", 0.0)
        total = found.get("sum", 0.0)
        result[phase] = {
            "count": count,
            "sum_seconds": total,
            "avg_ms": (total / count * 1000.0) if count > 0 else None,
            "max_ms": (found.get("max") * 1000.0) if found.get("max") is not None else None,
        }
    return result



def read_phase_runs(group_dir: Path, mode: str):
    run_dirs = sorted(
        path for path in group_dir.iterdir()
        if path.is_dir() and path.name.startswith("d-final-performance-closure-")
    ) if group_dir.exists() else []

    runs = []
    for run_dir in run_dirs:
        before_path = run_dir / "waiting-room-prometheus-before.txt"
        after_path = run_dir / "waiting-room-prometheus-after.txt"
        if not before_path.exists() or not after_path.exists():
            continue

        before = phase_metrics(parse_prometheus_file(before_path), mode)
        after = phase_metrics(parse_prometheus_file(after_path), mode)
        phases = {}
        for phase in PHASES:
            count_delta = after[phase]["count"] - before[phase]["count"]
            sum_delta = after[phase]["sum_seconds"] - before[phase]["sum_seconds"]
            phases[phase] = {
                "count": count_delta,
                "avg_ms": (sum_delta / count_delta * 1000.0) if count_delta > 0 and sum_delta >= 0 else None,
            }
        runs.append({"run_dir": run_dir, "phases": phases})
    return runs


def parse_prometheus_file(path: Path):
    metrics = {}
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not raw or raw.startswith("#"):
            continue
        try:
            left, value_text = raw.rsplit(" ", 1)
            value = float(value_text)
        except ValueError:
            continue
        if "{" in left:
            name, labels_text = left.split("{", 1)
            labels_text = labels_text.rstrip("}")
            labels = dict(LABEL_RE.findall(labels_text))
        else:
            name = left
            labels = {}
        metrics[(name, tuple(sorted(labels.items())))] = value
    return metrics


def aggregate_phase_runs(runs):
    result = {}
    for phase in PHASES:
        values = [
            run["phases"][phase]["avg_ms"]
            for run in runs
            if run["phases"][phase]["avg_ms"] is not None
        ]
        counts = [
            run["phases"][phase]["count"]
            for run in runs
            if run["phases"][phase]["count"] > 0
        ]
        result[phase] = {
            "values": values,
            "median": statistics.median(values) if values else None,
            "mean": statistics.fmean(values) if values else None,
            "count_values": counts,
        }
    return result


def completeness_issues(label, rows, matrix, phase_runs, phase_summary):
    issues = []
    if len(rows) != EXPECTED_RUNS:
        issues.append(f"{label}: closure rows={len(rows)} expected={EXPECTED_RUNS}")

    run_indexes = sorted(row.get("run_index") for row in rows)
    if run_indexes != ["1", "2", "3"]:
        issues.append(f"{label}: run_index={run_indexes} expected=['1', '2', '3']")

    for field in MATRIX_FIELDS:
        value_count = len(matrix.get(field, {}).get("values", []))
        if value_count != EXPECTED_RUNS:
            issues.append(f"{label}: {field} values={value_count} expected={EXPECTED_RUNS}")

    if len(phase_runs) != EXPECTED_RUNS:
        issues.append(f"{label}: phase snapshot runs={len(phase_runs)} expected={EXPECTED_RUNS}")

    for phase in PHASES:
        value_count = len(phase_summary.get(phase, {}).get("values", []))
        if value_count != EXPECTED_RUNS:
            issues.append(f"{label}: phase {phase} avg values={value_count} expected={EXPECTED_RUNS}")
    return issues


def write_incomplete(path: Path, issues, legacy_dir: Path, pipeline_dir: Path):
    lines = [
        "# Waiting Room Promotion Metadata A/B Results",
        "",
        "## EXPERIMENT INCOMPLETE",
        "",
        "정확히 2,000 users × 3 runs가 legacy/pipeline 양쪽에서 모두 완료되지 않아 변화율을 계산하지 않는다.",
        "",
        f"- legacy evidence: {legacy_dir}",
        f"- pipeline evidence: {pipeline_dir}",
        "",
        "### Missing or invalid evidence",
        "",
    ]
    lines.extend(f"- {issue}" for issue in issues)
    lines += [
        "",
        "fixture reset 또는 부하 실행 문제를 해결한 뒤 전체 A/B를 다시 실행한다.",
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")

def fmt(value, digits=3):
    if value is None:
        return "-"
    return f"{value:.{digits}f}"


def pct_change(before, after):
    if before in (None, 0) or after is None:
        return None
    return (after - before) / before * 100.0


def write_tsv(path: Path, legacy_matrix, pipeline_matrix, legacy_phases, pipeline_phases):
    rows = []
    for field in MATRIX_FIELDS:
        legacy = legacy_matrix[field]["median"]
        pipeline = pipeline_matrix[field]["median"]
        rows.append(("closure_median", field, legacy, pipeline, pct_change(legacy, pipeline)))

    for phase in PHASES:
        legacy = legacy_phases[phase]["median"]
        pipeline = pipeline_phases[phase]["median"]
        rows.append(("phase_run_delta_median", f"{phase}.avg_ms", legacy, pipeline, pct_change(legacy, pipeline)))

    with path.open("w", encoding="utf-8", newline="") as fp:
        writer = csv.writer(fp, delimiter="\t")
        writer.writerow(["section", "metric", "legacy", "pipeline", "change_percent"])
        for section, metric, legacy, pipeline, change in rows:
            writer.writerow([
                section,
                metric,
                legacy,
                pipeline,
                "" if change is None else change,
            ])


def format_run_values(values):
    return " / ".join(fmt(value) for value in values)


def write_markdown(path: Path, legacy_matrix, pipeline_matrix, legacy_phases, pipeline_phases, legacy_dir, pipeline_dir):
    lines = [
        "# Waiting Room Promotion Metadata A/B Results",
        "",
        "legacy/pipeline 모두 동일한 2,000 users × 3 runs가 완료된 경우에만 비교한다.",
        "",
        f"- legacy evidence: {legacy_dir}",
        f"- pipeline evidence: {pipeline_dir}",
        "- completeness: legacy 3/3, pipeline 3/3",
        "",
        "## End-to-end 결과 (3-run median)",
        "",
        "| Metric | Legacy | Pipeline | Change |",
        "|---|---:|---:|---:|",
    ]
    for field in MATRIX_FIELDS:
        legacy = legacy_matrix[field]["median"]
        pipeline = pipeline_matrix[field]["median"]
        change = pct_change(legacy, pipeline)
        lines.append(f"| {field} | {fmt(legacy)} | {fmt(pipeline)} | {fmt(change, 2)}% |")

    lines += [
        "",
        "## Promotion phase timer",
        "",
        "각 run 시작/종료 Prometheus snapshot의 count/sum delta로 run별 평균을 계산한 뒤 3회 median을 비교한다.",
        "Timer max는 time-window reset 영향을 받을 수 있어 A/B 판단값에서 제외한다.",
        "",
        "| Phase | Legacy median avg ms | Pipeline median avg ms | Change |",
        "|---|---:|---:|---:|",
    ]
    for phase in PHASES:
        legacy = legacy_phases[phase]["median"]
        pipeline = pipeline_phases[phase]["median"]
        lines.append(
            f"| {phase} | {fmt(legacy)} | {fmt(pipeline)} | {fmt(pct_change(legacy, pipeline), 2)}% |"
        )

    lines += [
        "",
        "## Promotion phase run별 값",
        "",
        "| Phase | Legacy r1 / r2 / r3 ms | Pipeline r1 / r2 / r3 ms |",
        "|---|---:|---:|",
    ]
    for phase in PHASES:
        lines.append(
            f"| {phase} | {format_run_values(legacy_phases[phase]['values'])} | "
            f"{format_run_values(pipeline_phases[phase]['values'])} |"
        )

    lines += [
        "",
        "## 해석 규칙",
        "",
        "- candidate_metadata가 줄어도 scheduler/admission/queue 지표가 같이 개선되지 않으면 전체 bottleneck 개선으로 해석하지 않는다.",
        "- pipeline이 phase와 end-to-end 양쪽에서 개선되지 않으면 merge 근거가 약하다.",
        "- waiting_admission_rate는 전체 collector 구간이 아니라 promotion counter가 실제 증가한 첫 구간부터 마지막 증가 구간까지의 active window로 계산한다.",
        "- 이 문서는 반복 측정 결과를 정리하며 통계적 유의성을 주장하지 않는다.",
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")


def main():
    args = parse_args()
    legacy_dir = Path(args.legacy_dir)
    pipeline_dir = Path(args.pipeline_dir)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    legacy_rows, legacy_matrix = read_matrix(legacy_dir)
    pipeline_rows, pipeline_matrix = read_matrix(pipeline_dir)

    legacy_phase_runs = read_phase_runs(legacy_dir, "legacy")
    pipeline_phase_runs = read_phase_runs(pipeline_dir, "pipeline")
    legacy_phases = aggregate_phase_runs(legacy_phase_runs)
    pipeline_phases = aggregate_phase_runs(pipeline_phase_runs)

    issues = []
    issues.extend(completeness_issues("legacy", legacy_rows, legacy_matrix, legacy_phase_runs, legacy_phases))
    issues.extend(completeness_issues("pipeline", pipeline_rows, pipeline_matrix, pipeline_phase_runs, pipeline_phases))
    if issues:
        write_incomplete(output_dir / "RESULTS.md", issues, legacy_dir, pipeline_dir)
        for issue in issues:
            print(f"EXPERIMENT INCOMPLETE: {issue}", file=sys.stderr)
        sys.exit(2)

    write_tsv(
        output_dir / "promotion-metadata-ab.tsv",
        legacy_matrix,
        pipeline_matrix,
        legacy_phases,
        pipeline_phases,
    )
    write_markdown(
        output_dir / "RESULTS.md",
        legacy_matrix,
        pipeline_matrix,
        legacy_phases,
        pipeline_phases,
        legacy_dir,
        pipeline_dir,
    )


if __name__ == "__main__":
    main()
