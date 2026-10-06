#!/usr/bin/env python3
"""
Claude Code Stop hook: before finishing, look at the README of every folder
whose code changed.

Each changed file belongs to the nearest folder above it that has a README.md
(the repository root's README does not count - it describes the whole project).
If code in such a folder changed and its README did not, the stop is blocked
with a request to check that README. Usually the answer is "still accurate";
the point is that the question gets asked.

Changes are measured against where this branch left origin/main, plus anything
uncommitted, so committing mid-session does not hide them. Each folder is
raised once per distinct change: a fingerprint of its diff is remembered for
the session, so an unchanged folder does not come up again on every stop.
"""

import hashlib
import json
import os
import subprocess
import sys
import tempfile

MAX_FILES_SHOWN = 4


def git(root, *args):
    result = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True)
    return result.stdout if result.returncode == 0 else ""


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

    pending = {folder: files for folder, files in owners.items() if folder not in readmes_touched}
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
        fingerprint = digest.hexdigest()
        if seen.get(folder) != fingerprint:
            flagged[folder] = files
            seen[folder] = fingerprint

    if not flagged:
        return

    with open(state_file, "w") as f:
        json.dump(seen, f)

    lines = ["Code changed in these folders, but their README.md did not:", ""]
    for folder, files in sorted(flagged.items()):
        names = [os.path.basename(p) for p in files]
        shown = ", ".join(names[:MAX_FILES_SHOWN])
        if len(names) > MAX_FILES_SHOWN:
            shown += f" and {len(names) - MAX_FILES_SHOWN} more"
        lines.append(f"  - {folder}/README.md  ({shown})")
    lines += [
        "",
        "Read each one and check it still describes the folder's purpose and rules.",
        "Update it if it no longer does; if it is still accurate, no change is needed.",
        "If behaviour changed, check docs/ as well.",
    ]
    print(json.dumps({"decision": "block", "reason": "\n".join(lines)}))


if __name__ == "__main__":
    main()
