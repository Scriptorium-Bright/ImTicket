#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

SECTION_NAMES = [
    "current-layout-json-plan",
    "candidate-layout-json-plan",
    "current-availability-json-plan",
    "candidate-availability-json-plan",
    "current-layout-analyze",
    "candidate-layout-analyze",
    "current-availability-analyze",
    "candidate-availability-analyze",
]

ACTUAL_RE = re.compile(r"actual time=([0-9.]+)\\.\\.([0-9.]+) rows=([0-9.]+) loops=([0-9.]+)")


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--input", required=True)
    p.add_argument("--output", required=True)
    return p.parse_args()


def section_blocks(lines):
    blocks = {}
    current = None
    for line in lines:
        stripped = line.strip()
        if stripped in SECTION_NAMES:
            current = stripped
            blocks[current] = []
            continue
        if current is not None:
            blocks[current].append(line.rstrip())
    return blocks


def first_json(block):
    for line in block:
        s = line.strip()
        if s.startswith("{") and s.endswith("}"):
            try:
                return json.loads(s)
            except json.JSONDecodeError:
                continue
    return None


def json_summary(plan):
    if not plan:
        return {"key": None, "rows_examined": None, "using_filesort": None}

    query = plan.get("query_block", {})
    ordering = query.get("ordering_operation")
    using_filesort = None
    node = query
    if ordering is not None:
        using_filesort = ordering.get("using_filesort")
        node = ordering

    table = None

    def walk(value):
        nonlocal table
        if table is not None:
            return
        if isinstance(value, dict):
            if "table" in value and isinstance(value["table"], dict):
                table = value["table"]
                return
            for child in value.values():
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)

    walk(node)
    table = table or {}
    return {
        "key": table.get("key"),
        "rows_examined": table.get("rows_examined_per_scan"),
        "using_filesort": using_filesort,
    }


def analyze_summary(block):
    joined = " ".join(line.strip() for line in block)
    matches = ACTUAL_RE.findall(joined)
    if not matches:
        return {"actual_end_ms": None, "rows": None}
    start, end, rows, loops = matches[0]
    return {
        "actual_end_ms": float(end),
        "rows": float(rows),
        "loops": float(loops),
    }


def main():
    args = parse_args()
    lines = Path(args.input).read_text(encoding="utf-8", errors="replace").splitlines()
    blocks = section_blocks(lines)

    rows = []
    for query in ("layout", "availability"):
        for mode in ("current", "candidate"):
            js = json_summary(first_json(blocks.get(f"{mode}-{query}-json-plan", [])))
            an = analyze_summary(blocks.get(f"{mode}-{query}-analyze", []))
            rows.append((query, mode, js, an))

    out = [
        "# Seat Index EXPLAIN Experiment",
        "",
        "현재 인덱스 (performance_time_id, seat_status)와 후보 인덱스 (performance_time_id)를",
        "동일한 60,000-seat query shape에서 비교한 자동 요약이다.",
        "",
        "| Query | Index shape | Optimizer key | Filesort | Rows/scan | EXPLAIN ANALYZE root end ms |",
        "|---|---|---|---|---:|---:|",
    ]
    for query, mode, js, an in rows:
        shape = "(performance_time_id, seat_status)" if mode == "current" else "(performance_time_id)"
        out.append(
            f"| {query} | {shape} | {js.get('key') or '-'} | "
            f"{js.get('using_filesort')} | {js.get('rows_examined') or '-'} | "
            f"{an.get('actual_end_ms') if an.get('actual_end_ms') is not None else '-'} |"
        )

    out += [
        "",
        "## 해석",
        "",
        "- 이 실험은 단일 query의 실행계획을 비교한다. Cache 도입 효과 자체를 측정하는 실험은 아니다.",
        "- 후보 인덱스가 filesort를 제거하거나 읽기 비용을 줄여도, 동시 사용자마다 같은 전체 좌석 집합을 반복 projection하는 구조는 남는다.",
        "- GitHub Actions의 절대 실행시간은 runner noise가 있으므로 포트폴리오에는 실행계획과 상대 경향을 우선 사용한다.",
        "- 실제 Cache OFF/ON 성능 수치는 동일 로컬 환경의 k6 결과를 사용한다.",
        "",
        "원본 계획은 같은 디렉터리의 raw.txt에 보관한다.",
    ]

    Path(args.output).write_text("\n".join(out) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
