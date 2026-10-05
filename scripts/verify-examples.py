"""Build independent documentation examples from staged or Central artifacts."""
import argparse
import os
import shutil
import subprocess
from pathlib import Path

EXAMPLES = ("kotlin-basic", "java-basic", "spring-boot", "ktor")


def verify(root, version, repository, gradle_user_home, only):
    base = root / "build/example-smoke"
    if base.resolve().parent != (root / "build").resolve():
        raise ValueError("Invalid example smoke directory")
    if base.exists():
        shutil.rmtree(base)
    for name in ((only,) if only else EXAMPLES):
        destination = base / name
        shutil.copytree(root / "examples" / name, destination, ignore=shutil.ignore_patterns("build", ".gradle"))
        command = [str(root / ("gradlew.bat" if os.name == "nt" else "gradlew")), "clean", "test", "installDist",
                   "--max-workers=2", "--no-parallel", "-Pkotlin.compiler.execution.strategy=in-process",
                   "--console=plain", f"-PvolanVersion={version}"]
        if name != "ktor":
            command += ["run"]
        if repository:
            command += [f"-PvolanRepository={repository.resolve().as_uri()}"]
        if gradle_user_home:
            command += ["--gradle-user-home", str(gradle_user_home)]
        if os.name == "nt":
            command = ["cmd.exe", "/d", "/c", *command]
        result = subprocess.run(command, cwd=destination, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                encoding="utf-8", errors="replace", timeout=600)
        print(result.stdout, flush=True)
        if result.returncode:
            raise RuntimeError(f"{name}: example build failed ({result.returncode})")
        if name != "ktor" and "Saved and read: Ada" not in result.stdout:
            raise AssertionError(f"{name}: application did not produce its documented result")
        if not list((destination / "build/test-results/test").glob("TEST-*.xml")):
            raise AssertionError(f"{name}: no test reports")
        print(f"Verified {name}: automatic generation, migrations, application and smoke test", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--repository", type=Path)
    parser.add_argument("--gradle-user-home", type=Path)
    parser.add_argument("--only", choices=EXAMPLES)
    args = parser.parse_args()
    verify(Path(__file__).resolve().parent.parent, args.version, args.repository, args.gradle_user_home, args.only)
