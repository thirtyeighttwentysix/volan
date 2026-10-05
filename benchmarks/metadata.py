"""Capture provenance after a completed run; no credentials or environment dumps."""
import argparse
import datetime
import hashlib
import json
import os
import platform
import re
import subprocess
from pathlib import Path


def command(*args):
    return subprocess.check_output(args, encoding="utf-8", errors="replace", timeout=30).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("results", type=Path)
    parser.add_argument("--container", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    results = json.loads(args.results.read_text(encoding="utf-8"))
    first = results[0]
    sources = []
    for folder in ("benchmarks/src", "benchmarks/schema", "volan-runtime/src/main", "volan-codegen/src/main",
                   "volan-core/src/main", "volan-dialect-api/src/main", "volan-dialect-postgres/src/main",
                   "volan-ir/src/main", "volan-schema/src/main"):
        sources.extend(path for path in (root / folder).rglob("*") if path.is_file())
    sources.extend(root / path for path in ("gradle/libs.versions.toml", "benchmarks/build.gradle.kts", "benchmarks/seed.sql", "benchmarks/compose.yaml"))
    digest = hashlib.sha256()
    for path in sorted(sources):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(b"\0")
        digest.update(path.read_bytes().replace(b"\r\n", b"\n"))
        digest.update(b"\0")
    versions = dict(re.findall(r'^([\w]+) = "([^"]+)"$', (root / "gradle/libs.versions.toml").read_text(), re.MULTILINE))
    metadata = {
        "recordedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "sourceCommit": command("git", "rev-parse", "HEAD"),
        "sourceTreeSha256": digest.hexdigest(),
        "resultsSha256": hashlib.sha256(args.results.read_bytes().replace(b"\r\n", b"\n")).hexdigest(),
        "digestLineEndings": "CRLF normalized to LF for source and results digests",
        "host": platform.platform(),
        "cpu": platform.processor(),
        "logicalProcessors": os.cpu_count(),
        "postgres": command("docker", "exec", args.container, "psql", "-U", "volan_bench", "-d", "volan_bench", "-Atc", "select version()"),
        "postgresImage": command("docker", "inspect", "--format", "{{.Image}}", args.container),
        "docker": command("docker", "version", "--format", "{{.Server.Version}}"),
        "dockerCpus": int(command("docker", "info", "--format", "{{.NCPU}}")),
        "dockerMemoryBytes": int(command("docker", "info", "--format", "{{.MemTotal}}")),
        "versions": {key: versions[key] for key in ("hibernate", "exposed", "jooq", "postgres", "hikari", "jmh", "kotlin")},
        "volanVersion": re.search(r'^version=(.+)$', (root / "gradle.properties").read_text(), re.MULTILINE).group(1),
        "jvm": {key: first[key] for key in ("jdkVersion", "vmName", "vmVersion", "jvmArgs")},
        "cases": len(results), "threads": sorted({result["threads"] for result in results}),
        "forks": first["forks"], "warmup": [first["warmupIterations"], first["warmupTime"]],
        "measurement": [first["measurementIterations"], first["measurementTime"]],
        "datasetRows": 10000, "pool": "HikariCP min/max 4 shared by all threads",
    }
    if os.name == "nt":
        processor = json.loads(command("powershell.exe", "-NoProfile", "-Command",
                                      "Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors | ConvertTo-Json -Compress"))
        metadata["cpu"] = processor["Name"].strip()
        metadata["physicalCores"] = processor["NumberOfCores"]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(metadata, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Recorded {len(results)} cases in {args.output}")


if __name__ == "__main__":
    main()
