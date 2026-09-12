#!/usr/bin/python3
"""Acceptance tests for the sealed, repository-owned dependency classpath."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import stat
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
CLEAN_SHEBANG = (
    "#!/usr/bin/env -S -i PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8 "
    "/usr/bin/bash --noprofile --norc"
)
BOOTSTRAP = REPOSITORY_ROOT / "scripts" / "bootstrap-dependencies.sh"
VERIFIER = REPOSITORY_ROOT / "scripts" / "verify-dependencies.sh"
TEST_RUNNER = REPOSITORY_ROOT / "scripts" / "test.sh"
LOCK_READER = REPOSITORY_ROOT / "scripts" / "dependency_lock.py"
LOCK_PATH = REPOSITORY_ROOT / "dependency-lock.json"


def run(command: list[str], environment: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=REPOSITORY_ROOT,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


class DependencyLauncherTest(unittest.TestCase):
    def test_entrypoints_are_direct_clean_environment_executables(self) -> None:
        for path in (BOOTSTRAP, VERIFIER, TEST_RUNNER):
            self.assertEqual(CLEAN_SHEBANG, path.read_text(encoding="utf-8").splitlines()[0])
            self.assertTrue(os.access(path, os.X_OK), path)

    def test_dependency_directory_matches_every_locked_artifact_and_has_no_extras(self) -> None:
        result = run(
            [
                "/usr/bin/python3",
                "-I",
                str(LOCK_READER),
                str(LOCK_PATH),
                "verify-directory",
                str(REPOSITORY_ROOT / ".toolchain" / "dependencies"),
            ]
        )
        self.assertEqual(0, result.returncode, result.stderr)
        lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
        versions = {record["artifact"]: record["version"] for record in lock["artifacts"]}
        self.assertEqual("1.12.0", versions["org.clojure/clojure"])
        self.assertEqual("1.4.2", versions["org.clojure/tools.reader"])
        directory = REPOSITORY_ROOT / ".toolchain" / "dependencies"
        self.assertEqual(0o700, stat.S_IMODE(directory.stat().st_mode))
        self.assertEqual(os.getuid(), directory.stat().st_uid)

    def test_test_runner_ignores_hostile_shell_java_and_clojure_configuration(self) -> None:
        with tempfile.TemporaryDirectory(prefix="sentinel-clj-dependency-hostile-") as temporary:
            root = Path(temporary)
            marker = root / "hostile-ran"
            startup = root / "startup.sh"
            startup.write_text(f"/usr/bin/touch {marker}\n", encoding="utf-8")
            fake_bin = root / "bin"
            fake_bin.mkdir()
            for name in ("bash", "java", "clojure"):
                executable = fake_bin / name
                executable.write_text(f"#!/usr/bin/bash\n/usr/bin/touch {marker}\nexit 90\n", encoding="utf-8")
                executable.chmod(0o755)
            environment = {
                **os.environ,
                "PATH": str(fake_bin),
                "BASH_ENV": str(startup),
                "ENV": str(startup),
                "HOME": str(root / "home"),
                "CLJ_CONFIG": str(root / "clj-config"),
                "JAVA_TOOL_OPTIONS": "-Dsentinel.hostile=true",
                "_JAVA_OPTIONS": "-Dsentinel.hostile=true",
                "JDK_JAVA_OPTIONS": "-Dsentinel.hostile=true",
                "JAVA_OPTS": "-Dsentinel.hostile=true",
                "CLJ_JVM_OPTS": "-Dsentinel.hostile=true",
                "CLASSPATH": str(root),
            }
            hostile_reader = root / "clojure" / "tools" / "reader.clj"
            hostile_reader.parent.mkdir(parents=True)
            hostile_reader.write_text(f"(spit {str(marker)!r} \"classpath-ran\")\n", encoding="utf-8")
            result = run([str(TEST_RUNNER), "--smoke"], environment)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("SENTINEL_CLJ smoke passed", result.stdout)
            self.assertNotIn("sentinel.hostile", result.stderr)
            self.assertFalse(marker.exists())

    def test_offline_profile_bypasses_cli_resolver_and_accepts_only_locked_profiles(self) -> None:
        accepted = run([str(REPOSITORY_ROOT / "scripts" / "clojure.sh"),
                        "--offline", "-M:test-runner-self"])
        self.assertEqual(0, accepted.returncode, accepted.stderr)
        self.assertIn("SENTINEL_CLJ smoke passed", accepted.stdout)

        rejected = run([str(REPOSITORY_ROOT / "scripts" / "clojure.sh"),
                        "--offline", "-M:unknown"])
        self.assertNotEqual(0, rejected.returncode)
        self.assertIn("offline mode requires exactly", rejected.stderr)

    def test_dependency_verification_failure_stops_before_the_jvm(self) -> None:
        lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
        source = REPOSITORY_ROOT / ".toolchain" / "dependencies"
        with tempfile.TemporaryDirectory(prefix="sentinel-clj-missing-dependency-") as temporary:
            temporary_root = Path(temporary)
            repository = temporary_root / "repository"
            scripts = repository / "scripts"
            dependencies = repository / ".toolchain" / "dependencies"
            scripts.mkdir(parents=True)
            dependencies.mkdir(parents=True, mode=0o700)
            (repository / ".toolchain").chmod(0o700)
            dependencies.chmod(0o700)
            for path in (TEST_RUNNER, LOCK_READER):
                shutil.copy2(path, scripts / path.name)
            shutil.copy2(LOCK_PATH, repository / LOCK_PATH.name)
            shutil.copy2(REPOSITORY_ROOT / "deps.edn", repository / "deps.edn")
            for record in lock["artifacts"][1:]:
                shutil.copy2(source / record["fileName"], dependencies / record["fileName"])
                (dependencies / record["fileName"]).chmod(0o644)
            fake_java = scripts / "java.sh"
            fake_java.write_text("#!/usr/bin/bash\n/usr/bin/printf 'JVM_STARTED\\n' >&2\n", encoding="utf-8")
            fake_java.chmod(0o755)

            result = subprocess.run(
                [str(scripts / "test.sh"), "--smoke"],
                cwd=repository,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn("JVM_STARTED", result.stderr)


if __name__ == "__main__":
    unittest.main()
