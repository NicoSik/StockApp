#!/usr/bin/env python3
"""
Claude Code Stop hook: before finishing, look at the README of every folder
whose code changed.

Each changed file belongs to the nearest folder above it that has a README.md
(the repository root's README does not count - it describes the whole project).
The stop is blocked with a request to check that README when:

  - code in the folder changed and its README did not, or
  - a source file in the folder is not named anywhere in its README - a new
    class or module that the "What's inside" list has not caught up with.

Usually the answer is "still accurate"; the point is that the question gets
asked.

Changes are measured against where this branch left origin/main, plus anything
uncommitted, so committing mid-session does not hide them. Each folder is
raised once per distinct change: a fingerprint of its diff is remembered for
the session, so an unchanged folder does not come up again on every stop.
"""

import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile

MAX_FILES_SHOWN = 4
SOURCE_PATTERNS = ("*.java", "*.js")


def git(root, *args):
    result = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True)
    return result.stdout if result.returncode == 0 else ""


def unmentioned(root, folder):
    """Source files directly in the folder whose name its README never uses."""
    with open(os.path.join(root, folder, "README.md"), encoding="utf-8") as f:
        text = f.read()
    names = []
    for pattern in SOURCE_PATTERNS:
        for path in glob.glob(os.path.join(root, folder, pattern)):
            name = os.path.splitext(os.path.basename(path))[0]
            if not re.search(r"\b" + re.escape(name) + r"\b", text):
                names.append(name)
    return sorted(names)


def main():
    try:
        payload = json.load(sys.stdin)
    except ValueError:
        payload = {}

    # Already sent back once for this stop; never loop.
    if payload.get("stop_hook_active"):
        return

    cwd = payload.get("cwd") or os.getcwd()
    root = git(cwd, "rev-parse", "--show-toplevel").strip()
    if not root:
        return

    base = git(root, "merge-base", "HEAD", "origin/main").strip() or "HEAD"
    changed = set(git(root, "diff", "--name-only", base).split())
    changed |= set(git(root, "ls-files", "--others", "--exclude-standard").split())
    if not changed:
        return

    readmes_touched = {os.path.dirname(p) for p in changed if os.path.basename(p) == "README.md"}

    owners = {}
    for path in sorted(changed):
        if os.path.basename(path) == "README.md":
            continue
        folder = os.path.dirname(path)
        while folder:
            if os.path.isfile(os.path.join(root, folder, "README.md")):
                owners.setdefault(folder, []).append(path)
                break
            folder = os.path.dirname(folder)

    missing = {folder: unmentioned(root, folder) for folder in owners}
    pending = {folder: files for folder, files in owners.items()
               if folder not in readmes_touched or missing[folder]}
    if not pending:
        return

    state_file = os.path.join(
        tempfile.gettempdir(),
        "claude-folder-readmes-" + str(payload.get("session_id", "default")) + ".json")
    try:
        with open(state_file) as f:
            seen = json.load(f)
    except (OSError, ValueError):
        seen = {}

    flagged = {}
    for folder, files in pending.items():
        digest = hashlib.sha1(git(root, "diff", base, "--", *files).encode())
        for path in files:  # untracked files have no diff; hash their content
            full = os.path.join(root, path)
            if os.path.isfile(full) and not git(root, "ls-files", "--", path).strip():
                with open(full, "rb") as f:
                    digest.update(f.read())
        digest.update(" ".join(missing[folder]).encode())
        fingerprint = digest.hexdigest()
        if seen.get(folder) != fingerprint:
            flagged[folder] = files
            seen[folder] = fingerprint

    if not flagged:
        return

    with open(state_file, "w") as f:
        json.dump(seen, f)

    lines = ["Code changed in these folders; check their README.md is still true:", ""]
    for folder, files in sorted(flagged.items()):
        names = [os.path.basename(p) for p in files]
        shown = ", ".join(names[:MAX_FILES_SHOWN])
        if len(names) > MAX_FILES_SHOWN:
            shown += f" and {len(names) - MAX_FILES_SHOWN} more"
        lines.append(f"  - {folder}/README.md  (changed: {shown})")
        if missing[folder]:
            lines.append(f"      not mentioned in it: {', '.join(missing[folder])}")
    lines += [
        "",
        "Check each one still describes the folder's purpose, its rules, and - in",
        "\"What's inside\" - every class or module with its main functions. Add anything",
        "not mentioned. If it is all still accurate, no change is needed.",
        "If behaviour changed, check docs/ as well.",
    ]
    print(json.dumps({"decision": "block", "reason": "\n".join(lines)}))


if __name__ == "__main__":
    main()
