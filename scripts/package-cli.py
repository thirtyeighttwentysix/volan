#!/usr/bin/env python3
"""Package one portable JVM CLI distribution for all supported desktop systems."""
import argparse
import hashlib
from pathlib import Path
import shutil
import tarfile
import zipfile


def package(distribution: Path, output: Path) -> None:
    for required in ("bin/volan", "bin/volan.bat"):
        if not (distribution / required).is_file():
            raise ValueError(f"Missing launcher: {required}")
    if not list((distribution / "lib").glob("volan-cli-*.jar")):
        raise ValueError("Missing CLI jar")
    output.mkdir(parents=True, exist_ok=True)
    files = sorted(path for path in distribution.rglob("*") if path.is_file())
    with zipfile.ZipFile(output / "volan-cli.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            name = "volan/" + path.relative_to(distribution).as_posix()
            info = zipfile.ZipInfo(name)
            info.external_attr = (0o100755 if name == "volan/bin/volan" else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes())
    with tarfile.open(output / "volan-cli.tar.gz", "w:gz") as archive:
        for path in files:
            info = archive.gettarinfo(str(path), "volan/" + path.relative_to(distribution).as_posix())
            info.mode = 0o755 if info.name == "volan/bin/volan" else 0o644
            info.uid = info.gid = info.mtime = 0
            info.uname = info.gname = ""
            with path.open("rb") as source:
                archive.addfile(info, source)
    for script in ("install.ps1", "install.sh"):
        shutil.copyfile(Path(__file__).with_name(script), output / script)
    names = ("volan-cli.zip", "volan-cli.tar.gz", "install.ps1", "install.sh")
    (output / "SHA256SUMS").write_text("".join(
        f"{hashlib.sha256((output / name).read_bytes()).hexdigest()}  {name}\n" for name in names
    ), encoding="ascii")
    print(f"Packaged portable CLI in {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--distribution", type=Path, default=Path("volan-cli/build/install/volan"))
    parser.add_argument("--output", type=Path, default=Path("build/cli-release"))
    args = parser.parse_args()
    package(args.distribution, args.output)
