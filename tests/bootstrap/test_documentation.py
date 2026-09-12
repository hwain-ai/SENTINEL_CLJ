#!/usr/bin/python3
"""Check the small OKF documentation bundle without third-party packages."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DOCS = REPOSITORY_ROOT / "docs"


class DocumentationTest(unittest.TestCase):
    def test_concept_documents_have_okf_frontmatter_and_are_indexed(self) -> None:
        index = (DOCS / "index.md").read_text(encoding="utf-8")
        concepts = sorted(path for path in DOCS.glob("*.md") if path.name not in {"index.md", "log.md"})
        self.assertTrue(concepts)
        for path in concepts:
            text = path.read_text(encoding="utf-8")
            self.assertTrue(text.startswith("---\n"), path)
            frontmatter = text.split("---\n", 2)[1]
            self.assertRegex(frontmatter, r"(?m)^type: .+$")
            self.assertIn(f"]({path.name})", index)
            if "resource: http" in frontmatter:
                self.assertRegex(frontmatter, r"(?m)^stale_after: \d{4}-\d{2}-\d{2}$")

    def test_index_links_exist_and_change_log_uses_okf_actions(self) -> None:
        index = (DOCS / "index.md").read_text(encoding="utf-8")
        for target in re.findall(r"\[[^]]+\]\(([^)#]+\.md)\)", index):
            self.assertTrue((DOCS / target).is_file(), target)
        log = (DOCS / "log.md").read_text(encoding="utf-8")
        entries = [line for line in log.splitlines() if line.startswith("- **")]
        self.assertTrue(entries)
        for entry in entries:
            self.assertRegex(entry, r"^- \*\*(Creation|Update|Deprecation)\*\*")

    def test_reader_facing_documents_start_with_an_easy_summary(self) -> None:
        for path in (REPOSITORY_ROOT / "README.md", DOCS / "index.md", DOCS / "architecture.md"):
            self.assertIn("한마디로", path.read_text(encoding="utf-8"), path)


if __name__ == "__main__":
    unittest.main()
