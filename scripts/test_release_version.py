import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("release_version", Path(__file__).with_name("release-version.py"))
version = importlib.util.module_from_spec(spec)
spec.loader.exec_module(version)


class ReleaseVersionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "gradle.properties").write_text("version=0.1.0-alpha.3-SNAPSHOT\n", encoding="utf-8")
        (self.root / "CHANGELOG.md").write_text("## [Unreleased]\n", encoding="utf-8")

    def test_branch_default_and_explicit_candidate(self):
        self.assertEqual("0.1.0-alpha.3", version.select(self.root, "branch", "main"))
        self.assertEqual("1.0.0-rc.1", version.select(self.root, "branch", "codex/m12-release", "1.0.0-rc.1"))

    def test_refuses_snapshot_tag_mismatch_and_tag_override(self):
        with self.assertRaises(ValueError):
            version.select(self.root, "tag", "v0.1.0-alpha.3-SNAPSHOT")
        (self.root / "gradle.properties").write_text("version=1.0.0\n", encoding="utf-8")
        for tag, candidate in (("v1.0.1", ""), ("v1.0.0", "1.0.1")):
            with self.assertRaises(ValueError):
                version.select(self.root, "tag", tag, candidate)

    def test_tag_requires_notes_and_dated_changelog(self):
        (self.root / "gradle.properties").write_text("version=1.0.0\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            version.select(self.root, "tag", "v1.0.0")
        notes = self.root / "docs/releases/1.0.0.md"
        notes.parent.mkdir(parents=True)
        notes.write_text("# Volan 1.0.0\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            version.select(self.root, "tag", "v1.0.0")
        (self.root / "CHANGELOG.md").write_text("## [1.0.0] - 2026-10-05\n", encoding="utf-8")
        self.assertEqual("1.0.0", version.select(self.root, "tag", "v1.0.0"))

    def test_refuses_malformed_or_unsafe_candidates(self):
        for candidate in ("../1.0.0", "1.0.0; echo unsafe", "1.0.0-rc.01", "01.0.0", "1.0.0-rc..1", "1.0.0-SNAPSHOT"):
            with self.subTest(candidate=candidate), self.assertRaises(ValueError):
                version.select(self.root, "branch", "main", candidate)


if __name__ == "__main__":
    unittest.main()
