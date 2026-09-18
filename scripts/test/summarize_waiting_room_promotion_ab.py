#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import math
import re
import statistics
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
        return {}
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
    return result


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
            "avg_ms": (total / count * 1000.0) if count > 0 else None,
            "max_ms": (found.get("max") * 1000.0) if found.get("max") is not None else None,
        }
    return result


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
        l = legacy_matrix.get(field, {}).get("median")
        p = pipeline_matrix.get(field, {}).get("median")
        rows.append(("closure_median", field, l, p, pct_change(l, p)))
    for phase in PHASES:
        for metric in ("avg_ms", "max_ms", "count"):
            l = legacy_phases.get(phase, {}).get(metric)
            p = pipeline_phases.get(phase, {}).get(metric)
            rows.append(("phase", f"{phase}.{metric}", l, p, pct_change(l, p)))

    with path.open("w", encoding="utf-8", newline="") as fp:
        writer = csv.writer(fp, delimiter="\t")
        writer.writerow(["section", "metric", "legacy", "pipeline", "change_percent"])
        for section, metric, legacy, pipeline, change in rows:
            writer.writerow([
                section,
                metric,
                "" if legacy is None else legacy,
                "" if pipeline is None else pipeline,
                "" if change is None else change,
            ])


def write_markdown(path: Path, legacy_matrix, pipeline_matrix, legacy_phases, pipeline_phases, legacy_dir, pipeline_dir):
    lines = [
        "# Waiting Room Promotion Metadata A/B Results",
        "",
        "동일한 2,000 users × 3 runs 종료 실험에서 후보 metadata 조회 방식만 변경한다.",
        "",
        f"- legacy evidence: {legacy_dir}",
        f"- pipeline evidence: {pipeline_dir}",
        "",
        "## End-to-end 결과 (3회 median)",
        "",
        "| Metric | Legacy | Pipeline | Change |",
        "|---|---:|---:|---:|",
    ]
    for field in MATRIX_FIELDS:
        l = legacy_matrix.get(field, {}).get("median")
        p = pipeline_matrix.get(field, {}).get("median")
        ch = pct_change(l, p)
        lines.append(f"| {field} | {fmt(l)} | {fmt(p)} | {fmt(ch, 2)}% |")

    lines += [
        "",
        "## Promotion phase timer",
        "",
        "| Phase | Legacy avg ms | Pipeline avg ms | Legacy max ms | Pipeline max ms |",
        "|---|---:|---:|---:|---:|",
    ]
    for phase in PHASES:
        l = legacy_phases.get(phase, {})
        p = pipeline_phases.get(phase, {})
        lines.append(
            f"| {phase} | {fmt(l.get('avg_ms'))} | {fmt(p.get('avg_ms'))} | "
            f"{fmt(l.get('max_ms'))} | {fmt(p.get('max_ms'))} |"
        )

    lines += [
        "",
        "## 해석 규칙",
        "",
        "- candidate_metadata가 legacy에서 큰 비중을 차지하고 pipeline에서 유의하게 감소하면 N×Redis round trip 가설을 지지한다.",
        "- phase 시간이 줄어도 end-to-end admission rate/queue wait이 변하지 않으면 bottleneck은 다른 구간으로 이동했거나 scheduler 외부에 있을 수 있다.",
        "- pipeline이 phase 시간과 end-to-end 양쪽에서 개선되지 않으면 최적화 근거가 약하므로 merge하지 않는다.",
        "- 이 문서는 실험 결과를 자동 정리할 뿐, 통계적 유의성을 주장하지 않는다. 3회 원시 결과를 함께 보관한다.",
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")


def main():
    args = parse_args()
    legacy_dir = Path(args.legacy_dir)
    pipeline_dir = Path(args.pipeline_dir)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    legacy_matrix = read_matrix(legacy_dir)
    pipeline_matrix = read_matrix(pipeline_dir)
    legacy_prom = parse_prometheus(legacy_dir)
    pipeline_prom = parse_prometheus(pipeline_dir)
    legacy_phases = phase_metrics(legacy_prom, "legacy")
    pipeline_phases = phase_metrics(pipeline_prom, "pipeline")

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
