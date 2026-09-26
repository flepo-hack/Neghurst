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

# Gradle phrases the cause several ways depending on whether the failure is at
# configuration time, at task execution, or in a dependency.
EXTRA_ERROR = re.compile(
    r"(What went wrong|A problem occurred|Could not |Unable to |No matching|"
    r"Cannot find|Could not resolve|Configuration .* failed|Plugin .* was not found|"
    r"Minimum supported Gradle|Unsupported class file|OutOfMemory|"
    r"Timeout waiting|Process .* completed with non-zero)",
    re.I,
)

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
            if pat.search(line) or EXTRA_ERROR.search(line):
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

    # Gradle's own "What went wrong" block is the diagnosis. It sits ABOVE a
    # stack trace, and dumping the tail only returns the trace, which is how the
    # previous version reported the cause as "FAILURE: Build failed with an
    # exception" and nothing else.
    emit("notice", f"{errors} matched error line(s) in {path}")
    start = None
    for i, line in enumerate(lines):
        if "What went wrong" in line:
            start = i
            break
    if start is not None:
        emit("error", "---- Gradle: What went wrong ----")
        depth = 0
        for line in lines[start + 1 : start + 60]:
            if not line.strip():
                continue
            if line.lstrip().startswith("at "):
                # A stack frame: noise here, and there are hundreds of them.
                depth += 1
                if depth > 4:
                    break
                continue
            if line.startswith("* Try:") or line.startswith("* Exception is:"):
                break
            emit("error", line)
            depth = 0

    # Any compiler diagnostics, which Gradle prints much earlier.
    compilers = [
        l for l in lines
        if re.match(r"^e: ", l) or re.match(r"^.*\berror:\s", l, re.I)
    ]
    if compilers:
        emit("error", "---- compiler diagnostics ----")
        for line in compilers[:40]:
            emit("error", line)

    # And the last non-frame lines, as a backstop.
    tail = [l for l in lines[-40:] if l.strip() and not l.lstrip().startswith("at ")]
    for line in tail:
        emit("warning", line)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "/tmp/test.log"))
