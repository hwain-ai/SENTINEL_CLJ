#!/usr/bin/python3
"""Acceptance tests for the repository-owned Clojure toolchain."""

from __future__ import annotations

import json
import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
CLEAN_SHEBANG = (
    "#!/usr/bin/env -S -i PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8 "
    "/usr/bin/bash --noprofile --norc"
)
JAVA_LAUNCHER = REPOSITORY_ROOT / "scripts" / "java.sh"
CLOJURE_LAUNCHER = REPOSITORY_ROOT / "scripts" / "clojure.sh"
BOOTSTRAP = REPOSITORY_ROOT / "scripts" / "bootstrap-clojure.sh"
LOCK_READER = REPOSITORY_ROOT / "scripts" / "toolchain_lock.py"
LOCK_PATH = REPOSITORY_ROOT / "toolchain.lock.json"


def run(command: list[str], *, environment: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=REPOSITORY_ROOT,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


class ToolchainLauncherTest(unittest.TestCase):
    def test_entrypoints_have_clean_environment_shebangs(self) -> None:
        for path in (JAVA_LAUNCHER, CLOJURE_LAUNCHER, BOOTSTRAP):
            first_line = path.read_text(encoding="utf-8").splitlines()[0]
            self.assertEqual(CLEAN_SHEBANG, first_line, path)
            self.assertTrue(os.access(path, os.X_OK), path)

    def test_bootstrap_and_java_ignore_hostile_path_and_bash_startup(self) -> None:
        with tempfile.TemporaryDirectory(prefix="sentinel-clj-hostile-") as temporary:
            temporary_path = Path(temporary)
            marker = temporary_path / "bash-env-ran"
            bash_env = temporary_path / "bash-env.sh"
            bash_env.write_text(f"/usr/bin/touch {marker}\n", encoding="utf-8")
            fake_bin = temporary_path / "bin"
            fake_bin.mkdir()
            for executable in ("bash", "java", "clojure"):
                fake = fake_bin / executable
                fake.write_text(f"#!/usr/bin/bash\n/usr/bin/touch {marker}\nexit 91\n", encoding="utf-8")
                fake.chmod(0o755)
            hostile = {
                **os.environ,
                "BASH_ENV": str(bash_env),
                "ENV": str(bash_env),
                "CDPATH": temporary,
                "PATH": str(fake_bin),
                "JAVA_TOOL_OPTIONS": "-Dsentinel.hostile=true",
                "_JAVA_OPTIONS": "-Dsentinel.hostile=true",
                "JDK_JAVA_OPTIONS": "-Dsentinel.hostile=true",
                "CLJ_CONFIG": str(temporary_path / "hostile-clj-config"),
                "HOME": str(temporary_path / "hostile-home"),
            }

            bootstrap = run([str(BOOTSTRAP)], environment=hostile)
            self.assertEqual(0, bootstrap.returncode, bootstrap.stderr)
            java = run([str(JAVA_LAUNCHER), "-version"], environment=hostile)
            self.assertEqual(0, java.returncode, java.stderr)
            self.assertIn('openjdk version "17.0.20.1"', java.stderr)
            self.assertIn("Temurin-17.0.20.1+1", java.stderr)
            self.assertNotIn("sentinel.hostile", java.stderr)
            self.assertFalse(marker.exists(), "hostile shell startup or PATH executable ran")

    def test_locked_install_trees_and_versions_are_exact(self) -> None:
        lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
        for tool in ("java", "clojure-cli"):
            record = lock["toolchains"][tool]
            install = REPOSITORY_ROOT / ".toolchain" / record["installDirectory"]
            verified = run(
                [
                    "/usr/bin/python3",
                    "-I",
                    str(LOCK_READER),
                    str(LOCK_PATH),
                    "verify-tree",
                    tool,
                    str(install),
                ]
            )
            self.assertEqual(0, verified.returncode, verified.stderr)

        cli_version = run([str(CLOJURE_LAUNCHER), "--cli-version"])
        self.assertEqual(0, cli_version.returncode, cli_version.stderr)
        self.assertEqual("Clojure CLI version 1.12.5.1664\n", cli_version.stdout)
        for relative in (".", "downloads", "runtime-home", "clj-config"):
            directory = REPOSITORY_ROOT / ".toolchain" / relative
            self.assertEqual(0o700, stat.S_IMODE(directory.stat().st_mode), directory)
            self.assertEqual(os.getuid(), directory.stat().st_uid, directory)

        with tempfile.TemporaryDirectory(prefix="sentinel-clj-archive-mode-") as temporary:
            archive = Path(temporary) / "clojure-tools.tar.gz"
            shutil.copy2(
                REPOSITORY_ROOT / ".toolchain" / "downloads" / "clojure-tools-1.12.5.1664.tar.gz",
                archive,
            )
            archive.chmod(0o666)
            rejected = run(
                [
                    "/usr/bin/python3",
                    "-I",
                    str(LOCK_READER),
                    str(LOCK_PATH),
                    "verify-archive",
                    "clojure-cli",
                    str(archive),
                ]
            )
            self.assertNotEqual(0, rejected.returncode)
            self.assertIn("ownership or mode", rejected.stderr)

    def test_partial_or_extra_install_tree_is_rejected_before_runtime_spawn(self) -> None:
        with tempfile.TemporaryDirectory(prefix="sentinel-clj-partial-") as temporary:
            root = Path(temporary)
            scripts = root / "scripts"
            scripts.mkdir()
            for source in (JAVA_LAUNCHER, CLOJURE_LAUNCHER, BOOTSTRAP, LOCK_READER):
                target = scripts / source.name
                shutil.copy2(source, target)
            shutil.copy2(LOCK_PATH, root / LOCK_PATH.name)
            lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
            java_record = lock["toolchains"]["java"]
            partial = root / ".toolchain" / java_record["installDirectory"] / "bin"
            partial.mkdir(parents=True)
            shutil.copy2(Path("/usr/bin/true"), partial / "java")
            marker = root / "runtime-ran"
            fake_bin = root / "fake-bin"
            fake_bin.mkdir()
            fake_java = fake_bin / "java"
            fake_java.write_text(f"#!/usr/bin/bash\n/usr/bin/touch {marker}\n", encoding="utf-8")
            fake_java.chmod(0o755)

            result = subprocess.run(
                [str(scripts / "java.sh"), "-version"],
                cwd=root,
                env={"PATH": str(fake_bin)},
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("installed tree", result.stderr)
            self.assertFalse(marker.exists(), "runtime spawned before tree verification")

            bootstrap = subprocess.run(
                [str(scripts / "bootstrap-clojure.sh")],
                cwd=root,
                env={"PATH": str(fake_bin)},
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            self.assertNotEqual(0, bootstrap.returncode)
            self.assertIn("partial or modified", bootstrap.stderr)
            self.assertFalse(marker.exists(), "runtime spawned during partial-tree bootstrap")

            partial_file = root / "partial-tree" / "only-file"
            partial_file.parent.mkdir()
            partial_file.write_text("partial\n", encoding="utf-8")
            verify = run(
                [
                    "/usr/bin/python3",
                    "-I",
                    str(LOCK_READER),
                    str(LOCK_PATH),
                    "verify-tree",
                    "java",
                    str(partial_file.parent),
                ]
            )
            self.assertNotEqual(0, verify.returncode)
            self.assertIn("installed tree", verify.stderr)

            complete_with_extra = root / "complete-with-extra"
            shutil.copytree(
                REPOSITORY_ROOT / ".toolchain" / lock["toolchains"]["clojure-cli"]["installDirectory"],
                complete_with_extra,
                symlinks=True,
            )
            (complete_with_extra / "unexpected").write_text("extra\n", encoding="utf-8")
            extra = run(
                [
                    "/usr/bin/python3",
                    "-I",
                    str(LOCK_READER),
                    str(LOCK_PATH),
                    "verify-tree",
                    "clojure-cli",
                    str(complete_with_extra),
                ]
            )
            self.assertNotEqual(0, extra.returncode)
            self.assertIn("installed tree", extra.stderr)

            (complete_with_extra / "unexpected").unlink()
            complete_with_extra.chmod(0o777)
            unsafe_root = run(
                [
                    "/usr/bin/python3",
                    "-I",
                    str(LOCK_READER),
                    str(LOCK_PATH),
                    "verify-tree",
                    "clojure-cli",
                    str(complete_with_extra),
                ]
            )
            self.assertNotEqual(0, unsafe_root.returncode)
            self.assertIn("group/world writable", unsafe_root.stderr)


if __name__ == "__main__":
    unittest.main()
