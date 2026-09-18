#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

SECTION_NAMES = {
    "current-layout-json-plan",
    "candidate-layout-json-plan",
    "current-availability-json-plan",
    "candidate-availability-json-plan",
    "current-layout-analyze",
    "candidate-layout-analyze",
    "current-availability-analyze",
    "candidate-availability-analyze",
}

ACTUAL_RE = re.compile(r"actual time=([0-9.]+)\.\.([0-9.]+) rows=([0-9.]+) loops=([0-9.]+)")


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--input", required=True)
    p.add_argument("--output", required=True)
    return p.parse_args()


def section_blocks(lines):
    blocks = {}
    current = None
    index = 0
    while index < len(lines):
        stripped = lines[index].strip()
        if stripped == "section" and index + 1 < len(lines):
            candidate = lines[index + 1].strip()
            if candidate in SECTION_NAMES:
                current = candidate
                blocks[current] = []
                index += 2
                continue
        if current is not None:
            blocks[current].append(lines[index])
        index += 1
    return blocks


def parse_json_block(block):
    try:
        start = next(i for i, line in enumerate(block) if line.lstrip().startswith("{"))
    except StopIteration:
        return None
    payload = "\n".join(block[start:]).strip()
    try:
        return json.loads(payload)
    except json.JSONDecodeError:
        return None


def json_summary(plan):
    if not plan:
        return {"key": None, "rows_examined": None, "using_filesort": None}

    query = plan.get("query_block", {})
    ordering = query.get("ordering_operation")
    using_filesort = ordering.get("using_filesort") if isinstance(ordering, dict) else None
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

    walk(ordering if ordering is not None else query)
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
        return {"actual_end_ms": None, "actual_rows": None}
    start, end, rows, loops = matches[0]
    return {
        "actual_end_ms": float(end),
        "actual_rows": float(rows),
        "loops": float(loops),
    }


def fmt(value):
    if value is None:
        return "-"
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value)


def pct(before, after):
    if before in (None, 0) or after is None:
        return None
    return (after - before) / before * 100.0


def main():
    args = parse_args()
    lines = Path(args.input).read_text(encoding="utf-8", errors="replace").splitlines()
    blocks = section_blocks(lines)

    results = {}
    for query in ("layout", "availability"):
        for mode in ("current", "candidate"):
            js = json_summary(parse_json_block(blocks.get(f"{mode}-{query}-json-plan", [])))
            an = analyze_summary(blocks.get(f"{mode}-{query}-analyze", []))
            results[(query, mode)] = (js, an)

    out = [
        "# Seat Index EXPLAIN Experiment",
        "",
        "MySQL 8.0 / InnoDB, 한 회차 60,000석의 실제 Seat read-model query shape를 사용했다.",
        "현재 인덱스와 query-aligned 후보 인덱스를 별도 mirror table에 동일 데이터로 구성해 비교했다.",
        "",
        "| Query | Index shape | Optimizer key | Filesort | Est. rows/scan | Actual rows | Root end ms |",
        "|---|---|---|---|---:|---:|---:|",
    ]
    for query in ("layout", "availability"):
        for mode in ("current", "candidate"):
            js, an = results[(query, mode)]
            shape = "(performance_time_id, seat_status)" if mode == "current" else "(performance_time_id)"
            out.append(
                f"| {query} | {shape} | {fmt(js.get('key'))} | "
                f"{fmt(js.get('using_filesort'))} | {fmt(js.get('rows_examined'))} | "
                f"{fmt(an.get('actual_rows'))} | {fmt(an.get('actual_end_ms'))} |"
            )

    current_layout = results[("layout", "current")][1].get("actual_end_ms")
    candidate_layout = results[("layout", "candidate")][1].get("actual_end_ms")
    current_availability = results[("availability", "current")][1].get("actual_end_ms")
    candidate_availability = results[("availability", "candidate")][1].get("actual_end_ms")

    out += [
        "",
        "## Relative observation",
        "",
        f"- layout root end: {fmt(current_layout)} ms -> {fmt(candidate_layout)} ms ({fmt(pct(current_layout, candidate_layout))}% change)",
        f"- availability root end: {fmt(current_availability)} ms -> {fmt(candidate_availability)} ms ({fmt(pct(current_availability, candidate_availability))}% change)",
        "",
        "## 해석",
        "",
        "- 현재 (performance_time_id, seat_status)는 performance_time_id lookup에는 사용되지만 ORDER BY id를 만족하지 못해 filesort가 발생했다.",
        "- 후보 (performance_time_id)는 InnoDB secondary index의 PK 포함 특성 때문에 같은 회차 안에서 id 순서를 유지해 filesort가 사라졌다.",
        "- 한 번의 CI 실행시간은 benchmark 확정값이 아니라 실행계획 차이를 보조하는 관측값으로만 사용한다.",
        "- 후보 인덱스가 단일 query를 개선해도 동시 사용자마다 같은 전체 좌석 집합을 반복 projection하는 구조는 남는다.",
        "- 실제 Cache OFF/ON 효과는 동일 애플리케이션 환경의 k6 결과로 판단한다.",
        "",
        "원본 계획은 같은 디렉터리의 raw.txt에 보관한다.",
    ]

    Path(args.output).write_text("\n".join(out) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
