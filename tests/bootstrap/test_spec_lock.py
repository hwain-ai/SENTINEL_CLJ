#!/usr/bin/python3
"""Verify that the checked-in shared vectors match their byte lock."""

from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path, PurePosixPath


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
LOCK_PATH = REPOSITORY_ROOT / "spec-lock.json"


class SpecLockTest(unittest.TestCase):
    def test_every_locked_file_is_safe_unique_and_byte_exact(self) -> None:
        lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
        self.assertEqual({"files", "schemaVersion"}, set(lock))
        self.assertEqual("sentinel-spec-lock-v1", lock["schemaVersion"])
        paths: list[str] = []
        for record in lock["files"]:
            self.assertEqual({"path", "sha256"}, set(record))
            relative = PurePosixPath(record["path"])
            self.assertFalse(relative.is_absolute())
            self.assertNotIn("..", relative.parts)
            path = REPOSITORY_ROOT.joinpath(*relative.parts)
            self.assertTrue(path.is_file())
            self.assertFalse(path.is_symlink())
            self.assertEqual(record["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
            paths.append(record["path"])
        self.assertEqual(sorted(paths), paths)
        self.assertEqual(len(paths), len(set(paths)))


if __name__ == "__main__":
    unittest.main()
