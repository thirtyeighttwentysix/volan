"""Generate the README benchmark table and chart from real JMH JSON (standard library only)."""
import argparse
import html
import json
import math
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path)
    parser.add_argument("--output", type=Path, default=Path("docs/benchmarks"))
    args = parser.parse_args()
    results = json.loads(args.results.read_text(encoding="utf-8"))
    cases = {(r["params"]["orm"], int(r["params"]["rows"])): r["primaryMetric"] for r in results}
    orms = ["Volan", "Hibernate", "Exposed", "jOOQ", "JDBC"]
    expected = {(orm, rows) for orm in orms for rows in (1, 100)}
    if set(cases) != expected or len(results) != len(expected):
        raise ValueError("Expected exactly one result for each of the ten benchmark cases")
    for metric in cases.values():
        if metric["scoreUnit"] != "us/op" or not all(math.isfinite(metric[key]) for key in ("score", "scoreError")):
            raise ValueError("Expected finite average latency and confidence interval in us/op")
    args.output.mkdir(parents=True, exist_ok=True)
    lines = ["| Library | 1 row, µs/op | 100 rows, µs/op |", "|---|---:|---:|"]
    for orm in orms:
        cells = [f'{cases[orm, n]["score"]:.1f} ± {cases[orm, n]["scoreError"]:.1f}' for n in (1, 100)]
        label = f"**{orm}**" if orm == "Volan" else orm
        lines.append(f"| {label} | {' | '.join(cells)} |")
    (args.output / "table.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    readme = Path(__file__).resolve().parent.parent / "README.md"
    content = readme.read_text(encoding="utf-8")
    start, end = "<!-- BENCHMARKS:START -->", "<!-- BENCHMARKS:END -->"
    if start in content and end in content:
        before, remainder = content.split(start, 1)
        _, after = remainder.split(end, 1)
        readme.write_text(before + start + "\n\n" + "\n".join(lines) + "\n\n" + end + after, encoding="utf-8")
    parts = ['<svg xmlns="http://www.w3.org/2000/svg" width="1100" height="660" viewBox="0 0 1100 660" role="img" aria-labelledby="title desc">',
             '<title id="title">PostgreSQL read latency by library</title>',
             '<desc id="desc">Mean microseconds per operation with 99.9% confidence intervals. Lower is better. Separate axes for 1 and 100 rows.</desc>',
             '<rect width="1100" height="660" rx="24" fill="#101827"/>',
             '<g font-family="Segoe UI,Arial,sans-serif" fill="#edf2fa">',
             '<text x="40" y="52" font-size="27" font-weight="700">PostgreSQL · end-to-end reads</text>',
             '<text x="40" y="81" font-size="15" fill="#a7b5ca">Mean latency · lower is better · error bars show 99.9% confidence intervals</text>']
    for panel, n in enumerate((1, 100)):
        y0 = 124 + panel * 246
        parts.append(f'<text x="40" y="{y0}" font-size="19" font-weight="600">{n} {"row" if n == 1 else "rows"}</text>')
        ordered = sorted(orms, key=lambda orm: cases[orm, n]["score"])
        maximum = max(cases[orm, n]["score"] + cases[orm, n]["scoreError"] for orm in orms)
        scale = 690 / maximum
        for position, orm in enumerate(ordered):
            metric = cases[orm, n]
            y = y0 + 19 + position * 36
            width = metric["score"] * scale
            error = metric["scoreError"] * scale
            color = "#9c8cff" if orm == "Volan" else "#7e98b7" if orm == "JDBC" else "#3c638a"
            lo, hi = 155 + max(0, width - error), 155 + width + error
            parts.extend([f'<text x="40" y="{y+20}" font-size="15">{html.escape(orm)}</text>',
                          f'<rect x="155" y="{y}" width="{width:.2f}" height="27" rx="5" fill="{color}"/>',
                          f'<path d="M {lo:.2f},{y+13} H {hi:.2f} M {lo:.2f},{y+8} v 10 M {hi:.2f},{y+8} v 10" stroke="#f4f7fb" stroke-width="1.5"/>',
                          f'<text x="880" y="{y+20}" font-size="15">{metric["score"]:.1f} ± {metric["scoreError"]:.1f} µs</text>'])
    parts.extend(['<text x="40" y="623" fill="#a7b5ca" font-size="14">10,000 rows · 1 thread · pooled JDBC · fresh transaction per query · independent axes</text>', '</g></svg>'])
    (args.output / "read-latency.svg").write_text("\n".join(parts), encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
