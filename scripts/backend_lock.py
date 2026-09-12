#!/usr/bin/python3
"""Verify the exact vendored clj-mutate backend before executing it."""

from __future__ import annotations

import hashlib
import json
import os
import stat
import sys
from pathlib import Path, PurePosixPath
from typing import NoReturn


EXPECTED_BACKEND = "clj-mutate"
EXPECTED_COMMIT = "e27dd5df63c4efdd66438587d1c5f49e73661b69"
EXPECTED_ARCHIVE_SHA256 = "fcd0638e1b60a46779f28d778f542ad52858738bcde4b925cf1ce28db2eb5b5a"
EXPECTED_OPERATORS = [
    "arithmetic",
    "boolean",
    "comparison",
    "conditional",
    "constant",
    "equality",
]
TOP_KEYS = {
    "backendName",
    "files",
    "operatorInventory",
    "schemaVersion",
    "sourceArchiveSha256",
    "sourceCommit",
}
FILE_KEYS = {"mode", "path", "sha256", "sizeBytes"}


class BackendLockError(ValueError):
    """The backend lock or vendored tree is outside the admitted identity."""


def fail(message: str) -> NoReturn:
    print(f"backend lock verification failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def require_sha256(value: object, label: str) -> str:
    if (not isinstance(value, str) or len(value) != 64
            or any(character not in "0123456789abcdef" for character in value)):
        raise BackendLockError(f"{label} must be a lowercase SHA-256 digest")
    return value


def require_path(value: object, label: str) -> str:
    if not isinstance(value, str) or not value or "\\" in value or "\x00" in value:
        raise BackendLockError(f"{label} must be a normalized relative POSIX path")
    parsed = PurePosixPath(value)
    if (parsed.is_absolute() or parsed.as_posix() != value
            or any(component in ("", ".", "..") for component in parsed.parts)):
        raise BackendLockError(f"{label} must be a normalized relative POSIX path")
    return value


def validate_file(value: object, index: int) -> dict[str, object]:
    label = f"files[{index}]"
    if not isinstance(value, dict) or set(value) != FILE_KEYS:
        raise BackendLockError(f"{label} has unexpected fields")
    require_path(value["path"], f"{label}.path")
    if value["mode"] != "0644":
        raise BackendLockError(f"{label}.mode must equal 0644")
    size = value["sizeBytes"]
    if isinstance(size, bool) or not isinstance(size, int) or size < 0:
        raise BackendLockError(f"{label}.sizeBytes must be a non-negative integer")
    require_sha256(value["sha256"], f"{label}.sha256")
    return value


def load_lock(path: Path) -> list[dict[str, object]]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise BackendLockError(f"cannot read {path}: {error}") from error
    if not isinstance(value, dict) or set(value) != TOP_KEYS:
        raise BackendLockError("lock has unexpected fields")
    if value["schemaVersion"] != "sentinel-backend-lock-v1":
        raise BackendLockError("unsupported schemaVersion")
    if value["backendName"] != EXPECTED_BACKEND:
        raise BackendLockError(f"backendName must equal {EXPECTED_BACKEND}")
    if value["sourceCommit"] != EXPECTED_COMMIT:
        raise BackendLockError(f"sourceCommit must equal {EXPECTED_COMMIT}")
    if value["sourceArchiveSha256"] != EXPECTED_ARCHIVE_SHA256:
        raise BackendLockError("sourceArchiveSha256 does not match the admitted archive")
    if value["operatorInventory"] != EXPECTED_OPERATORS:
        raise BackendLockError("operatorInventory does not match the admitted inventory")
    raw_files = value["files"]
    if not isinstance(raw_files, list) or not raw_files:
        raise BackendLockError("files must be a non-empty array")
    files = [validate_file(record, index) for index, record in enumerate(raw_files)]
    paths = [str(record["path"]) for record in files]
    if paths != sorted(paths) or len(paths) != len(set(paths)):
        raise BackendLockError("files must have unique paths in lexical order")
    return files


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def actual_files(root: Path) -> list[str]:
    if not root.is_dir() or root.is_symlink():
        raise BackendLockError(f"backend root is not a real directory: {root}")
    discovered: list[str] = []
    for directory, names, file_names in os.walk(root, topdown=True, followlinks=False):
        names.sort()
        file_names.sort()
        parent = Path(directory)
        for name in names:
            candidate = parent / name
            metadata = candidate.lstat()
            if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISDIR(metadata.st_mode):
                relative = candidate.relative_to(root).as_posix()
                raise BackendLockError(f"backend contains an unsafe directory entry: {relative}")
        for name in file_names:
            candidate = parent / name
            metadata = candidate.lstat()
            relative = candidate.relative_to(root).as_posix()
            if not stat.S_ISREG(metadata.st_mode):
                raise BackendLockError(f"backend contains a non-regular file: {relative}")
            discovered.append(relative)
    return sorted(discovered)


def verify_file(root: Path, record: dict[str, object]) -> None:
    relative = str(record["path"])
    path = root / relative
    metadata = path.lstat()
    if not stat.S_ISREG(metadata.st_mode):
        raise BackendLockError(f"backend entry is not a regular file: {relative}")
    if stat.S_IMODE(metadata.st_mode) != int(str(record["mode"]), 8):
        raise BackendLockError(f"backend mode mismatch: {relative}")
    if metadata.st_size != record["sizeBytes"]:
        raise BackendLockError(f"backend size mismatch: {relative}")
    if sha256(path) != record["sha256"]:
        raise BackendLockError(f"backend digest mismatch: {relative}")


def verify_backend(records: list[dict[str, object]], root: Path) -> None:
    expected = [str(record["path"]) for record in records]
    actual = actual_files(root)
    if actual != expected:
        missing = sorted(set(expected) - set(actual))
        extra = sorted(set(actual) - set(expected))
        raise BackendLockError(f"backend manifest mismatch; missing={missing}, extra={extra}")
    for record in records:
        verify_file(root, record)


def main(arguments: list[str]) -> int:
    if len(arguments) != 3:
        raise BackendLockError("usage: backend_lock.py LOCK BACKEND_ROOT")
    records = load_lock(Path(arguments[1]))
    verify_backend(records, Path(arguments[2]))
    print(f"verified {EXPECTED_BACKEND} backend at {EXPECTED_COMMIT}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv))
    except BackendLockError as error:
        fail(str(error))
