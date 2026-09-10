"""Validate the actual Maven repository, optionally downloading it from Central first."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

GROUP = "io.github.thirtyeighttwentysix"
LIBRARIES = {
    "volan-core", "volan-schema", "volan-ir", "volan-codegen", "volan-dialect-api",
    "volan-dialect-postgres", "volan-runtime", "volan-migrate",
}
ARTIFACTS = LIBRARIES | {"volan-bom"}
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def files_for(artifact, version):
    prefix = f"{artifact}-{version}"
    suffixes = [".pom", ".module"]
    if artifact in LIBRARIES:
        suffixes += [".jar", "-sources.jar", "-javadoc.jar"]
    return [prefix + suffix for suffix in suffixes]


def download(repository, version, signatures, wait):
    pending = []
    for artifact in sorted(ARTIFACTS):
        folder = Path(GROUP.replace(".", "/")) / artifact / version
        for name in files_for(artifact, version):
            pending += [folder / name, folder / (name + ".sha1"), folder / (name + ".md5")]
            if signatures:
                pending.append(folder / (name + ".asc"))
    deadline = time.monotonic() + wait
    while pending:
        missing = []
        for relative in pending:
            try:
                with urllib.request.urlopen("https://repo.maven.apache.org/maven2/" + relative.as_posix(), timeout=30) as response:
                    content = response.read()
                target = repository / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(content)
            except urllib.error.HTTPError as error:
                if error.code not in (404, 429, 500, 502, 503, 504):
                    raise
                missing.append(relative)
        if not missing:
            break
        require(time.monotonic() < deadline, f"Central still missing {len(missing)} files: {missing[0]}")
        print(f"Waiting for Central: {len(missing)} files pending", flush=True)
        time.sleep(min(30, max(0, deadline - time.monotonic())))
        pending = missing


def verify(repository, version, public_key):
    base = repository / GROUP.replace(".", "/")
    present = {p.name for p in base.iterdir() if (p / version).is_dir()}
    require(present == ARTIFACTS, f"Unexpected artifact set: {present ^ ARTIFACTS}")
    with tempfile.TemporaryDirectory(prefix="volan-public-key-") as keyring:
        gpg = shutil.which("gpg")
        if public_key:
            require(gpg, "gpg is required to verify release signatures")
            subprocess.run([gpg, "--homedir", keyring, "--batch", "--import", str(public_key)], check=True, capture_output=True)
        for artifact in sorted(ARTIFACTS):
            folder = base / artifact / version
            pom = ET.parse(folder / f"{artifact}-{version}.pom").getroot()
            value = lambda path: pom.findtext(path, namespaces=NS)
            require(value("m:groupId") == GROUP and value("m:artifactId") == artifact
                    and value("m:version") == version, f"Wrong coordinates: {artifact}")
            for field in ["m:name", "m:description", "m:url", "m:licenses/m:license/m:name",
                          "m:licenses/m:license/m:url", "m:developers/m:developer/m:id",
                          "m:scm/m:url", "m:scm/m:connection", "m:scm/m:developerConnection"]:
                require(value(field), f"Missing POM {field}: {artifact}")
            dependencies = pom.findall(".//m:dependency", NS)
            for dependency in dependencies:
                group = dependency.findtext("m:groupId", namespaces=NS)
                name = dependency.findtext("m:artifactId", namespaces=NS)
                dependency_version = dependency.findtext("m:version", namespaces=NS)
                require(dependency_version and "SNAPSHOT" not in dependency_version, f"Unreleased dependency: {name}")
                if group == GROUP:
                    require(name in LIBRARIES and dependency_version == version, f"Invalid Volan dependency: {name}")
            if artifact == "volan-bom":
                require(value("m:packaging") == "pom", "BOM must have pom packaging")
                require({d.findtext("m:artifactId", namespaces=NS) for d in dependencies} == LIBRARIES,
                        "BOM must constrain exactly the published libraries")
            metadata = json.loads((folder / f"{artifact}-{version}.module").read_text())
            require(metadata["component"]["version"] == version, f"Wrong module metadata: {artifact}")
            for name in files_for(artifact, version):
                path = folder / name
                content = path.read_bytes()
                for algorithm in ["sha1", "md5"]:
                    digest = hashlib.new(algorithm, content).hexdigest()
                    require(path.with_name(name + "." + algorithm).read_text().strip() == digest,
                            f"Invalid {algorithm}: {path}")
                if name.endswith(".jar"):
                    with zipfile.ZipFile(path) as jar:
                        entries = jar.namelist()
                        suffix = ".kt" if name.endswith("-sources.jar") else ".html" if name.endswith("-javadoc.jar") else ".class"
                        require(any(entry.endswith(suffix) for entry in entries), f"Empty artifact: {path}")
                        if suffix == ".class":
                            for entry in entries:
                                if entry.endswith(".class"):
                                    require(int.from_bytes(jar.read(entry)[6:8], "big") <= 61, f"Requires newer than Java 17: {entry}")
                if public_key:
                    subprocess.run([gpg, "--homedir", keyring, "--batch", "--verify", str(path) + ".asc", str(path)],
                                   check=True, capture_output=True)
            print(f"Verified {artifact}:{version}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path("build/release-repository"))
    parser.add_argument("--version", required=True)
    parser.add_argument("--public-key", type=Path, help="Require and cryptographically verify every signature")
    parser.add_argument("--central", action="store_true")
    parser.add_argument("--wait-seconds", type=int, default=1800)
    args = parser.parse_args()
    require(re.fullmatch(r"\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?", args.version), "Invalid release version")
    require("SNAPSHOT" not in args.version, "This verifier is for immutable releases")
    if args.central:
        download(args.repository, args.version, args.public_key is not None, args.wait_seconds)
    verify(args.repository, args.version, args.public_key)
