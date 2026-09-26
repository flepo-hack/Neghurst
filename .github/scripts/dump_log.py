#!/usr/bin/env python3
"""
Dump a Gradle log into GitHub annotations, and always exit 0.

## Why this exists

The Actions console truncates Gradle's output, and downloading a run log needs
admin rights on the repository, so a failing build could be seen only as "Process
completed with exit code 1". That is not enough to act on: a Kotlin compile
error, a failing assertion and a Gradle configuration failure all look
identical from the outside.

Annotations are the one channel that survives both limits, so the interesting
lines are re-emitted as `::error::` / `::warning::` and read straight off the
check run.

## Why it always exits 0

A previous version used `grep` in a pipeline under `set -o pipefail`, so when the
pattern matched nothing the reporting step itself failed. A diagnostic that
fails when there is nothing to report is worse than useless: it adds a second
red step that says nothing and masks the real one.
"""

from __future__ import annotations

import re
import sys

# Patterns worth calling an error. Ordered by how much they tell you.
ERROR_PATTERNS = [
    re.compile(r"^e: .*"),                       # Kotlin compiler
    re.compile(r"^.*\berror:\s.*", re.I),        # clang / ld / AAPT
    re.compile(r"^> Task .* FAILED"),
    re.compile(r"^Execution failed for task"),
    re.compile(r"^FAILURE:.*", re.M),
    re.compile(r"^\s*Caused by:"),
    re.compile(r"^\s*> .*FAILED"),
    re.compile(r"tests? .* (failed|FAILED)"),
    re.compile(r"^.*Unresolved reference.*"),
    re.compile(r"^.*Expecting.*but was.*"),      # JUnit assertion
    re.compile(r"^.*\bAssertionError\b.*"),
]
WARN_PATTERNS = [
    re.compile(r"^w: .*"),
    re.compile(r"^\s*\d+ tests? completed"),
]

# Noise that buries the signal in a wall of identical lines.
SKIP = re.compile(
    r"(Daemon will be stopped|Calculating task graph|To honour the JVM settings|"
    r"^\s*$|^> Task .* (UP-TO-DATE|NO-SOURCE|SKIPPED)$)"
)


def emit(level: str, message: str) -> None:
    # Annotations are line based; collapse so a multi line message cannot break
    # the workflow command parser.
    flat = " ".join(message.split())
    if len(flat) > 900:
        flat = flat[:900] + " ..."
    print(f"::{level}::{flat}")


def main(path: str) -> int:
    try:
        with open(path, "r", errors="replace") as fh:
            lines = fh.read().splitlines()
    except OSError as exc:
        emit("error", f"could not read {path}: {exc}")
        return 0

    emit("notice", f"{path}: {len(lines)} lines")

    seen: set[str] = set()
    errors = 0
    for line in lines:
        if SKIP.match(line):
            continue
        if len(seen) > 400:
            break
        for pat in ERROR_PATTERNS:
            if pat.search(line):
                key = line.strip()[:200]
                if key in seen:
                    break
                seen.add(key)
                emit("error", line)
                errors += 1
                break
        else:
            for pat in WARN_PATTERNS:
                if pat.search(line):
                    key = line.strip()[:200]
                    if key not in seen:
                        seen.add(key)
                        emit("warning", line)
                    break

    if errors == 0:
        # Nothing matched, so the log is the only evidence. Emit the tail; a
        # reader needs the last screen, which is where Gradle puts the reason.
        emit("error", "no known error pattern matched; dumping the tail of the log")
        for line in lines[-60:]:
            if line.strip():
                emit("warning", line)
    else:
        emit("notice", f"{errors} error line(s) reported")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "/tmp/test.log"))
