#!/usr/bin/python3
"""Build both locked tool trees in a new repository-shaped directory."""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


class CleanToolchainBootstrapTest(unittest.TestCase):
    def test_cached_verified_archives_produce_the_exact_portable_trees(self) -> None:
        with tempfile.TemporaryDirectory(prefix="sentinel-clj-clean-bootstrap-") as temporary:
            root = Path(temporary)
            scripts = root / "scripts"
            scripts.mkdir()
            for name in ("bootstrap-clojure.sh", "clojure.sh", "java.sh", "toolchain_lock.py"):
                shutil.copy2(REPOSITORY_ROOT / "scripts" / name, scripts / name)
            shutil.copy2(REPOSITORY_ROOT / "toolchain.lock.json", root / "toolchain.lock.json")
            downloads = root / ".toolchain" / "downloads"
            downloads.mkdir(parents=True, mode=0o700)
            (root / ".toolchain").chmod(0o700)
            downloads.chmod(0o700)
            for name in ("temurin-17.0.20.1+1.tar.gz", "clojure-tools-1.12.5.1664.tar.gz"):
                shutil.copy2(REPOSITORY_ROOT / ".toolchain" / "downloads" / name, downloads / name)
            marker = root / "hostile-startup-ran"
            startup = root / "startup.sh"
            startup.write_text(f"/usr/bin/touch {marker}\n", encoding="utf-8")
            environment = {**os.environ, "PATH": "/nonexistent", "BASH_ENV": str(startup)}

            bootstrap = subprocess.run(
                [str(scripts / "bootstrap-clojure.sh")],
                cwd=root,
                env=environment,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            self.assertEqual(0, bootstrap.returncode, bootstrap.stderr)
            self.assertFalse(marker.exists())
            java = subprocess.run(
                [str(scripts / "java.sh"), "-version"],
                cwd=root,
                env=environment,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            self.assertEqual(0, java.returncode, java.stderr)
            self.assertIn('openjdk version "17.0.20.1"', java.stderr)
            cli = subprocess.run(
                [str(scripts / "clojure.sh"), "--cli-version"],
                cwd=root,
                env=environment,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            self.assertEqual(0, cli.returncode, cli.stderr)
            self.assertEqual("Clojure CLI version 1.12.5.1664\n", cli.stdout)


if __name__ == "__main__":
    unittest.main()
