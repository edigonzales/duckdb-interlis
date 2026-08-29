import importlib.util
import json
import pathlib
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "release_metadata", ROOT / "scripts/release_metadata.py"
)
release_metadata = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(release_metadata)


class ReleaseMetadataTest(unittest.TestCase):
    def test_rendered_native_manifest_uses_exact_lock_versions(self):
        lock = release_metadata.load_lock(ROOT)
        rendered = release_metadata.rendered_files(ROOT, lock)
        manifest = json.loads(
            rendered[pathlib.Path("vcpkg/native-deps/vcpkg.json")]
        )
        overrides = {
            entry["name"]: entry["version-string"] for entry in manifest["overrides"]
        }
        self.assertEqual(
            overrides["iox-cpp"], lock["dependencies"]["iox-cpp"]["packageVersion"]
        )
        self.assertEqual(
            overrides["ilic"], lock["dependencies"]["ilic"]["packageVersion"]
        )

    def test_manifest_contains_full_source_and_dependency_shas(self):
        source_sha = "0123456789abcdef0123456789abcdef01234567"
        manifest = release_metadata.release_manifest(
            ROOT, source_sha, "42", "2026-08-29T12:00:00Z", "AppleClang"
        )
        self.assertEqual(manifest["sourceSha"], source_sha)
        self.assertRegex(manifest["duckdb"]["sourceSha"], r"^[0-9a-f]{40}$")
        self.assertRegex(
            manifest["dependencies"]["iox-cpp"]["sourceSha"], r"^[0-9a-f]{40}$"
        )

    def test_historical_v020_contract_is_frozen(self):
        history = json.loads(
            (ROOT / "release/history/v0.2.0.json").read_text(encoding="utf-8")
        )
        self.assertEqual(history["duckdb"]["version"], "1.5.3")
        self.assertEqual(
            history["tagSourceSha"], "db28b12eb892fb70e1456050177601650e2e498a"
        )
        self.assertTrue(history["releaseMetadataNeedsCorrection"])
        self.assertEqual(
            history["dependencies"]["iox-cpp"]["sourceSha"],
            "600d191e387405b3e957617f7a1e6dd7a29a1d94",
        )
        self.assertEqual(
            history["dependencies"]["ilic"]["sourceSha"],
            "cd74490b1fddfe38ac80288067e1af0dd800e8da",
        )

    def test_check_detects_a_changed_generated_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            for relative in (
                "release/dependencies.lock.json",
                "vcpkg/ports/ilic/vcpkg.json",
                "vcpkg/ports/ilic/portfile.cmake",
                "vcpkg/ports/iox-cpp/vcpkg.json",
                "vcpkg/ports/iox-cpp/portfile.cmake",
                ".github/workflows/community-preflight.yml",
                "extension_config.cmake",
            ):
                destination = root / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_text(
                    (ROOT / relative).read_text(encoding="utf-8"), encoding="utf-8"
                )
            release_metadata.sync(root, False)
            (root / "vcpkg/native-deps/vcpkg.json").write_text("{}\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "stale"):
                release_metadata.sync(root, True)


if __name__ == "__main__":
    unittest.main()
