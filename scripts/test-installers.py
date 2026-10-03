#!/usr/bin/env python3
"""Installer integration tests: isolated downloads, homes and Windows user PATH.

No real user configuration is touched. With --assets, also test an actual packaged CLI.
"""
import argparse
import contextlib
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile

SCRIPTS = Path(__file__).resolve().parent
ASSETS = None
SPEC = importlib.util.spec_from_file_location("package_cli", SCRIPTS / "package-cli.py")
PACKAGER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGER)
TAG = "cli-v0.1.0-alpha.3-preview.1"


def ps_quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def shell_path(value):
    path = Path(value).resolve().as_posix()
    return "/" + path[0].lower() + path[2:] if os.name == "nt" else path


class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="volan-install-test-")
        self.base = Path(self.temp.name)
        self.assets = self.base / "assets"
        self.home = self.base / "home"
        self.home.mkdir()
        self.root = self.home / "tools with 'quotes" / "Volan"
        self.mock_path = self.base / "user-path.txt"
        self.mock_path.write_text("existing-path", encoding="utf-8")
        if ASSETS:
            shutil.copytree(ASSETS, self.assets)
        else:
            distribution = self.base / "distribution"
            (distribution / "bin").mkdir(parents=True)
            (distribution / "lib").mkdir()
            (distribution / "bin/volan").write_text("#!/bin/sh\nprintf 'Volan test CLI\\n'\n", encoding="utf-8")
            (distribution / "bin/volan.bat").write_text("@echo off\necho Volan test CLI\nexit /b 0\n", encoding="ascii")
            (distribution / "lib/volan-cli-test.jar").write_bytes(b"test-jar")
            with contextlib.redirect_stdout(io.StringIO()):
                PACKAGER.package(distribution, self.assets)
        (self.assets / "cli-release.txt").write_text(TAG + "\n", encoding="ascii")
        self.env = os.environ.copy()
        self.env.pop("VOLAN_TAG", None)
        self.env.pop("VOLAN_INSTALL_DIR", None)
        self.env["PYTHONUTF8"] = "1"
        self.windows = os.name == "nt"
        if self.windows:
            self.env["USERPROFILE"] = str(self.home)
            source = (SCRIPTS / "install.ps1").read_text(encoding="utf-8")
            substitutions = {
                "[Environment]::GetEnvironmentVariable('Path', 'User')": "$global:mockUserPath",
                "[Environment]::SetEnvironmentVariable('Path', (Add-VolanPath $oldUserPath), 'User')": "if ($env:VOLAN_TEST_PATH_FAILURE) { throw 'Simulated PATH failure' }; $global:mockUserPath = (Add-VolanPath $oldUserPath)",
                "[Environment]::SetEnvironmentVariable('Path', $oldUserPath, 'User')": "$global:mockUserPath = $oldUserPath",
            }
            for original, replacement in substitutions.items():
                self.assertEqual(source.count(original), 1, "Only the OS user-PATH adapter is mocked")
                source = source.replace(original, replacement)
            self.script = self.base / "install.ps1"
            self.script.write_text(source, encoding="utf-8-sig")
            wrapper = f"""
$ErrorActionPreference = 'Stop'
$global:mockUserPath = Get-Content -Raw -LiteralPath {ps_quote(self.mock_path)}
function Invoke-WebRequest {{
    param([switch]$UseBasicParsing, [string]$Uri, [string]$OutFile, [int]$TimeoutSec)
    if ($Uri -notmatch '^https://(github.com/thirtyeighttwentysix/volan/releases/download/|raw.githubusercontent.com/thirtyeighttwentysix/volan/main/scripts/)') {{ throw 'Unexpected URL' }}
    Copy-Item -LiteralPath (Join-Path {ps_quote(self.assets)} ($Uri.Split('/')[-1])) -Destination $OutFile
}}
try {{
    & {ps_quote(self.script)} -InstallDir {ps_quote(self.root)}
    if ($env:Path.Split(';')[0] -ne {ps_quote(str(self.root / 'bin'))}) {{ throw 'Process PATH was not updated' }}
}} finally {{ [IO.File]::WriteAllText({ps_quote(self.mock_path)}, $global:mockUserPath) }}
"""
            self.runner = self.base / "run.ps1"
            self.runner.write_text(wrapper, encoding="utf-8-sig")
        else:
            self.env["HOME"] = str(self.home)
            self.env["SHELL"] = "/bin/zsh"
            self.env["XDG_CONFIG_HOME"] = str(self.home / ".config")
            self.env["ZDOTDIR"] = str(self.home)
            self.env["VOLAN_INSTALL_DIR"] = str(self.root)
            self.env["VOLAN_TEST_ASSETS"] = str(self.assets)
            (self.home / ".bash_profile").write_text("# existing login profile\n", encoding="utf-8")
            (self.home / ".config/fish").mkdir(parents=True)
            fakebin = self.base / "fakebin"
            fakebin.mkdir()
            fetch = fakebin / "fetch.py"
            fetch.write_text("""import os,sys,shutil
from pathlib import Path
args=sys.argv[1:]
url=next(a for a in args if a.startswith('https://'))
assert url.startswith(('https://github.com/thirtyeighttwentysix/volan/releases/download/', 'https://raw.githubusercontent.com/thirtyeighttwentysix/volan/main/scripts/'))
shutil.copyfile(Path(os.environ['VOLAN_TEST_ASSETS'])/url.rsplit('/',1)[1], args[args.index('-o')+1])
""", encoding="utf-8")
            curl = fakebin / "curl"
            curl.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{fetch}" "$@"\n', encoding="utf-8")
            curl.chmod(0o755)
            self.env["PATH"] = str(fakebin) + os.pathsep + self.env["PATH"]
            self.script = SCRIPTS / "install.sh"

    def tearDown(self):
        self.temp.cleanup()

    def run_installer(self, success=True):
        if self.windows:
            command = [os.environ.get("VOLAN_TEST_POWERSHELL", "powershell.exe"), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(self.runner)]
        else:
            command = ["bash", str(self.script)]
        result = subprocess.run(command, env=self.env, text=True, capture_output=True, timeout=120)
        self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
        return result

    def rehash(self):
        (self.assets / "SHA256SUMS").write_text("".join(
            hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.name + "\n"
            for path in sorted(self.assets.glob("volan-cli.*"))
        ), encoding="ascii")

    def archive_with_entry(self, name, link=False):
        if self.windows:
            with zipfile.ZipFile(self.assets / "volan-cli.zip", "w") as archive:
                info = zipfile.ZipInfo(name)
                if link:
                    info.external_attr = 0o120777 << 16
                archive.writestr(info, b"outside")
        else:
            with tarfile.open(self.assets / "volan-cli.tar.gz", "w:gz") as archive:
                info = tarfile.TarInfo(name)
                if link:
                    info.type = tarfile.SYMTYPE
                    info.linkname = "/tmp/outside"
                    archive.addfile(info)
                else:
                    info.size = 7
                    archive.addfile(info, io.BytesIO(b"outside"))
        self.rehash()

    def test_install_upgrade_and_path(self):
        self.run_installer()
        self.assertEqual((self.root / ".volan-install").read_text().strip(), TAG)
        (self.root / "lib/stale-old.jar").write_bytes(b"stale")
        self.run_installer()
        self.assertFalse((self.root / "lib/stale-old.jar").exists())
        self.assertFalse(Path(str(self.root) + ".install-lock").exists())
        if self.windows:
            parts = self.mock_path.read_text(encoding="utf-8").split(";")
            self.assertEqual(parts.count(str(self.root / "bin")), 1)
            self.assertIn("existing-path", parts)
        else:
            for name in (".profile", ".bashrc", ".bash_profile", ".zshrc", ".zprofile", ".config/fish/config.fish"):
                self.assertEqual((self.home / name).read_text().count("# Volan CLI"), 1)
            result = subprocess.run(["bash", "-c", '. "$HOME/.config/volan/env.sh"; . "$HOME/.config/volan/env.sh"; volan --help; printf "\\n%s\\n" "$PATH"'], env=self.env, capture_output=True, text=True, timeout=60)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.splitlines()[-1].split(":" ).count(str(self.root / "bin")), 1)

    def test_checksum_failure_keeps_existing_installation(self):
        self.run_installer()
        (self.root / "keep.txt").write_text("old", encoding="ascii")
        archive = self.assets / ("volan-cli.zip" if self.windows else "volan-cli.tar.gz")
        archive.write_bytes(archive.read_bytes() + b"corrupt")
        result = self.run_installer(False)
        self.assertIn("checksum", (result.stdout + result.stderr).lower())
        self.assertEqual((self.root / "keep.txt").read_text(), "old")

    def test_unrelated_directory_is_preserved(self):
        self.root.mkdir(parents=True)
        (self.root / "personal.txt").write_text("keep", encoding="ascii")
        self.run_installer(False)
        self.assertEqual((self.root / "personal.txt").read_text(), "keep")

    def test_path_traversal_is_rejected(self):
        self.archive_with_entry("volan/../../outside.txt")
        self.run_installer(False)
        self.assertFalse(self.root.exists())
        self.assertFalse((self.base / "outside.txt").exists())

    def test_archive_link_is_rejected(self):
        self.archive_with_entry("volan/lib/link", link=True)
        self.run_installer(False)
        self.assertFalse(self.root.exists())

    def test_invalid_tag_is_rejected(self):
        self.env["VOLAN_TAG"] = "../../unsafe"
        self.run_installer(False)
        self.assertFalse(self.root.exists())

    def test_java_home_is_validated(self):
        self.env["JAVA_HOME"] = str(self.base / "missing-java")
        self.run_installer(False)
        self.assertFalse(self.root.exists())

    def test_missing_download_keeps_previous_installation(self):
        self.run_installer()
        (self.root / "keep.txt").write_text("old", encoding="ascii")
        (self.assets / "SHA256SUMS").unlink()
        self.run_installer(False)
        self.assertEqual((self.root / "keep.txt").read_text(), "old")

    def test_incomplete_archive_is_rejected(self):
        self.archive_with_entry("volan/lib/unrelated.jar")
        self.run_installer(False)
        self.assertFalse(self.root.exists())

    def test_lock_is_respected(self):
        lock = Path(str(self.root) + ".install-lock")
        lock.mkdir(parents=True)
        self.run_installer(False)
        self.assertTrue(lock.is_dir())
        self.assertFalse(self.root.exists())

    def test_activation_failure_restores_previous_installation(self):
        self.run_installer()
        (self.root / "keep.txt").write_text("old", encoding="ascii")
        if self.windows:
            self.env["VOLAN_TEST_PATH_FAILURE"] = "1"
        else:
            (self.home / ".profile").unlink()
            (self.home / ".profile").mkdir()
        self.run_installer(False)
        self.assertEqual((self.root / "keep.txt").read_text(), "old")
        self.assertFalse(Path(str(self.root) + ".install-lock").exists())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, help="Test the real packaged distribution")
    args, remaining = parser.parse_known_args()
    ASSETS = args.assets.resolve() if args.assets else None
    unittest.main(argv=[sys.argv[0]] + remaining, verbosity=2)
