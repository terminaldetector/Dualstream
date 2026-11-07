#!/usr/bin/env python3
"""Print Android Lint findings compactly from its XML report.

Gradle's lint failure message does not list the issues, and the HTML report is
only useful once downloaded. In CI that means every lint failure costs a round
trip through a megabyte of log or an artifact download. This prints the errors
in a few lines instead, so the log itself says what to fix.

Usage:
  show_lint.py <lint-results.xml>                     errors in full
  show_lint.py <lint-results.xml> --include-warnings  warnings in full too
  show_lint.py <lint-results.xml> --summary           counts per issue only
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    include_warnings = "--include-warnings" in sys.argv
    summary_only = "--summary" in sys.argv
    if len(args) != 1:
        print(__doc__, file=sys.stderr)
        return 2

    path = Path(args[0])
    if not path.is_file():
        print(f"no lint report at {path}", file=sys.stderr)
        return 0        # never mask the real failure with a missing report

    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        print(f"could not parse {path}: {e}", file=sys.stderr)
        return 0

    wanted = {"Fatal", "Error"}
    if include_warnings:
        wanted.add("Warning")

    counts: dict[str, int] = {}
    per_issue: dict[tuple[str, str], int] = {}
    shown = 0
    for issue in root.iter("issue"):
        severity = issue.get("severity", "?")
        counts[severity] = counts.get(severity, 0) + 1
        key = (severity, issue.get("id", "?"))
        per_issue[key] = per_issue.get(key, 0) + 1
        if summary_only or severity not in wanted:
            continue
        shown += 1
        issue_id = issue.get("id", "?")
        message = (issue.get("message") or "").replace("\n", " ")
        loc = issue.find("location")
        where = "unknown"
        if loc is not None:
            f = loc.get("file", "?")
            # Trim the runner's absolute path down to something readable.
            for marker in ("/app/src/", "/app/build/", "/src/"):
                if marker in f:
                    f = f[f.index(marker) + 1:]
                    break
            line = loc.get("line")
            where = f"{f}:{line}" if line else f
        print(f"[{severity}] {issue_id}")
        print(f"    {where}")
        print(f"    {message}")
        explanation = (issue.get("explanation") or "").replace("\n", " ").strip()
        if explanation:
            print(f"    why: {explanation[:300]}")
        print()

    if summary_only:
        order = {"Fatal": 0, "Error": 1, "Warning": 2, "Information": 3}
        for (severity, issue_id), n in sorted(
            per_issue.items(), key=lambda kv: (order.get(kv[0][0], 9), -kv[1], kv[0][1])
        ):
            print(f"  {severity:12} {issue_id:35} x{n}")

    summary = ", ".join(f"{k}: {v}" for k, v in sorted(counts.items())) or "nothing reported"
    print(f"lint totals — {summary}")
    if summary_only:
        return 0
    if not shown:
        print("no errors in the report; the task may have failed for another reason")
    return 0


if __name__ == "__main__":
    sys.exit(main())
