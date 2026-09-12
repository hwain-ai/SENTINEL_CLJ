import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
VERIFIER = REPOSITORY_ROOT / "scripts" / "backend_lock.py"
LOCK = REPOSITORY_ROOT / "backend.lock.json"
VENDORED = REPOSITORY_ROOT / "third_party" / "clj-mutate"


class BackendLockTests(unittest.TestCase):
    def run_verifier(self, root: Path) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["/usr/bin/python3", "-I", str(VERIFIER), str(LOCK), str(root)],
            cwd=REPOSITORY_ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_exact_vendored_e27dd5d_tree_is_accepted(self):
        result = self.run_verifier(VENDORED)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("e27dd5df63c4efdd66438587d1c5f49e73661b69", result.stdout)

    def test_changed_operator_or_extra_file_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix="sentinel-clj-backend-") as directory:
            copied = Path(directory) / "clj-mutate"
            shutil.copytree(VENDORED, copied)
            operator = copied / "src" / "clj_mutate" / "mutations.cljc"
            operator.write_bytes(operator.read_bytes() + b"\n")

            changed = self.run_verifier(copied)

            self.assertNotEqual(0, changed.returncode)
            operator.write_bytes(VENDORED.joinpath(
                "src", "clj_mutate", "mutations.cljc"
            ).read_bytes())
            copied.joinpath("unexpected.txt").write_text("unexpected", encoding="utf-8")

            extra = self.run_verifier(copied)

            self.assertNotEqual(0, extra.returncode)


if __name__ == "__main__":
    unittest.main()
