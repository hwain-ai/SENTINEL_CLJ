#!/usr/bin/python3
"""Validate the complete repository-local dependency directory."""

from __future__ import annotations

import hashlib
import json
import os
import stat
import sys
from pathlib import Path
from typing import NoReturn


TOP_KEYS = {"artifacts", "configuration", "schemaVersion"}
ARTIFACT_KEYS = {"artifact", "fileName", "sha256", "sizeBytes", "url", "version"}
CONFIGURATION_KEYS = {"path", "sha256", "sizeBytes"}


class DependencyError(ValueError):
    """A dependency lock or installed artifact is invalid."""


def fail(message: str) -> NoReturn:
    print(f"dependency lock verification failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def require_string(value: object, label: str) -> str:
    if not isinstance(value, str) or not value or any(character in value for character in ("\x00", "\t", "\n")):
        raise DependencyError(f"{label} must be a non-empty single-line string")
    return value


def validate_record(value: object, index: int) -> dict[str, object]:
    label = f"artifacts[{index}]"
    if not isinstance(value, dict) or set(value) != ARTIFACT_KEYS:
        raise DependencyError(f"{label} has unexpected fields")
    require_string(value["artifact"], f"{label}.artifact")
    require_string(value["version"], f"{label}.version")
    file_name = require_string(value["fileName"], f"{label}.fileName")
    if Path(file_name).name != file_name or not file_name.endswith(".jar"):
        raise DependencyError(f"{label}.fileName must be one JAR file name")
    url = require_string(value["url"], f"{label}.url")
    if not url.startswith("https://repo.maven.apache.org/maven2/") or not url.endswith(f"/{file_name}"):
        raise DependencyError(f"{label}.url is outside the locked Maven repository path")
    size = value["sizeBytes"]
    if isinstance(size, bool) or not isinstance(size, int) or size < 1:
        raise DependencyError(f"{label}.sizeBytes must be a positive integer")
    digest = require_string(value["sha256"], f"{label}.sha256")
    if len(digest) != 64 or any(character not in "0123456789abcdef" for character in digest):
        raise DependencyError(f"{label}.sha256 must be a lowercase SHA-256 digest")
    return value


def validate_configuration(value: object, lock_path: Path) -> None:
    if not isinstance(value, dict) or set(value) != CONFIGURATION_KEYS:
        raise DependencyError("configuration has unexpected fields")
    if value["path"] != "deps.edn":
        raise DependencyError("configuration.path must equal deps.edn")
    size = value["sizeBytes"]
    digest = value["sha256"]
    if isinstance(size, bool) or not isinstance(size, int) or size < 1:
        raise DependencyError("configuration.sizeBytes must be positive")
    if (not isinstance(digest, str) or len(digest) != 64
            or any(character not in "0123456789abcdef" for character in digest)):
        raise DependencyError("configuration.sha256 is invalid")
    configuration_path = lock_path.parent / "deps.edn"
    if (not configuration_path.is_file() or configuration_path.is_symlink()
            or configuration_path.stat().st_size != size
            or sha256(configuration_path) != digest):
        raise DependencyError("deps.edn does not match the dependency lock")


def load_lock(path: Path) -> list[dict[str, object]]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise DependencyError(f"cannot read {path}: {error}") from error
    if not isinstance(value, dict) or set(value) != TOP_KEYS:
        raise DependencyError("lock has unexpected fields")
    if value["schemaVersion"] != "sentinel-dependency-lock-v1":
        raise DependencyError("unsupported schemaVersion")
    validate_configuration(value["configuration"], path)
    raw_records = value["artifacts"]
    if not isinstance(raw_records, list) or not raw_records:
        raise DependencyError("artifacts must be a non-empty array")
    records = [validate_record(record, index) for index, record in enumerate(raw_records)]
    names = [str(record["fileName"]) for record in records]
    artifacts = [str(record["artifact"]) for record in records]
    if names != sorted(names) or len(names) != len(set(names)) or len(artifacts) != len(set(artifacts)):
        raise DependencyError("artifacts must have unique identities and sorted file names")
    return records


def verify_file(record: dict[str, object], path: Path) -> None:
    if not path.is_file() or path.is_symlink():
        raise DependencyError(f"dependency is not a regular file: {path}")
    if path.stat().st_size != record["sizeBytes"]:
        raise DependencyError(f"dependency size mismatch: {path}")
    if path.stat().st_uid != os.getuid() or stat.S_IMODE(path.stat().st_mode) & 0o022:
        raise DependencyError(f"dependency ownership or mode is invalid: {path}")
    if sha256(path) != record["sha256"]:
        raise DependencyError(f"dependency digest mismatch: {path}")


def verify_directory(records: list[dict[str, object]], directory: Path) -> None:
    if not directory.is_dir() or directory.is_symlink():
        raise DependencyError(f"dependency directory is absent: {directory}")
    parent = directory.parent
    if (directory.stat().st_uid != os.getuid()
            or stat.S_IMODE(directory.stat().st_mode) != 0o700
            or not parent.is_dir()
            or parent.is_symlink()
            or parent.stat().st_uid != os.getuid()
            or stat.S_IMODE(parent.stat().st_mode) != 0o700):
        raise DependencyError("dependency directory ownership or mode is invalid")
    expected = [str(record["fileName"]) for record in records]
    actual = sorted(path.name for path in directory.iterdir())
    if actual != expected:
        raise DependencyError("dependency directory manifest mismatch")
    for record in records:
        verify_file(record, directory / str(record["fileName"]))


def record_by_name(records: list[dict[str, object]], name: str) -> dict[str, object]:
    matches = [record for record in records if record["fileName"] == name]
    if len(matches) != 1:
        raise DependencyError(f"unknown dependency file: {name}")
    return matches[0]


def main(arguments: list[str]) -> int:
    if len(arguments) < 3:
        raise DependencyError("missing command")
    records = load_lock(Path(arguments[1]))
    command = arguments[2]
    if command == "manifest" and len(arguments) == 3:
        for record in records:
            print("\t".join(str(record[field]) for field in ("fileName", "url", "sizeBytes", "sha256")))
        return 0
    if command == "verify-file" and len(arguments) == 5:
        verify_file(record_by_name(records, arguments[3]), Path(arguments[4]))
        return 0
    if command == "verify-directory" and len(arguments) == 4:
        verify_directory(records, Path(arguments[3]))
        return 0
    if command == "class-path" and len(arguments) == 4:
        directory = Path(arguments[3])
        verify_directory(records, directory)
        print(":".join(str(directory / str(record["fileName"])) for record in records))
        return 0
    raise DependencyError("unknown command or arguments")


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv))
    except DependencyError as error:
        fail(str(error))
