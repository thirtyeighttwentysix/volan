"""Select an unsigned rehearsal version or validate an immutable tag before publishing."""
import argparse
import re
from pathlib import Path

VERSION = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?")


def validate(version):
    match = VERSION.fullmatch(version)
    if match is None:
        raise ValueError("Expected a semantic version without build metadata")
    if match[1] and any(part.isdigit() and len(part) > 1 and part.startswith("0") for part in match[1].split(".")):
        raise ValueError("Numeric prerelease identifiers cannot have leading zeroes")
    return version


def select(root, ref_type, ref_name, candidate=""):
    matches = re.findall(r"^version=(.+)$", (root / "gradle.properties").read_text(encoding="utf-8"), re.MULTILINE)
    if len(matches) != 1:
        raise ValueError("gradle.properties must declare exactly one version")
    declared = validate(matches[0].strip())
    if ref_type == "tag":
        if candidate:
            raise ValueError("A tag cannot override its declared version")
        if "SNAPSHOT" in declared or ref_name != "v" + declared:
            raise ValueError("Release tag must match the non-SNAPSHOT project version")
        notes = root / "docs/releases" / (declared + ".md")
        if not notes.is_file() or not notes.read_text(encoding="utf-8").strip():
            raise ValueError("Release notes are required before publication")
        changelog = (root / "CHANGELOG.md").read_text(encoding="utf-8")
        if not re.search(r"^## \[" + re.escape(declared) + r"\] - \d{4}-\d{2}-\d{2}$", changelog, re.MULTILINE):
            raise ValueError("A dated changelog entry is required before publication")
        return declared
    if ref_type != "branch":
        raise ValueError("Only a branch rehearsal or release tag is supported")
    version = validate(candidate or declared.removesuffix("-SNAPSHOT"))
    if "SNAPSHOT" in version:
        raise ValueError("Rehearsal artifacts must use a non-SNAPSHOT version")
    return version


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref-type", required=True)
    parser.add_argument("--ref-name", required=True)
    parser.add_argument("--candidate", default="")
    args = parser.parse_args()
    try:
        print(select(Path(__file__).resolve().parent.parent, args.ref_type, args.ref_name, args.candidate))
    except ValueError as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
