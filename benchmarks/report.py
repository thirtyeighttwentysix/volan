"""Render measured JMH latency, refusing missing, duplicate or incompatible cases."""
import argparse
import html
import json
import math
from pathlib import Path

ORMS = ("Volan", "Hibernate", "Exposed", "jOOQ", "JDBC")
SCENARIOS = {
    "bench.OrmBenchmark.read": ("Read, one thread", "read-latency.svg", 1),
    "bench.ConcurrentReadBenchmark.read": ("Read, four threads sharing four connections", "concurrent-read-latency.svg", 4),
    "bench.WriteBenchmark.update": ("Update and commit", "update-latency.svg", 1),
    "bench.WriteBenchmark.insertDelete": ("Insert, delete and commit", "insert-delete-latency.svg", 1),
}


def validate(results):
    names = {r["benchmark"] for r in results}
    # Keep the original published ten-case report reproducible.
    expected_names = {"bench.OrmBenchmark.read"} if names == {"bench.OrmBenchmark.read"} else set(SCENARIOS)
    expected = {(name, orm, rows) for name in expected_names for orm in ORMS for rows in (1, 100)}
    cases = {}
    for result in results:
        key = (result["benchmark"], result["params"]["orm"], int(result["params"]["rows"]))
        if key in cases:
            raise ValueError(f"Duplicate case: {key}")
        if key[0] not in SCENARIOS:
            raise ValueError(f"Unknown workload: {key[0]}")
        if result["mode"] != "avgt" or result["threads"] != SCENARIOS[key[0]][2]:
            raise ValueError(f"Wrong mode or thread count: {key}")
        if result["forks"] < 2 or result["measurementIterations"] < 5 or result["warmupIterations"] < 3:
            raise ValueError("Smoke runs are not publishable: need two forks, three warmups and five measurements")
        metric = result["primaryMetric"]
        if metric["scoreUnit"] != "us/op" or not all(math.isfinite(metric[field]) for field in ("score", "scoreError")):
            raise ValueError(f"Expected finite latency and confidence interval in us/op: {key}")
        if metric["score"] <= 0 or metric["scoreError"] < 0:
            raise ValueError(f"Invalid latency: {key}")
        cases[key] = metric
    if set(cases) != expected:
        raise ValueError(f"Incomplete suite: expected {len(expected)} cases, got {len(cases)}")
    configurations = {
        (r["jvm"], tuple(r["jvmArgs"]), r["jdkVersion"], r["jmhVersion"], r["forks"],
         r["warmupIterations"], r["warmupTime"], r["measurementIterations"], r["measurementTime"])
        for r in results
    }
    if len(configurations) != 1:
        raise ValueError("Do not combine measurements from different JVM or JMH configurations")
    return cases


def chart(cases, workload):
    title, _, threads = SCENARIOS[workload]
    parts = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="1100" height="660" viewBox="0 0 1100 660" role="img" aria-labelledby="title desc">',
        f'<title id="title">PostgreSQL: {html.escape(title)}</title>',
        '<desc id="desc">Mean microseconds per operation with 99.9% confidence intervals. Lower is better. Separate axes for 1 and 100 rows. Negative lower bounds are clipped at zero.</desc>',
        '<rect width="1100" height="660" rx="16" fill="#101827"/>',
        '<g font-family="Segoe UI,Arial,sans-serif" fill="#edf2fa">',
        f'<text x="40" y="52" font-size="25" font-weight="700">PostgreSQL: {html.escape(title)}</text>',
        '<text x="40" y="81" font-size="15" fill="#a7b5ca">Mean latency; lower is better; error bars show 99.9% confidence intervals</text>',
    ]
    for panel, rows in enumerate((1, 100)):
        y0 = 124 + panel * 246
        parts.append(f'<text x="40" y="{y0}" font-size="19" font-weight="600">{rows} {"row" if rows == 1 else "rows"}</text>')
        ordered = sorted(ORMS, key=lambda orm: cases[workload, orm, rows]["score"])
        maximum = max(cases[workload, orm, rows]["score"] + cases[workload, orm, rows]["scoreError"] for orm in ORMS)
        scale = 690 / maximum
        for position, orm in enumerate(ordered):
            metric = cases[workload, orm, rows]
            y = y0 + 19 + position * 36
            width, error = metric["score"] * scale, metric["scoreError"] * scale
            color = "#8db8e8" if orm == "Volan" else "#7e98b7" if orm == "JDBC" else "#3c638a"
            lo, hi = 155 + max(0, width - error), 155 + width + error
            parts.extend([
                f'<text x="40" y="{y+20}" font-size="15">{html.escape(orm)}</text>',
                f'<rect x="155" y="{y}" width="{width:.2f}" height="27" rx="4" fill="{color}"/>',
                f'<path d="M {lo:.2f},{y+13} H {hi:.2f} M {lo:.2f},{y+8} v 10 M {hi:.2f},{y+8} v 10" stroke="#f4f7fb" stroke-width="1.5"/>',
                f'<text x="880" y="{y+20}" font-size="15">{metric["score"]:.1f} ± {metric["scoreError"]:.1f} µs</text>',
            ])
    parts.extend([
        f'<text x="40" y="623" fill="#a7b5ca" font-size="14">10,000 baseline rows; {threads} thread(s); four pooled connections; transaction per operation</text>',
        '</g></svg>',
    ])
    return "\n".join(parts)


def render(results, output, readme=None):
    cases = validate(results)
    sections = []
    output.mkdir(parents=True, exist_ok=True)
    for workload, (title, filename, _) in SCENARIOS.items():
        if (workload, "Volan", 1) not in cases:
            continue
        lines = [f"**{title}**", "", "| Library | 1 row, µs/op | 100 rows, µs/op |", "|---|---:|---:|"]
        for orm in ORMS:
            cells = [f'{cases[workload, orm, rows]["score"]:.1f} ± {cases[workload, orm, rows]["scoreError"]:.1f}' for rows in (1, 100)]
            label = f"**{orm}**" if orm == "Volan" else orm
            lines.append(f"| {label} | {' | '.join(cells)} |")
        sections.append("\n".join(lines))
        (output / filename).write_text(chart(cases, workload), encoding="utf-8")
    table = "\n\n".join(sections) + "\n"
    (output / "table.md").write_text(table, encoding="utf-8")
    if readme is not None:
        content = readme.read_text(encoding="utf-8")
        start, end = "<!-- BENCHMARKS:START -->", "<!-- BENCHMARKS:END -->"
        if content.count(start) != 1 or content.count(end) != 1:
            raise ValueError("README must contain exactly one benchmark marker pair")
        before, remainder = content.split(start, 1)
        _, after = remainder.split(end, 1)
        readme.write_text(before + start + "\n\n" + table + "\n" + end + after, encoding="utf-8")
    return table


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path)
    parser.add_argument("--output", type=Path, default=Path("docs/benchmarks"))
    parser.add_argument("--no-readme", action="store_true")
    args = parser.parse_args()
    results = json.loads(args.results.read_text(encoding="utf-8"))
    readme = None if args.no_readme else Path(__file__).resolve().parent.parent / "README.md"
    print(render(results, args.output, readme))


if __name__ == "__main__":
    main()
