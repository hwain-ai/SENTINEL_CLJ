#!/usr/bin/python3
"""Read and verify the SENTINEL_CLJ toolchain lock without ambient packages."""

from __future__ import annotations

import hashlib
import json
import os
import stat
import sys
from pathlib import Path, PurePosixPath
from typing import NoReturn


TOP_LEVEL_KEYS = {"repository", "schemaVersion", "status", "toolchains"}
TOOL_KEYS = {"archive", "executable", "installDirectory", "installedTree", "version"}
ARCHIVE_KEYS = {"rootDirectory", "sha256", "sizeBytes", "url"}
EXECUTABLE_KEYS = {"relativePath", "sha256"}
TREE_KEYS = {"entries", "sha256"}
TOOLS = {"clojure-cli", "java"}
SHA256_LENGTH = 64


class LockError(ValueError):
    """The lock or an artifact does not match the declared contract."""


def fail(message: str) -> NoReturn:
    print(f"toolchain lock verification failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def require_exact_keys(value: object, keys: set[str], label: str) -> dict[str, object]:
    if not isinstance(value, dict) or set(value) != keys:
        raise LockError(f"{label} must contain exactly {sorted(keys)}")
    return value


def require_string(value: object, label: str) -> str:
    if not isinstance(value, str) or not value or "\x00" in value:
        raise LockError(f"{label} must be a non-empty string")
    return value


def require_sha256(value: object, label: str) -> str:
    digest = require_string(value, label)
    if len(digest) != SHA256_LENGTH or any(character not in "0123456789abcdef" for character in digest):
        raise LockError(f"{label} must be a lowercase SHA-256 digest")
    return digest


def require_positive_integer(value: object, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise LockError(f"{label} must be a positive integer")
    return value


def require_safe_relative_path(value: object, label: str) -> str:
    raw = require_string(value, label)
    path = PurePosixPath(raw)
    if path.is_absolute() or raw != path.as_posix() or any(part in ("", ".", "..") for part in path.parts):
        raise LockError(f"{label} must be a normalized relative POSIX path")
    return raw


def validate_archive(value: object, label: str) -> dict[str, object]:
    archive = require_exact_keys(value, ARCHIVE_KEYS, label)
    url = require_string(archive["url"], f"{label}.url")
    if not url.startswith("https://"):
        raise LockError(f"{label}.url must use HTTPS")
    require_sha256(archive["sha256"], f"{label}.sha256")
    require_positive_integer(archive["sizeBytes"], f"{label}.sizeBytes")
    root = require_safe_relative_path(archive["rootDirectory"], f"{label}.rootDirectory")
    if "/" in root:
        raise LockError(f"{label}.rootDirectory must have one path component")
    return archive


def validate_tool(value: object, label: str) -> dict[str, object]:
    tool = require_exact_keys(value, TOOL_KEYS, label)
    require_string(tool["version"], f"{label}.version")
    require_safe_relative_path(tool["installDirectory"], f"{label}.installDirectory")
    validate_archive(tool["archive"], f"{label}.archive")
    executable = require_exact_keys(tool["executable"], EXECUTABLE_KEYS, f"{label}.executable")
    require_safe_relative_path(executable["relativePath"], f"{label}.executable.relativePath")
    require_sha256(executable["sha256"], f"{label}.executable.sha256")
    tree = require_exact_keys(tool["installedTree"], TREE_KEYS, f"{label}.installedTree")
    require_positive_integer(tree["entries"], f"{label}.installedTree.entries")
    require_sha256(tree["sha256"], f"{label}.installedTree.sha256")
    return tool


def load_lock(path: Path) -> dict[str, object]:
    try:
        text = path.read_text(encoding="utf-8")
        value = json.loads(text)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise LockError(f"cannot read {path}: {error}") from error
    lock = require_exact_keys(value, TOP_LEVEL_KEYS, "lock")
    if lock["repository"] != "SENTINEL_CLJ":
        raise LockError("lock.repository must equal SENTINEL_CLJ")
    if lock["schemaVersion"] != "sentinel-toolchain-lock-v1":
        raise LockError("unsupported lock.schemaVersion")
    if lock["status"] != "locked":
        raise LockError("lock.status must equal locked")
    toolchains = require_exact_keys(lock["toolchains"], TOOLS, "lock.toolchains")
    for name in sorted(TOOLS):
        validate_tool(toolchains[name], f"lock.toolchains.{name}")
    return lock


def tool_record(lock: dict[str, object], name: str) -> dict[str, object]:
    if name not in TOOLS:
        raise LockError(f"unknown toolchain: {name}")
    return lock["toolchains"][name]  # type: ignore[index,return-value]


def nested_field(record: object, dotted_path: str) -> object:
    current = record
    for component in dotted_path.split("."):
        if not isinstance(current, dict) or component not in current:
            raise LockError(f"unknown lock field: {dotted_path}")
        current = current[component]
    if not isinstance(current, (str, int)) or isinstance(current, bool):
        raise LockError(f"lock field is not scalar: {dotted_path}")
    return current


def hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def safe_link_target(relative: PurePosixPath, target: str) -> None:
    target_path = PurePosixPath(target)
    if target_path.is_absolute() or "\x00" in target:
        raise LockError(f"unsafe installed symlink: {relative} -> {target}")
    depth = len(relative.parent.parts)
    for part in target_path.parts:
        if part == "..":
            depth -= 1
        elif part not in ("", "."):
            depth += 1
        if depth < 0:
            raise LockError(f"escaping installed symlink: {relative} -> {target}")


def tree_records(root: Path) -> list[bytes]:
    if not root.is_dir() or root.is_symlink():
        raise LockError(f"installed tree is not a real directory: {root}")
    root_metadata = root.lstat()
    root_mode = stat.S_IMODE(root_metadata.st_mode)
    if root_metadata.st_uid != os.getuid():
        raise LockError(f"installed tree root has a different owner: {root}")
    if root_mode & 0o022:
        raise LockError(f"installed tree root is group/world writable: {root}")
    records: list[bytes] = [b"\x00".join((b"D", f"{root_mode:o}".encode("ascii"), b".", b"")) + b"\n"]
    for directory, names, files in os.walk(root, topdown=True, followlinks=False):
        names.sort(key=lambda item: os.fsencode(item))
        files.sort(key=lambda item: os.fsencode(item))
        parent = Path(directory)
        for name in [*names, *files]:
            path = parent / name
            relative = PurePosixPath(path.relative_to(root).as_posix())
            encoded_path = relative.as_posix().encode("utf-8", "strict")
            metadata = path.lstat()
            mode = stat.S_IMODE(metadata.st_mode)
            if metadata.st_uid != os.getuid():
                raise LockError(f"installed tree entry has a different owner: {relative}")
            if not stat.S_ISLNK(metadata.st_mode) and mode & 0o022:
                raise LockError(f"installed tree entry is group/world writable: {relative}")
            if stat.S_ISLNK(metadata.st_mode):
                target = os.readlink(path)
                safe_link_target(relative, target)
                payload = target.encode("utf-8", "strict")
                kind = b"L"
            elif stat.S_ISDIR(metadata.st_mode):
                payload = b""
                kind = b"D"
            elif stat.S_ISREG(metadata.st_mode):
                payload = bytes.fromhex(hash_file(path))
                kind = b"F"
            else:
                raise LockError(f"unsupported installed tree entry: {relative}")
            record = b"\x00".join((kind, f"{mode:o}".encode("ascii"), encoded_path, payload)) + b"\n"
            records.append(record)
    records.sort(key=lambda record: record.split(b"\x00", 3)[2])
    return records


def tree_manifest(root: Path) -> tuple[int, str]:
    records = tree_records(root)
    digest = hashlib.sha256()
    for record in records:
        digest.update(record)
    return len(records), digest.hexdigest()


def verify_archive(record: dict[str, object], path: Path) -> None:
    archive = record["archive"]
    if not path.is_file() or path.is_symlink():
        raise LockError(f"archive is not a regular file: {path}")
    metadata = path.stat()
    if metadata.st_uid != os.getuid() or stat.S_IMODE(metadata.st_mode) & 0o022:
        raise LockError(f"archive ownership or mode is invalid: {path}")
    if metadata.st_size != archive["sizeBytes"]:  # type: ignore[index]
        raise LockError(f"archive size mismatch: {path}")
    if hash_file(path) != archive["sha256"]:  # type: ignore[index]
        raise LockError(f"archive digest mismatch: {path}")


def verify_tree(record: dict[str, object], path: Path) -> None:
    parent = path.parent
    if (not parent.is_dir() or parent.is_symlink()
            or parent.stat().st_uid != os.getuid()
            or stat.S_IMODE(parent.stat().st_mode) != 0o700):
        raise LockError(f"installed tree parent ownership or mode is invalid: {parent}")
    expected = record["installedTree"]
    actual_entries, actual_digest = tree_manifest(path)
    if actual_entries != expected["entries"] or actual_digest != expected["sha256"]:  # type: ignore[index]
        raise LockError(f"installed tree manifest mismatch: {path}")
    executable = record["executable"]
    executable_path = path / executable["relativePath"]  # type: ignore[index]
    if not executable_path.is_file() or executable_path.is_symlink():
        raise LockError(f"locked executable is not a regular file: {executable_path}")
    if hash_file(executable_path) != executable["sha256"]:  # type: ignore[index]
        raise LockError(f"locked executable digest mismatch: {executable_path}")


def print_usage() -> NoReturn:
    fail("usage: toolchain_lock.py LOCK get TOOL FIELD | verify-archive TOOL PATH | verify-tree TOOL PATH | digest-tree PATH")


def main(arguments: list[str]) -> int:
    if len(arguments) < 3:
        print_usage()
    lock_path = Path(arguments[1])
    command = arguments[2]
    if command == "digest-tree" and len(arguments) == 4:
        entries, digest = tree_manifest(Path(arguments[3]))
        print(json.dumps({"entries": entries, "sha256": digest}, separators=(",", ":")))
        return 0
    lock = load_lock(lock_path)
    if command == "get" and len(arguments) == 5:
        print(nested_field(tool_record(lock, arguments[3]), arguments[4]))
        return 0
    if command == "verify-archive" and len(arguments) == 5:
        verify_archive(tool_record(lock, arguments[3]), Path(arguments[4]))
        return 0
    if command == "verify-tree" and len(arguments) == 5:
        verify_tree(tool_record(lock, arguments[3]), Path(arguments[4]))
        return 0
    print_usage()


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv))
    except LockError as error:
        fail(str(error))
