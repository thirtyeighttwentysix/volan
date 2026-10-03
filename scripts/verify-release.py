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
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
PLUGIN_ID = "io.github.thirtyeighttwentysix.volan"
MARKER = PLUGIN_ID + ".gradle.plugin"


def libraries_for(version):
    # Keep verification of immutable alphas aligned with the modules each release actually contains.
    libraries = LIBRARIES if version == "0.1.0-alpha.1" else LIBRARIES | {"volan-dialect-sqlite"}
    return libraries if version in {"0.1.0-alpha.1", "0.1.0-alpha.2"} else libraries | {
        "volan-dialect-h2", "volan-dialect-mysql", "volan-coroutines", "volan-micrometer",
    }


def plugins_for(version):
    return set() if version in {"0.1.0-alpha.1", "0.1.0-alpha.2"} else {"volan-gradle-plugin", "volan-maven-plugin"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def files_for(artifact, version):
    prefix = f"{artifact}-{version}"
    suffixes = [".pom", ".module"]
    if artifact in libraries_for(version) | plugins_for(version):
        suffixes += [".jar", "-sources.jar", "-javadoc.jar"]
    return [prefix + suffix for suffix in suffixes]


def download(repository, version, signatures, wait):
    pending = []
    for artifact in sorted(libraries_for(version) | plugins_for(version) | {"volan-bom"}):
        folder = Path(GROUP.replace(".", "/")) / artifact / version
        for name in files_for(artifact, version):
            pending += [folder / name, folder / (name + ".sha1"), folder / (name + ".md5")]
            if signatures:
                pending.append(folder / (name + ".asc"))
    if plugins_for(version):
        marker = Path(PLUGIN_ID.replace(".", "/")) / MARKER / version / f"{MARKER}-{version}.pom"
        pending += [marker, Path(str(marker) + ".sha1"), Path(str(marker) + ".md5")]
        if signatures:
            pending.append(Path(str(marker) + ".asc"))
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
    libraries = libraries_for(version)
    artifacts = libraries | plugins_for(version) | {"volan-bom"}
    base = repository / GROUP.replace(".", "/")
    present = {p.name for p in base.iterdir() if (p / version).is_dir()}
    require(present == artifacts, f"Unexpected artifact set: {present ^ artifacts}")
    with tempfile.TemporaryDirectory(prefix="volan-public-key-") as keyring:
        gpg = shutil.which("gpg")
        if public_key:
            require(gpg, "gpg is required to verify release signatures")
            # Relative paths also work with the MSYS GPG bundled with Git for Windows.
            Path(keyring, "common.conf").write_text("")
            shutil.copyfile(public_key, Path(keyring, "public-key.asc"))
            subprocess.run([gpg, "--homedir", ".", "--batch", "--import", "public-key.asc"],
                           cwd=keyring, check=True, capture_output=True)
        for artifact in sorted(artifacts):
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
                    require(name in artifacts and dependency_version == version, f"Invalid Volan dependency: {name}")
            if artifact == "volan-bom":
                require(value("m:packaging") == "pom", "BOM must have pom packaging")
                require({d.findtext("m:artifactId", namespaces=NS) for d in dependencies} == libraries,
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
                    shutil.copyfile(path, Path(keyring, "artifact"))
                    shutil.copyfile(str(path) + ".asc", Path(keyring, "artifact.asc"))
                    subprocess.run([gpg, "--homedir", ".", "--batch", "--verify", "artifact.asc", "artifact"],
                                   cwd=keyring, check=True, capture_output=True)
            print(f"Verified {artifact}:{version}")
        if plugins_for(version):
            verify_plugins(repository, version, public_key, keyring, gpg)


def verify_plugins(repository, version, public_key, keyring, gpg):
    marker = repository / PLUGIN_ID.replace(".", "/") / MARKER / version / f"{MARKER}-{version}.pom"
    pom = ET.parse(marker).getroot()
    require(pom.findtext("m:groupId", namespaces=NS) == PLUGIN_ID, "Wrong plugin marker group")
    require(pom.findtext("m:artifactId", namespaces=NS) == MARKER, "Wrong plugin marker artifact")
    require(pom.findtext("m:version", namespaces=NS) == version, "Wrong plugin marker version")
    dependency = pom.find("m:dependencies/m:dependency", NS)
    require(dependency is not None and dependency.findtext("m:groupId", namespaces=NS) == GROUP
            and dependency.findtext("m:artifactId", namespaces=NS) == "volan-gradle-plugin"
            and dependency.findtext("m:version", namespaces=NS) == version, "Invalid plugin marker target")
    for algorithm in ("sha1", "md5"):
        require(Path(str(marker) + "." + algorithm).read_text().strip() == hashlib.new(algorithm, marker.read_bytes()).hexdigest(),
                f"Invalid marker {algorithm}")
    if public_key:
        shutil.copyfile(marker, Path(keyring, "artifact"))
        shutil.copyfile(str(marker) + ".asc", Path(keyring, "artifact.asc"))
        subprocess.run([gpg, "--homedir", ".", "--batch", "--verify", "artifact.asc", "artifact"],
                       cwd=keyring, check=True, capture_output=True)
    for artifact, entry in (("volan-gradle-plugin", f"META-INF/gradle-plugins/{PLUGIN_ID}.properties"),
                            ("volan-maven-plugin", "META-INF/maven/plugin.xml")):
        jar = repository / GROUP.replace(".", "/") / artifact / version / f"{artifact}-{version}.jar"
        with zipfile.ZipFile(jar) as archive:
            require(entry in archive.namelist(), f"Missing plugin descriptor: {artifact}")
            if artifact == "volan-maven-plugin":
                descriptor = ET.fromstring(archive.read(entry))
                require(descriptor.findtext("version") == version and descriptor.findtext("mojos/mojo/goal") == "generate"
                        and descriptor.findtext("mojos/mojo/phase") == "generate-sources", "Invalid Maven plugin descriptor")
    print(f"Verified Gradle marker and both plugin descriptors:{version}")


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
