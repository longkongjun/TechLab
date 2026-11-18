"""Summarize one or more raw TechLab Binder reports using nearest-rank percentiles."""
import json
import math
import sys
from collections import defaultdict
from pathlib import Path


def percentile(values, fraction):
    return sorted(values)[math.ceil(fraction * len(values)) - 1] / 1_000_000


def legacy_plan(sdk):
    routes = ["BINDER", "FILE", "PIPE"] + (["SHARED"] if sdk >= 27 else [])
    return {**dict.fromkeys(("bin32k", "bin1m", "bin8m", "Q002"), routes),
            "liveQ002": ["FILE", "PIPE"]}


def summarize(paths):
    groups = defaultdict(list)
    fixtures = {}
    table_order = {}
    implementation = None
    for path in paths:
        report = json.loads(Path(path).read_text())
        version = report.get("implementation", "original")
        if implementation is not None and version != implementation:
            raise ValueError(f"{path}: 请分别汇总不同实现的报告 ({implementation}, {version})")
        implementation = version
        plan = legacy_plan(report["sdk"]) if version == "original" else report["plan"]
        assert plan and report["rounds"] > 0, path
        for name, routes in plan.items():
            assert isinstance(routes, list) and routes and len(routes) == len(set(routes)), (path, name)
            for route in routes:
                table_order[name, route] = None
        assert report["clientPid"] != report["sourcePid"]
        checks = report["checks"]
        for route in ("FILE", "PIPE"):
            pages = [p for p in checks["pages"] if p["route"] == route]
            assert [p["id"] for p in pages] == ["Q001", "Q002", "Q003"]
            assert all(p["bytes"] > 1024 * 1024 for p in pages)
        assert checks["writersRemaining"] == 0 and checks["writerErrors"] == 2 * checks["faultRounds"]
        seen = set()
        for sample in report["samples"]:
            key = (sample["id"], sample["route"], sample["round"])
            assert key not in seen and 1 <= key[2] <= report["rounds"], (path, key)
            seen.add(key)
            assert 0 < sample["firstNs"] <= sample["totalNs"]
            identity = (sample["bytes"], sample["crc32"])
            assert fixtures.setdefault(key[0], identity) == identity
            groups[key[:2]].append(sample)
        expected = {(name, route, r) for name, routes in plan.items()
                    for route in routes for r in range(1, report["rounds"] + 1)}
        assert seen == expected, path
        assert fixtures["Q002"] == fixtures["liveQ002"]
    print("| 输入 | 路径 | n | 首块 P50 (ms) | 完整 P50 (ms) | 完整 P95 (ms) |")
    print("| --- | --- | ---: | ---: | ---: | ---: |")
    display_names = {"Q002": "预生成编码文件", "liveQ002": "按需编码"}
    for name, route in table_order:
        samples = groups[name, route]
        first = [s["firstNs"] for s in samples]
        total = [s["totalNs"] for s in samples]
        print(f"| {display_names.get(name, name)} | {route} | {len(total)} | {percentile(first,.5):.2f} | "
              f"{percentile(total,.5):.2f} | {percentile(total,.95):.2f} |")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("usage: python3 summarize.py REPORT.json [REPORT.json ...]")
    summarize(sys.argv[1:])
