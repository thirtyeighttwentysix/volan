"""Build independent Gradle and Maven consumers from staged or Central plugin artifacts."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess


def run(command, directory):
    if os.name == "nt" and str(command[0]).endswith((".bat", ".cmd")):
        command = ["cmd.exe", "/d", "/c", *map(str, command)]
    result = subprocess.run(command, cwd=directory, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            encoding="utf-8", errors="replace", timeout=600)
    print(result.stdout, flush=True)
    if result.returncode:
        raise RuntimeError(f"Build failed ({result.returncode}): {command}")
    return result.stdout


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def verify(root, version, repository, maven, gradle_user_home, only):
    base = root / "build/plugin-smoke"
    # This directory is exclusively owned by this script, never a caller-supplied deletion target.
    require(base.resolve().parent == (root / "build").resolve(), "Invalid smoke workspace")
    if base.exists():
        shutil.rmtree(base)
    engines = (only,) if only else ("gradle", "maven")
    for kind in engines:
        shutil.copytree(root / "plugin-smoke" / kind, base / kind,
                        ignore=shutil.ignore_patterns("build", ".gradle", "target"))
    gradle = [str(root / ("gradlew.bat" if os.name == "nt" else "gradlew")), "test", "--configuration-cache", "--build-cache",
              "--max-workers=2", "--no-parallel", "-Pkotlin.compiler.execution.strategy=in-process",
              f"-PvolanVersion={version}", "--console=plain"]
    if gradle_user_home:
        gradle += ["--gradle-user-home", str(gradle_user_home)]
    mvn = [maven, "--batch-mode", "--no-transfer-progress", "test", f"-Dvolan.version={version}"]
    if repository:
        gradle += [f"-PvolanRepository={repository.resolve().as_uri()}"]
        mvn += [f"-DvolanRepository={repository.resolve().as_uri()}"]
    commands = {"gradle": gradle, "maven": mvn}
    folders = {"gradle": "build/generated/volan", "maven": "target/generated-sources/volan"}
    for kind in engines:
        directory = base / kind
        run(commands[kind] + (["--refresh-dependencies"] if kind == "gradle" else []), directory)
        if kind == "gradle":
            repeated = run(gradle, directory)
            require("Reusing configuration cache" in repeated and ":volanGenerate UP-TO-DATE" in repeated,
                    "Gradle did not reuse configuration cache and generation outputs")
        schema = directory / "schema.volan"
        original = schema.read_text()
        schema.write_text(original + "\nmodel Removed { id Int @id }\n")
        run(commands[kind], directory)
        generated = directory / folders[kind] / "example/generated/Removed.kt"
        require(generated.exists(), f"{kind}: changed schema was not generated")
        classes = directory / ("build/classes/kotlin/main" if kind == "gradle" else "target/classes")
        removed_class = classes / "example/generated/Removed.class"
        require(removed_class.exists(), f"{kind}: generated model was not compiled")
        schema.write_text(original)
        run(commands[kind], directory)
        require(not generated.exists(), f"{kind}: removed model source survived")
        require(not removed_class.exists(), f"{kind}: removed model bytecode survived")
        if kind == "gradle":
            cached = run([gradle[0], "clean", *gradle[1:]], directory)
            require(":volanGenerate FROM-CACHE" in cached, "Gradle did not restore generated sources from build cache")
        print(f"Verified {kind}: automatic generation, compilation, CRUD and model removal", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--repository", type=Path)
    parser.add_argument("--maven", default="mvn.cmd" if os.name == "nt" else "mvn")
    parser.add_argument("--gradle-user-home", type=Path)
    parser.add_argument("--only", choices=("gradle", "maven"))
    args = parser.parse_args()
    verify(Path(__file__).resolve().parent.parent, args.version, args.repository,
           args.maven, args.gradle_user_home, args.only)
